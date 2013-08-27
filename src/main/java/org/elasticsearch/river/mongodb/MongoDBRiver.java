/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.river.mongodb;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.elasticsearch.client.Requests.indexRequest;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.bson.BasicBSONObject;
import org.bson.types.BSONTimestamp;
import org.bson.types.ObjectId;
import org.elasticsearch.ElasticSearchInterruptedException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.StopWatch;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.jsr166y.LinkedTransferQueue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverIndexName;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;
import org.elasticsearch.river.mongodb.util.MongoDBHelper;
import org.elasticsearch.river.mongodb.util.MongoDBRiverHelper;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptService;

import com.mongodb.BasicDBObject;
import com.mongodb.Bytes;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientOptions.Builder;
import com.mongodb.MongoException;
import com.mongodb.MongoInterruptedException;
import com.mongodb.QueryOperators;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSDBFile;
import com.mongodb.util.JSON;

/**
 * @author richardwilly98 (Richard Louapre)
 * @author flaper87 (Flavio Percoco Premoli)
 * @author aparo (Alberto Paro)
 * @author kryptt (Rodolfo Hansen)
 */
public class MongoDBRiver extends AbstractRiverComponent implements River {

	public final static String IS_MONGODB_ATTACHMENT = "is_mongodb_attachment";
	public final static String MONGODB_ATTACHMENT = "mongodb_attachment";
	public final static String TYPE = "mongodb";
	public final static String NAME = "mongodb-river";
	public final static String STATUS = "_mongodbstatus";
	public final static String ENABLED = "enabled";
	public final static String DESCRIPTION = "MongoDB River Plugin";
	public final static String LAST_TIMESTAMP_FIELD = "_last_ts";
	public final static String MONGODB_LOCAL_DATABASE = "local";
	public final static String MONGODB_ADMIN_DATABASE = "admin";
	public final static String MONGODB_CONFIG_DATABASE = "config";
	public final static String MONGODB_ID_FIELD = "_id";
	public final static String MONGODB_OR_OPERATOR = "$or";
	public final static String MONGODB_AND_OPERATOR = "$and";
	public final static String MONGODB_NATURAL_OPERATOR = "$natural";
	public final static String OPLOG_COLLECTION = "oplog.rs";
	public final static String OPLOG_NAMESPACE = "ns";
	public final static String OPLOG_NAMESPACE_COMMAND = "$cmd";
	public final static String OPLOG_OBJECT = "o";
	public final static String OPLOG_UPDATE = "o2";
	public final static String OPLOG_OPERATION = "op";
	public final static String OPLOG_UPDATE_OPERATION = "u";
	public final static String OPLOG_INSERT_OPERATION = "i";
	public final static String OPLOG_DELETE_OPERATION = "d";
	public final static String OPLOG_COMMAND_OPERATION = "c";
	public final static String OPLOG_DROP_COMMAND_OPERATION = "drop";
	public final static String OPLOG_TIMESTAMP = "ts";
	public final static String GRIDFS_FILES_SUFFIX = ".files";
	public final static String GRIDFS_CHUNKS_SUFFIX = ".chunks";

	protected final Client client;
	protected final String riverIndexName;
	protected final ScriptService scriptService;

	protected final MongoDBRiverDefinition definition;
	protected final String mongoOplogNamespace;

	protected final BasicDBObject findKeys = new BasicDBObject();

	protected volatile List<Thread> tailerThreads = new ArrayList<Thread>();
	protected volatile Thread indexerThread;
	protected volatile Thread statusThread;
	protected volatile boolean active = false;
	protected volatile boolean startInvoked = false;

	private final BlockingQueue<Map<String, Object>> stream;
	private SocketFactory sslSocketFactory;

	private Mongo mongo;
	private DB adminDb;
	
	@Inject
	public MongoDBRiver(final RiverName riverName,
			final RiverSettings settings,
			@RiverIndexName final String riverIndexName, final Client client,
			final ScriptService scriptService) {
		super(riverName, settings);
		if (logger.isDebugEnabled()) {
			logger.debug("Prefix: [{}] - name: [{}]", logger.getPrefix(),
					logger.getName());
		}
		this.scriptService = scriptService;
		this.riverIndexName = riverIndexName;
		this.client = client;
		
		this.definition = MongoDBRiverDefinition.parseSettings(riverName, settings, scriptService);

		if (definition.getExcludeFields() != null) {
			for (String key : definition.getExcludeFields()) {
				findKeys.put(key, 0);
			}
		}
		mongoOplogNamespace = definition.getMongoDb() + "." + definition.getMongoCollection();
		
		if (definition.getThrottleSize() == -1) {
			stream = new LinkedTransferQueue<Map<String, Object>>();
		} else {
			stream = new ArrayBlockingQueue<Map<String, Object>>(definition.getThrottleSize());
		}

		statusThread = EsExecutors.daemonThreadFactory(
				settings.globalSettings(), "mongodb_river_status").newThread(
				new Status());
		statusThread.start();
	}

