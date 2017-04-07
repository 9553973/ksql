/**
 * Copyright 2017 Confluent Inc.
 **/
package io.confluent.kql.rest;

import io.confluent.kql.KQLEngine;
import io.confluent.kql.metastore.MetaStore;
import io.confluent.kql.metastore.MetaStoreImpl;
import io.confluent.kql.rest.computation.QueryComputer;
import io.confluent.kql.rest.computation.QueryHandler;
import io.confluent.kql.rest.computation.StatementStatus;
import io.confluent.kql.rest.resources.KQLResource;
import io.confluent.kql.rest.resources.StatusResource;
import io.confluent.kql.rest.resources.StreamedQueryResource;
import io.confluent.kql.util.KQLConfig;
import io.confluent.kql.util.QueryMetadata;
import io.confluent.rest.Application;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.glassfish.jersey.server.ServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Configurable;
import javax.ws.rs.core.MediaType;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class KQLApplication extends Application<KQLRestConfig> {

  private static final Logger log = LoggerFactory.getLogger(KQLApplication.class);

  private final QueryComputer queryComputer;
  private final StatusResource statusResource;
  private final StreamedQueryResource streamedQueryResource;
  private final KQLResource kqlResource;
  private final QuickstartResource quickstartResource;

  public KQLApplication(
      KQLRestConfig config,
      QueryComputer queryComputer,
      StatusResource statusResource,
      StreamedQueryResource streamedQueryResource,
      KQLResource kqlResource,
      QuickstartResource quickstartResource
  ) {
    super(config);
    this.queryComputer = queryComputer;
    this.statusResource = statusResource;
    this.streamedQueryResource = streamedQueryResource;
    this.kqlResource = kqlResource;
    this.quickstartResource = quickstartResource;
  }

  @Override
  public void setupResources(Configurable<?> config, KQLRestConfig appConfig) {
    config.register(statusResource);
    config.register(kqlResource);
    config.register(streamedQueryResource);
    if (quickstartResource != null) {
      config.register(quickstartResource);
    }
  }

  private static Properties getProps(String propsFile) throws IOException {
    Properties result = new Properties();
    result.load(new FileInputStream(propsFile));
    return result;
  }

  @Override
  public void start() throws Exception {
    super.start();
    new Thread(queryComputer).start();
  }

  @Override
  public void onShutdown() {
    super.onShutdown();
    queryComputer.shutdown();
  }

  @Override
  public void configureBaseApplication(Configurable<?> config, Map<String, String> metricTags) {
    super.configureBaseApplication(config, metricTags);
    // Don't want to buffer rows when streaming JSON in a request to the query resource
    config.property(ServerProperties.OUTBOUND_CONTENT_LENGTH_BUFFER, 0);
  }

  @Path("/quickstart")
  public static class QuickstartResource {
    private final File quickstartFile;

    public QuickstartResource(File quickstartFile) {
      this.quickstartFile = quickstartFile;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public File getQuickstart() {
      return quickstartFile;
    }
  }

  public static void main(String[] args) throws Exception {
    CLIOptions cliOptions = CLIOptions.parse(args);
    if (cliOptions == null) {
      return;
    }

    Properties props = getProps(cliOptions.getPropertiesFile());
    KQLRestConfig config = new KQLRestConfig(props);
    KQLApplication app = of(config, cliOptions.getQuickstart());
    app.start();
    app.join();
    System.err.println("Server shutting down...");
  }

  public static KQLApplication of(KQLRestConfig config, String quickstartFile) throws Exception {
    Map<String, QueryMetadata> liveQueryMap = new HashMap<>();
    TopicUtil topicUtil = new TopicUtil(config.getString(KQLRestConfig.ZOOKEEPER_CONNECT_CONFIG));

    MetaStore metaStore = new MetaStoreImpl();
    Map<String, StatementStatus> statusStore = new HashMap<>();

    KQLConfig kqlConfig = new KQLConfig(config.getKqlStreamsProperties());
    KQLEngine kqlEngine = new KQLEngine(metaStore, kqlConfig);
    StatementParser statementParser = new StatementParser(kqlEngine);

    String commandTopic = config.getString(KQLRestConfig.COMMAND_TOPIC_CONFIG);
    topicUtil.ensureTopicExists(commandTopic);

    Long commandPollTimeout = config.getLong(KQLRestConfig.COMMAND_POLL_TIMEOUT_CONFIG);
    Map<String, Object> commandConsumerProperties = config.getCommandConsumerProperties();
    KafkaConsumer<String, String> commandConsumer = new KafkaConsumer<>(
        commandConsumerProperties,
        new StringDeserializer(),
        new StringDeserializer()
    );

    QueryHandler queryHandler = new QueryHandler(
        topicUtil,
        kqlEngine,
        liveQueryMap,
        statementParser,
        statusStore
    );

    String nodeId = config.getString(KQLRestConfig.NODE_ID_CONFIG);

    QueryComputer queryComputer = new QueryComputer(
        queryHandler,
        commandTopic,
        commandPollTimeout,
        commandConsumer,
        statusStore,
        statementParser,
        String.format("%s_", nodeId).toUpperCase(),
        kqlEngine
    );


    StatusResource statusResource = new StatusResource(statusStore);
    StreamedQueryResource streamedQueryResource = new StreamedQueryResource(
        kqlEngine,
        topicUtil,
        nodeId,
        statementParser,
        config.getLong(KQLRestConfig.STREAMED_QUERY_DISCONNECT_CHECK_MS_CONFIG),
        config.getKqlStreamsProperties()
    );
    KQLResource kqlResource = new KQLResource(
        liveQueryMap,
        kqlEngine,
        nodeId,
        commandTopic,
        new KafkaProducer<>(
            config.getCommandProducerProperties(),
            new StringSerializer(),
            new StringSerializer()
        ),
        statusStore,
        queryComputer.processPriorCommands()
    );

    return new KQLApplication(
        config,
        queryComputer,
        statusResource,
        streamedQueryResource,
        kqlResource,
        quickstartFile == null ? null : new QuickstartResource(new File(quickstartFile))
    );
  }
}

/*
        TODO: Find a good, forwards-compatible use for the root resource
 */