	public void start() {
		if (!MongoDBRiverHelper.isRiverEnabled(client, riverName.getName())) {
			logger.debug("Cannot start river {}. It is currently disabled",
					riverName.getName());
			startInvoked = true;
			return;
		}
		active = true;
		for (ServerAddress server : definition.getMongoServers()) {
			logger.info("Using mongodb server(s): host [{}], port [{}]",
					server.getHost(), server.getPort());
		}
		// http://stackoverflow.com/questions/5270611/read-maven-properties-file-inside-jar-war-file
		logger.info("{} version: [{}]", DESCRIPTION, MongoDBHelper.getRiverVersion());
		logger.info(
				"starting mongodb stream. options: secondaryreadpreference [{}], drop_collection [{}], include_collection [{}], throttlesize [{}], gridfs [{}], filter [{}], db [{}], collection [{}], script [{}], indexing to [{}]/[{}]",
				definition.isMongoSecondaryReadPreference(), definition.isDropCollection(),
				definition.getIncludeCollection(), definition.getThrottleSize(), definition.isMongoGridFS(), definition.getMongoFilter(),
				definition.getMongoDb(), definition.getMongoCollection(), definition.getScript(), definition.getIndexName(), definition.getTypeName());
		try {
			client.admin().indices().prepareCreate(definition.getIndexName()).execute()
					.actionGet();
		} catch (Exception e) {
			if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException) {
				// that's fine
				logger.warn("index alredy exsists");
			} else if (ExceptionsHelper.unwrapCause(e) instanceof ClusterBlockException) {
				// ok, not recovered yet..., lets start indexing and hope we
				// recover by the first bulk
				// TODO: a smarter logic can be to register for cluster event
				// listener here, and only start sampling when the
				// block is removed...
				logger.warn("ok, not recovered yet..., lets start indexing and hope we");
			} else {
				logger.warn("failed to create index [{}], disabling river...",
						e, definition.getIndexName());
				return;
			}
		}
		logger.warn("index created");
		
		
		if (definition.isMongoGridFS()) {
			try {
				if (logger.isDebugEnabled()) {
					logger.debug("Set explicit attachment mapping.");
				}
				client.admin().indices().preparePutMapping(definition.getIndexName())
						.setType(definition.getTypeName()).setSource(getGridFSMapping())
						.execute().actionGet();
			} catch (Exception e) {
				logger.warn("Failed to set explicit mapping (attachment): {}",
						e);
			}
		}

		if (isMongos()) {
			DBCursor cursor = getConfigDb().getCollection("shards").find();
			while (cursor.hasNext()) {
				DBObject item = cursor.next();
				logger.info(item.toString());
				List<ServerAddress> servers = getServerAddressForReplica(item);
				if (servers != null) {
					String replicaName = item.get("_id").toString();
					Thread tailerThread = EsExecutors.daemonThreadFactory(
							settings.globalSettings(),
							"mongodb_river_slurper-" + replicaName).newThread(
							new Slurper(servers));
					tailerThreads.add(tailerThread);
				}
			}
		} else {
			Thread tailerThread = EsExecutors.daemonThreadFactory(
					settings.globalSettings(), "mongodb_river_slurper")
					.newThread(new Slurper(definition.getMongoServers()));
			tailerThreads.add(tailerThread);
		}

		for (Thread thread : tailerThreads) {
			thread.start();
		}

		indexerThread = EsExecutors.daemonThreadFactory(
				settings.globalSettings(), "mongodb_river_indexer").newThread(
				new Indexer());
		indexerThread.start();

		startInvoked = true;
	}

	private boolean isMongos() {
		DB adminDb = getAdminDb();
		if (adminDb == null) {
			return false;
		}
		CommandResult cr = adminDb
				.command(new BasicDBObject("serverStatus", 1));
		if (cr == null || cr.get("process") == null) {
			logger.warn("serverStatus return null.");
			return false;
		}
		String process = cr.get("process").toString().toLowerCase();
		if (logger.isTraceEnabled()) {
			logger.trace("serverStatus: {}", cr);
			logger.trace("process: {}", process);
		}
		// return (cr.get("process").equals("mongos"));
		// Fix for https://jira.mongodb.org/browse/SERVER-9160
		return (process.contains("mongos"));
	}

	private DB getAdminDb() {
		if (adminDb == null) {
			adminDb = getMongoClient().getDB(MONGODB_ADMIN_DATABASE);
			if (!definition.getMongoAdminUser().isEmpty() && !definition.getMongoAdminPassword().isEmpty()
					&& !adminDb.isAuthenticated()) {
				logger.info("Authenticate {} with {}", MONGODB_ADMIN_DATABASE,
						definition.getMongoAdminUser());

				try {
					CommandResult cmd = adminDb.authenticateCommand(
							definition.getMongoAdminUser(), definition.getMongoAdminPassword().toCharArray());
					if (!cmd.ok()) {
						logger.error("Autenticatication failed for {}: {}",
								MONGODB_ADMIN_DATABASE, cmd.getErrorMessage());
					}
				} catch (MongoException mEx) {
					logger.warn("getAdminDb() failed", mEx);
				}
			}
		}
		return adminDb;
	}

	private DB getConfigDb() {
		DB configDb = getMongoClient().getDB(MONGODB_CONFIG_DATABASE);
		if (!definition.getMongoAdminUser().isEmpty() && !definition.getMongoAdminPassword().isEmpty()
				&& getAdminDb().isAuthenticated()) {
			configDb = getAdminDb().getMongo().getDB(MONGODB_CONFIG_DATABASE);
			// } else if (!mongoDbUser.isEmpty() && !mongoDbPassword.isEmpty()
			// && !configDb.isAuthenticated()) {
			// logger.info("Authenticate {} with {}", mongoDb, mongoDbUser);
			// CommandResult cmd = configDb.authenticateCommand(mongoDbUser,
			// mongoDbPassword.toCharArray());
			// if (!cmd.ok()) {
			// logger.error("Authentication failed for {}: {}",
			// DB_CONFIG, cmd.getErrorMessage());
			// }
		}
		return configDb;
	}

	// TODO: MongoClientOptions should be configurable
	private Mongo getMongoClient() {
		if (mongo == null) {
			Builder builder = MongoClientOptions.builder()
					.autoConnectRetry(true).connectTimeout(15000)
					.socketKeepAlive(true).socketTimeout(60000);
			if (definition.isMongoUseSSL()) {
				builder.socketFactory(getSSLSocketFactory());
			}

			MongoClientOptions mco = builder.build();
			mongo = new MongoClient(definition.getMongoServers(), mco);
		}
		return mongo;
	}

	private void closeMongoClient() {
		if (adminDb != null) {
			adminDb = null;
		}
		if (mongo != null) {
			mongo.close();
			mongo = null;
		}
	}

	private List<ServerAddress> getServerAddressForReplica(DBObject item) {
		String definition = item.get("host").toString();
		if (definition.contains("/")) {
			definition = definition.substring(definition.indexOf("/") + 1);
		}
		if (logger.isDebugEnabled()) {
			logger.debug("getServerAddressForReplica - definition: {}",
					definition);
		}
		List<ServerAddress> servers = new ArrayList<ServerAddress>();
		for (String server : definition.split(",")) {
			try {
				servers.add(new ServerAddress(server));
			} catch (UnknownHostException uhEx) {
				logger.warn("failed to execute bulk", uhEx);
			}
		}
		return servers;
	}

	@Override
	public void close() {
		logger.info("closing mongodb stream river");
		try {
			for (Thread thread : tailerThreads) {
				thread.interrupt();
			}
			tailerThreads.clear();
			if (indexerThread != null) {
				indexerThread.interrupt();
				indexerThread = null;
			}
			closeMongoClient();
		} catch (Throwable t) {
			logger.error("Fail to close river {}", t, riverName.getName());
		} finally {
			active = false;
		}
	}

	private SocketFactory getSSLSocketFactory() {
		if (sslSocketFactory != null)
			return sslSocketFactory;

		if (!definition.isMongoSSLVerifyCertificate()) {
			try {
				final TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {

					@Override
					public X509Certificate[] getAcceptedIssuers() {
						return null;
					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain,
							String authType) throws CertificateException {
					}

					@Override
					public void checkClientTrusted(X509Certificate[] chain,
							String authType) throws CertificateException {
					}
				} };
				final SSLContext sslContext = SSLContext.getInstance("SSL");
				sslContext.init(null, trustAllCerts,
						new java.security.SecureRandom());
				// Create an ssl socket factory with our all-trusting manager
				sslSocketFactory = sslContext.getSocketFactory();
				return sslSocketFactory;
			} catch (Exception ex) {
				logger.error(
						"Unable to build ssl socket factory without certificate validation, using default instead.",
						ex);
			}
		}
		sslSocketFactory = SSLSocketFactory.getDefault();
		return sslSocketFactory;
	}

	private class Indexer implements Runnable {

		private final ESLogger logger = ESLoggerFactory.getLogger(this
				.getClass().getName());
		private int deletedDocuments = 0;
		private int insertedDocuments = 0;
		private int updatedDocuments = 0;
		private StopWatch sw;
		private ExecutableScript scriptExecutable;

		@Override
		public void run() {
			while (active) {
				sw = new StopWatch().start();
				deletedDocuments = 0;
				insertedDocuments = 0;
				updatedDocuments = 0;

				if (definition.getScript() != null && definition.getScriptType() != null) {
					scriptExecutable = scriptService.executable(definition.getScriptType(),
							definition.getScript(), ImmutableMap.of("logger", logger));
				}

				try {
					BSONTimestamp lastTimestamp = null;
					BulkRequestBuilder bulk = client.prepareBulk();

					// 1. Attempt to fill as much of the bulk request as
					// possible
					Map<String, Object> data = stream.take();
					lastTimestamp = updateBulkRequest(bulk, data);
					while ((data = stream.poll(definition.getBulkTimeout().millis(),
							MILLISECONDS)) != null) {
						lastTimestamp = updateBulkRequest(bulk, data);
						if (bulk.numberOfActions() >= definition.getBulkSize()) {
							break;
						}
					}

					// 2. Update the timestamp
					if (lastTimestamp != null) {
						updateLastTimestamp(mongoOplogNamespace, lastTimestamp,
								bulk);
					}

					// 3. Execute the bulk requests
					try {
						BulkResponse response = bulk.execute().actionGet();
						if (response.hasFailures()) {
							// TODO write to exception queue?
							logger.warn("failed to execute"
									+ response.buildFailureMessage());
						}
					} catch (ElasticSearchInterruptedException esie) {
						logger.warn(
								"river-mongodb indexer bas been interrupted",
								esie);
						Thread.currentThread().interrupt();
					} catch (Exception e) {
						logger.warn("failed to execute bulk", e);
					}

				} catch (InterruptedException e) {
					if (logger.isDebugEnabled()) {
						logger.debug("river-mongodb indexer interrupted");
					}
					Thread.currentThread().interrupt();
					break;
				}
				logStatistics();
			}
		}

		@SuppressWarnings({ "unchecked" })
		private BSONTimestamp updateBulkRequest(final BulkRequestBuilder bulk,
				Map<String, Object> data) {
			if (data.get(MONGODB_ID_FIELD) == null
					&& !data.get(OPLOG_OPERATION).equals(
							OPLOG_COMMAND_OPERATION)) {
				logger.warn(
						"Cannot get object id. Skip the current item: [{}]",
						data);
				return null;
			}
			BSONTimestamp lastTimestamp = (BSONTimestamp) data
					.get(OPLOG_TIMESTAMP);
			String operation = data.get(OPLOG_OPERATION).toString();
			// String objectId = data.get(MONGODB_ID_FIELD).toString();
			String objectId = "";
			if (data.get(MONGODB_ID_FIELD) != null) {
				objectId = data.get(MONGODB_ID_FIELD).toString();
			}
			data.remove(OPLOG_TIMESTAMP);
			data.remove(OPLOG_OPERATION);
			if (logger.isDebugEnabled()) {
				logger.debug("updateBulkRequest for id: [{}], operation: [{}]",
						objectId, operation);
			}

			if (!definition.getIncludeCollection().isEmpty()) {
				logger.trace(
						"About to include collection. set attribute {} / {} ",
						definition.getIncludeCollection(), definition.getMongoCollection());
				data.put(definition.getIncludeCollection(), definition.getMongoCollection());
			}

			Map<String, Object> ctx = null;
			try {
				ctx = XContentFactory.xContent(XContentType.JSON)
						.createParser("{}").mapAndClose();
			} catch (IOException e) {
				logger.warn("failed to parse {}", e);
			}
			if (scriptExecutable != null) {
				if (ctx != null) {
					ctx.put("document", data);
					ctx.put("operation", operation);
					if (!objectId.isEmpty()) {
						ctx.put("id", objectId);
					}
					if (logger.isDebugEnabled()) {
						logger.debug("Script to be executed: {}",
								scriptExecutable);
						logger.debug("Context before script executed: {}", ctx);
					}
					scriptExecutable.setNextVar("ctx", ctx);
					try {
						scriptExecutable.run();
						// we need to unwrap the context object...
						ctx = (Map<String, Object>) scriptExecutable
								.unwrap(ctx);
					} catch (Exception e) {
						logger.warn("failed to script process {}, ignoring", e,
								ctx);
					}
					if (logger.isDebugEnabled()) {
						logger.debug("Context after script executed: {}", ctx);
					}
					if (ctx.containsKey("ignore")
							&& ctx.get("ignore").equals(Boolean.TRUE)) {
						logger.debug("From script ignore document id: {}",
								objectId);
						// ignore document
						return lastTimestamp;
					}
					if (ctx.containsKey("deleted")
							&& ctx.get("deleted").equals(Boolean.TRUE)) {
						ctx.put("operation", OPLOG_DELETE_OPERATION);
					}
					if (ctx.containsKey("document")) {
						data = (Map<String, Object>) ctx.get("document");
						logger.debug("From script document: {}", data);
					}
					if (ctx.containsKey("operation")) {
						operation = ctx.get("operation").toString();
						logger.debug("From script operation: {}", operation);
					}
				}
			}

			try {
				String index = extractIndex(ctx);
				String type = extractType(ctx);
				String parent = extractParent(ctx);
				String routing = extractRouting(ctx);
				objectId = extractObjectId(ctx, objectId);
				if (logger.isDebugEnabled()) {
					logger.debug(
							"Operation: {} - index: {} - type: {} - routing: {} - parent: {}",
							operation, index, type, routing, parent);
				}
				if (OPLOG_INSERT_OPERATION.equals(operation)) {
					if (logger.isDebugEnabled()) {
						logger.debug(
								"Insert operation - id: {} - contains attachment: {}",
								operation, objectId,
								data.containsKey(IS_MONGODB_ATTACHMENT));
					}
					bulk.add(indexRequest(index).type(type).id(objectId)
							.source(build(data, objectId)).routing(routing)
							.parent(parent));
					insertedDocuments++;
				}
				if (OPLOG_UPDATE_OPERATION.equals(operation)) {
					if (logger.isDebugEnabled()) {
						logger.debug(
								"Update operation - id: {} - contains attachment: {}",
								objectId,
								data.containsKey(IS_MONGODB_ATTACHMENT));
					}
					bulk.add(new DeleteRequest(index, type, objectId).routing(
							routing).parent(parent));
					bulk.add(indexRequest(index).type(type).id(objectId)
							.source(build(data, objectId)).routing(routing)
							.parent(parent));
					updatedDocuments++;
					// new UpdateRequest(definition.getIndexName(), definition.getTypeName(), objectId)
				}
				if (OPLOG_DELETE_OPERATION.equals(operation)) {
					logger.info("Delete request [{}], [{}], [{}]", index, type,
							objectId);
					bulk.add(new DeleteRequest(index, type, objectId).routing(
							routing).parent(parent));
					deletedDocuments++;
				}
				if (OPLOG_COMMAND_OPERATION.equals(operation)) {
					if (definition.isDropCollection()) {
						if (data.containsKey(OPLOG_DROP_COMMAND_OPERATION)
								&& data.get(OPLOG_DROP_COMMAND_OPERATION)
										.equals(definition.getMongoCollection())) {
							logger.info("Drop collection request [{}], [{}]",
									index, type);
							bulk.request().requests().clear();
							client.admin().indices().prepareRefresh(index)
									.execute().actionGet();
							Map<String, MappingMetaData> mappings = client
									.admin().cluster().prepareState().execute()
									.actionGet().getState().getMetaData()
									.index(index).mappings();
							logger.trace("mappings contains type {}: {}", type,
									mappings.containsKey(type));
							if (mappings.containsKey(type)) {
								/*
								 * Issue #105 - Mapping changing from custom
								 * mapping to dynamic when drop_collection =
								 * true Should capture the existing mapping
								 * metadata (in case it is has been customized
								 * before to delete.
								 */
								MappingMetaData mapping = mappings.get(type);
								client.admin().indices()
										.prepareDeleteMapping(index)
										.setType(type).execute().actionGet();
								PutMappingResponse pmr = client.admin()
										.indices().preparePutMapping(index)
										.setType(type)
										.setSource(mapping.source().string())
										.execute().actionGet();
								if (!pmr.isAcknowledged()) {
									logger.error(
											"Failed to put mapping {} / {} / {}.",
											index, type, mapping.source());
								}
							}

							deletedDocuments = 0;
							updatedDocuments = 0;
							insertedDocuments = 0;
							logger.info(
									"Delete request for index / type [{}] [{}] successfully executed.",
									index, type);
						} else {
							logger.debug("Database command {}", data);
						}
					} else {
						logger.info(
								"Ignore drop collection request [{}], [{}]. The option has been disabled.",
								index, type);
					}
				}
			} catch (IOException e) {
				logger.warn("failed to parse {}", e, data);
			}
			return lastTimestamp;
		}

		private XContentBuilder build(final Map<String, Object> data,
				final String objectId) throws IOException {
			if (data.containsKey(IS_MONGODB_ATTACHMENT)) {
				logger.info("Add Attachment: {} to index {} / type {}",
						objectId, definition.getIndexName(), definition.getTypeName());
				return MongoDBHelper.serialize((GridFSDBFile) data
						.get(MONGODB_ATTACHMENT));
			} else {
				return XContentFactory.jsonBuilder().map(data);
			}
		}

		private String extractObjectId(Map<String, Object> ctx, String objectId) {
			Object id = ctx.get("id");
			if (id == null) {
				return objectId;
			} else {
				return id.toString();
			}
		}

		private String extractParent(Map<String, Object> ctx) {
			Object parent = ctx.get("_parent");
			if (parent == null) {
				return null;
			} else {
				return parent.toString();
			}
		}

		private String extractRouting(Map<String, Object> ctx) {
			Object routing = ctx.get("_routing");
			if (routing == null) {
				return null;
			} else {
				return routing.toString();
			}
		}

		private String extractType(Map<String, Object> ctx) {
			Object type = ctx.get("_type");
			if (type == null) {
				return definition.getTypeName();
			} else {
				return type.toString();
			}
		}

		private String extractIndex(Map<String, Object> ctx) {
			String index = (String) ctx.get("_index");
			if (index == null) {
				index = definition.getIndexName();
			}
			return index;
		}

		private void logStatistics() {
			long totalDocuments = deletedDocuments + insertedDocuments;
			long totalTimeInSeconds = sw.stop().totalTime().seconds();
			long totalDocumentsPerSecond = (totalTimeInSeconds == 0) ? totalDocuments
					: totalDocuments / totalTimeInSeconds;
			logger.info(
					"Indexed {} documents, {} insertions, {} updates, {} deletions, {} documents per second",
					totalDocuments, insertedDocuments, updatedDocuments,
					deletedDocuments, totalDocumentsPerSecond);
		}
	}

	private class Slurper implements Runnable {

		private Mongo mongo;
		private DB slurpedDb;
		private DBCollection slurpedCollection;
		private DB oplogDb;
		private DBCollection oplogCollection;
		private final List<ServerAddress> mongoServers;

		public Slurper(List<ServerAddress> mongoServers) {
			this.mongoServers = mongoServers;
		}

		private boolean assignCollections() {
			DB adminDb = mongo.getDB(MONGODB_ADMIN_DATABASE);
			oplogDb = mongo.getDB(MONGODB_LOCAL_DATABASE);

			if (!definition.getMongoAdminUser().isEmpty() && !definition.getMongoAdminPassword().isEmpty()) {
				logger.info("Authenticate {} with {}", MONGODB_ADMIN_DATABASE,
						definition.getMongoAdminUser());

				CommandResult cmd = adminDb.authenticateCommand(definition.getMongoAdminUser(),
						definition.getMongoAdminPassword().toCharArray());
				if (!cmd.ok()) {
					logger.error("Autenticatication failed for {}: {}",
							MONGODB_ADMIN_DATABASE, cmd.getErrorMessage());
					// Can still try with mongoLocal credential if provided.
					// return false;
				}
				oplogDb = adminDb.getMongo().getDB(MONGODB_LOCAL_DATABASE);
			}

			if (!definition.getMongoLocalUser().isEmpty() && !definition.getMongoLocalPassword().isEmpty()
					&& !oplogDb.isAuthenticated()) {
				logger.info("Authenticate {} with {}", MONGODB_LOCAL_DATABASE,
						definition.getMongoLocalUser());
				CommandResult cmd = oplogDb.authenticateCommand(definition.getMongoLocalUser(),
						definition.getMongoLocalPassword().toCharArray());
				if (!cmd.ok()) {
					logger.error("Autenticatication failed for {}: {}",
							MONGODB_LOCAL_DATABASE, cmd.getErrorMessage());
					return false;
				}
			}

			Set<String> collections = oplogDb.getCollectionNames();
			if (!collections.contains(OPLOG_COLLECTION)) {
				logger.error("Cannot find "
						+ OPLOG_COLLECTION
						+ " collection. Please use check this link: http://goo.gl/2x5IW");
				return false;
			}
			oplogCollection = oplogDb.getCollection(OPLOG_COLLECTION);

			slurpedDb = mongo.getDB(definition.getMongoDb());
			if (!definition.getMongoAdminUser().isEmpty() && !definition.getMongoAdminPassword().isEmpty()
					&& adminDb.isAuthenticated()) {
				slurpedDb = adminDb.getMongo().getDB(definition.getMongoDb());
			}

			// Not necessary as local user has access to all databases.
			// http://docs.mongodb.org/manual/reference/local-database/
			// if (!mongoDbUser.isEmpty() && !mongoDbPassword.isEmpty()
			// && !slurpedDb.isAuthenticated()) {
			// logger.info("Authenticate {} with {}", mongoDb, mongoDbUser);
			// CommandResult cmd = slurpedDb.authenticateCommand(mongoDbUser,
			// mongoDbPassword.toCharArray());
			// if (!cmd.ok()) {
			// logger.error("Autenticatication failed for {}: {}",
			// mongoDb, cmd.getErrorMessage());
			// return false;
			// }
			// }
			slurpedCollection = slurpedDb.getCollection(definition.getMongoCollection());

			return true;
		}

		@Override
		public void run() {
			Builder builder = MongoClientOptions.builder()
					.autoConnectRetry(true).connectTimeout(15000)
					.socketKeepAlive(true).socketTimeout(60000);
			if (definition.isMongoUseSSL()) {
				builder.socketFactory(getSSLSocketFactory());
			}

			// TODO: MongoClientOptions should be configurable
			MongoClientOptions mco = builder.build();
			mongo = new MongoClient(mongoServers, mco);

			if (definition.isMongoSecondaryReadPreference()) {
				mongo.setReadPreference(ReadPreference.secondaryPreferred());
			}

			while (active) {
				try {
					if (!assignCollections()) {
						break; // failed to assign oplogCollection or
								// slurpedCollection
					}

					DBCursor oplogCursor = oplogCursor(null);
					if (oplogCursor == null) {
						oplogCursor = processFullCollection();
					}

					while (oplogCursor.hasNext()) {
						DBObject item = oplogCursor.next();
						processOplogEntry(item);
					}
					logger.trace("*** Try again in few seconds...");
					Thread.sleep(500);
				} catch (MongoInterruptedException mIEx) {
					logger.error("Mongo driver has been interrupted", mIEx);
					// active = false;
					break;
				} catch (MongoException mEx) {
					logger.error("Mongo gave an exception", mEx);
				} catch (NoSuchElementException nEx) {
					logger.warn("A mongoDB cursor bug ?", nEx);
				} catch (InterruptedException e) {
					if (logger.isDebugEnabled()) {
						logger.debug("river-mongodb slurper interrupted");
					}
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		/*
		 * Remove fscynlock and unlock -
		 * https://github.com/richardwilly98/elasticsearch
		 * -river-mongodb/issues/17
		 */
		private DBCursor processFullCollection() throws InterruptedException {
			// CommandResult lockResult = mongo.fsyncAndLock();
			// if (lockResult.ok()) {
			try {
				BSONTimestamp currentTimestamp = (BSONTimestamp) oplogCollection
						.find().sort(new BasicDBObject(OPLOG_TIMESTAMP, -1))
						.limit(1).next().get(OPLOG_TIMESTAMP);
				addQueryToStream(OPLOG_INSERT_OPERATION, currentTimestamp, null);
				return oplogCursor(currentTimestamp);
			} finally {
				// mongo.unlock();
			}
			// } else {
			// throw new MongoException(
			// "Could not lock the database for FullCollection sync");
			// }
		}

		@SuppressWarnings("unchecked")
		private void processOplogEntry(final DBObject entry)
				throws InterruptedException {
			String operation = entry.get(OPLOG_OPERATION).toString();
			String namespace = entry.get(OPLOG_NAMESPACE).toString();
			BSONTimestamp oplogTimestamp = (BSONTimestamp) entry
					.get(OPLOG_TIMESTAMP);
			DBObject object = (DBObject) entry.get(OPLOG_OBJECT);

			if (logger.isTraceEnabled()) {
				logger.trace("MongoDB object deserialized: {}",
						object.toString());
			}

			// Initial support for sharded collection -
			// https://jira.mongodb.org/browse/SERVER-4333
			// Not interested in operation from migration or sharding
			if (entry.containsField("fromMigrate")
					&& ((BasicBSONObject) entry).getBoolean("fromMigrate")) {
				logger.debug(
						"From migration or sharding operation. Can be ignored. {}",
						entry);
				return;
			}
			// Not interested by chunks - skip all
			if (namespace.endsWith(GRIDFS_CHUNKS_SUFFIX)) {
				return;
			}

			if (logger.isTraceEnabled()) {
				logger.trace("oplog entry - namespace [{}], operation [{}]",
						namespace, operation);
				logger.trace("oplog processing item {}", entry);
			}

			String objectId = getObjectIdFromOplogEntry(entry);
			if (definition.isMongoGridFS()
					&& namespace.endsWith(GRIDFS_FILES_SUFFIX)
					&& (OPLOG_INSERT_OPERATION.equals(operation) || OPLOG_UPDATE_OPERATION
							.equals(operation))) {
				if (objectId == null) {
					throw new NullPointerException(MONGODB_ID_FIELD);
				}
				GridFS grid = new GridFS(mongo.getDB(definition.getMongoDb()), definition.getMongoCollection());
				GridFSDBFile file = grid.findOne(new ObjectId(objectId));
				if (file != null) {
					logger.info("Caught file: {} - {}", file.getId(),
							file.getFilename());
					object = file;
				} else {
					logger.warn("Cannot find file from id: {}", objectId);
				}
			}

			if (object instanceof GridFSDBFile) {
				if (objectId == null) {
					throw new NullPointerException(MONGODB_ID_FIELD);
				}
				logger.info("Add attachment: {}", objectId);
				object = MongoDBHelper
						.applyExcludeFields(object, definition.getExcludeFields());
				HashMap<String, Object> data = new HashMap<String, Object>();
				data.put(IS_MONGODB_ATTACHMENT, true);
				data.put(MONGODB_ATTACHMENT, object);
				data.put(MONGODB_ID_FIELD, objectId);
				addToStream(operation, oplogTimestamp, data);
			} else {
				if (OPLOG_UPDATE_OPERATION.equals(operation)) {
					DBObject update = (DBObject) entry.get(OPLOG_UPDATE);
					logger.debug("Updated item: {}", update);
					addQueryToStream(operation, oplogTimestamp, update);
				} else {
					object = MongoDBHelper.applyExcludeFields(object,
							definition.getExcludeFields());
					addToStream(operation, oplogTimestamp, object.toMap());
				}
			}
		}

		/*
		 * Extract "_id" from "o" if it fails try to extract from "o2"
		 */
		private String getObjectIdFromOplogEntry(DBObject entry) {
			if (entry.containsField(OPLOG_OBJECT)) {
				DBObject object = (DBObject) entry.get(OPLOG_OBJECT);
				if (object.containsField(MONGODB_ID_FIELD)) {
					return object.get(MONGODB_ID_FIELD).toString();
				}
			}
			if (entry.containsField(OPLOG_UPDATE)) {
				DBObject object = (DBObject) entry.get(OPLOG_UPDATE);
				if (object.containsField(MONGODB_ID_FIELD)) {
					return object.get(MONGODB_ID_FIELD).toString();
				}
			}
			logger.trace("Oplog entry {}", entry);
			return null;
		}

		private DBObject getIndexFilter(final BSONTimestamp timestampOverride) {
			BSONTimestamp time = timestampOverride == null ? getLastTimestamp(mongoOplogNamespace)
					: timestampOverride;
			BasicDBObject filter = new BasicDBObject();
			List<DBObject> values = new ArrayList<DBObject>();
			List<DBObject> values2 = new ArrayList<DBObject>();

			if (definition.isMongoGridFS()) {
				values.add(new BasicDBObject(OPLOG_NAMESPACE,
						mongoOplogNamespace + GRIDFS_FILES_SUFFIX));
			} else {
				// values.add(new BasicDBObject(OPLOG_NAMESPACE,
				// mongoOplogNamespace));
				values2.add(new BasicDBObject(OPLOG_NAMESPACE,
						mongoOplogNamespace));
				values2.add(new BasicDBObject(OPLOG_NAMESPACE, definition.getMongoDb() + "."
						+ OPLOG_NAMESPACE_COMMAND));
				values.add(new BasicDBObject(MONGODB_OR_OPERATOR, values2));
			}
			if (!definition.getMongoFilter().isEmpty()) {
				values.add(getMongoFilter());
			}
			if (time == null) {
				logger.info("No known previous slurping time for this collection");
			} else {
				values.add(new BasicDBObject(OPLOG_TIMESTAMP,
						new BasicDBObject(QueryOperators.GT, time)));
			}
			filter = new BasicDBObject(MONGODB_AND_OPERATOR, values);
			if (logger.isDebugEnabled()) {
				logger.debug("Using filter: {}", filter);
			}
			return filter;
		}

		private DBObject getMongoFilter() {
			List<DBObject> filters = new ArrayList<DBObject>();
			List<DBObject> filters2 = new ArrayList<DBObject>();
			List<DBObject> filters3 = new ArrayList<DBObject>();
			// include delete operation
			filters.add(new BasicDBObject(OPLOG_OPERATION,
					OPLOG_DELETE_OPERATION));

			// include update, insert in filters3
			filters3.add(new BasicDBObject(OPLOG_OPERATION,
					OPLOG_UPDATE_OPERATION));
			filters3.add(new BasicDBObject(OPLOG_OPERATION,
					OPLOG_INSERT_OPERATION));

			// include or operation statement in filter2
			filters2.add(new BasicDBObject(MONGODB_OR_OPERATOR, filters3));

			// include custom filter in filters2
			filters2.add((DBObject) JSON.parse(definition.getMongoFilter()));

			filters.add(new BasicDBObject(MONGODB_AND_OPERATOR, filters2));

			return new BasicDBObject(MONGODB_OR_OPERATOR, filters);
		}

		private DBCursor oplogCursor(final BSONTimestamp timestampOverride) {
			DBObject indexFilter = getIndexFilter(timestampOverride);
			if (indexFilter == null) {
				return null;
			}
			return oplogCollection.find(indexFilter)
					.sort(new BasicDBObject(MONGODB_NATURAL_OPERATOR, 1))
					.addOption(Bytes.QUERYOPTION_TAILABLE)
					.addOption(Bytes.QUERYOPTION_AWAITDATA);
		}

		@SuppressWarnings("unchecked")
		private void addQueryToStream(final String operation,
				final BSONTimestamp currentTimestamp, final DBObject update)
				throws InterruptedException {
			if (logger.isDebugEnabled()) {
				logger.debug(
						"addQueryToStream - operation [{}], currentTimestamp [{}], update [{}]",
						operation, currentTimestamp, update);
			}

			for (DBObject item : slurpedCollection.find(update, findKeys)) {
				addToStream(operation, currentTimestamp, item.toMap());
			}
		}

		private void addToStream(final String operation,
				final BSONTimestamp currentTimestamp,
				final Map<String, Object> data) throws InterruptedException {
			if (logger.isDebugEnabled()) {
				logger.debug(
						"addToStream - operation [{}], currentTimestamp [{}], data [{}]",
						operation, currentTimestamp, data);
			}
			data.put(OPLOG_TIMESTAMP, currentTimestamp);
			data.put(OPLOG_OPERATION, operation);

			// stream.add(data);
			stream.put(data);
			// try {
			// stream.put(data);
			// } catch (InterruptedException e) {
			// e.printStackTrace();
			// }
		}

	}

	private class Status implements Runnable {

		@Override
		public void run() {
			while (true) {
				try {
					if (startInvoked) {
						// logger.trace("*** river status thread waiting: {} ***",
						// riverName.getName());

						boolean enabled = MongoDBRiverHelper.isRiverEnabled(
								client, riverName.getName());

						if (active && !enabled) {
							logger.info("About to stop river: {}",
									riverName.getName());
							close();
						}

						if (!active && enabled) {
							logger.trace("About to start river: {}",
									riverName.getName());
							// active = true;
							start();
						}
					}
					Thread.sleep(1000L);
				} catch (InterruptedException e) {
					logger.info("Status thread interrupted", e, (Object) null);
					Thread.currentThread().interrupt();
					break;
				}

			}
		}
	}

	private XContentBuilder getGridFSMapping() throws IOException {
		XContentBuilder mapping = jsonBuilder().startObject()
				.startObject(definition.getTypeName()).startObject("properties")
				.startObject("content").field("type", "attachment").endObject()
				.startObject("filename").field("type", "string").endObject()
				.startObject("contentType").field("type", "string").endObject()
				.startObject("md5").field("type", "string").endObject()
				.startObject("length").field("type", "long").endObject()
				.startObject("chunkSize").field("type", "long").endObject()
				.endObject().endObject().endObject();
		logger.info("Mapping: {}", mapping.string());
		return mapping;
	}

	/**
	 * Get the latest timestamp for a given namespace.
	 */
	@SuppressWarnings("unchecked")
	private BSONTimestamp getLastTimestamp(final String namespace) {
		GetResponse lastTimestampResponse = client
				.prepareGet(riverIndexName, riverName.getName(), namespace)
				.execute().actionGet();
		// API changes since 0.90.0 lastTimestampResponse.exists() replaced by
		// lastTimestampResponse.isExists()
		if (lastTimestampResponse.isExists()) {
			// API changes since 0.90.0 lastTimestampResponse.sourceAsMap()
			// replaced by lastTimestampResponse.getSourceAsMap()
			Map<String, Object> mongodbState = (Map<String, Object>) lastTimestampResponse
					.getSourceAsMap().get(TYPE);
			if (mongodbState != null) {
				String lastTimestamp = mongodbState.get(LAST_TIMESTAMP_FIELD)
						.toString();
				if (lastTimestamp != null) {
					if (logger.isDebugEnabled()) {
						logger.debug("{} last timestamp: {}", namespace,
								lastTimestamp);
					}
					return (BSONTimestamp) JSON.parse(lastTimestamp);

				}
			}
		} else {
			if (definition.getInitialTimestamp() != null) {
				return definition.getInitialTimestamp();
			}
		}
		return null;
	}

	/**
	 * Adds an index request operation to a bulk request, updating the last
	 * timestamp for a given namespace (ie: host:dbName.collectionName)
	 * 
	 * @param bulk
	 */
	private void updateLastTimestamp(final String namespace,
			final BSONTimestamp time, final BulkRequestBuilder bulk) {
		try {
			bulk.add(indexRequest(riverIndexName)
					.type(riverName.getName())
					.id(namespace)
					.source(jsonBuilder().startObject().startObject(TYPE)
							.field(LAST_TIMESTAMP_FIELD, JSON.serialize(time))
							.endObject().endObject()));
		} catch (IOException e) {
			logger.error("error updating last timestamp for namespace {}",
					namespace);
		}
	}

}
