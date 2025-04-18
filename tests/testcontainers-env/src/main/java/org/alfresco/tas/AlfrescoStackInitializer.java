package org.alfresco.tas;

import static org.alfresco.tas.SystemPropertyHelper.getSystemProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.github.dockerjava.api.command.CreateContainerCmd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.IndefiniteWaitOneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import org.testng.Assert;
import org.testng.util.Strings;

import org.alfresco.utility.report.log.Step;

public class AlfrescoStackInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext>
{
    private static Logger LOGGER = LoggerFactory.getLogger(AlfrescoStackInitializer.class);
    private static Slf4jLogConsumer LOG_CONSUMER = new Slf4jLogConsumer(LOGGER);

    public static final String CUSTOM_ALFRESCO_INDEX = "custom-alfresco-index";

    public static Network network;

    public static GenericContainer alfresco;

    public static GenericContainer searchEngineContainer;

    /** To create the kibana container for a test run then pass -Dkibana=true as an argument to the mvn command. */
    public static GenericContainer dashboardsContainer;

    public static GenericContainer liveIndexer;

    @Override
    public void initialize(ConfigurableApplicationContext configurableApplicationContext)
    {

        // Wait till existing containers are stopped
        if (alfresco != null)
        {
            if (alfresco.getDockerClient().listContainersCmd().withShowAll(true).exec().size() > 0)
            {
                try
                {
                    LOGGER.info("Waiting for living containers to be stopped...");
                    Thread.sleep(10000);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        }

        network = Network.newNetwork();

        alfresco = createAlfrescoContainer();

        JdbcDatabaseContainer database = createDatabaseContainer();

        GenericContainer transformRouter = createTransformRouterContainer();

        GenericContainer transformCore = createTransformCoreContainer();

        GenericContainer sfs = createSfsContainer();

        GenericContainer activemq = createAMQContainer();

        searchEngineContainer = createSearchEngineContainer();

        startOrFail(searchEngineContainer);

        configureSecuritySettings(searchEngineContainer);

        startOrFail(database);

        startOrFail(activemq, sfs);

        startOrFail(transformCore, transformRouter);

        // We don't want Kibana to run on our CI, but it can be useful when investigating issues locally.
        if (getSystemProperty("kibana", "false").equals("true"))
        {
            dashboardsContainer = createDashboardsContainer();
            startOrFail(dashboardsContainer);
        }

        liveIndexer = createLiveIndexingContainer();

        startOrFail(liveIndexer);

        startOrFail(alfresco);

        alfresco.followOutput(LOG_CONSUMER);

        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(configurableApplicationContext,
                "alfresco.server=" + alfresco.getContainerIpAddress(),
                "alfresco.port=" + alfresco.getFirstMappedPort());

    }

    private JdbcDatabaseContainer createDatabaseContainer()
    {
        switch (getImagesConfig().getDatabaseType())
        {
        case POSTGRESQL_DB:
            return createPosgresContainer();
        case MYSQL_DB:
            return createMySqlContainer();
        case MARIA_DB:
            return createMariaDBContainer();
        case MSSQL_DB:
            return createMsSqlContainer();
        case ORACLE_DB:
            return createOracleDBContainer();
        default:
            throw new IllegalArgumentException("Database not set.");
        }
    }

    public void configureSecuritySettings(GenericContainer searchEngineContainer)
    {
        // empty for default execution
    }

    /**
     * Run the alfresco-elasticsearch-reindexing container with path reindexing enabled.
     */
    public static void reindexEverything()
    {
        reindex(Map.of("ALFRESCO_REINDEX_PATHINDEXINGENABLED", "true", // Ensure path reindexing is enabled.
                "ALFRESCO_REINDEX_JOB_NAME", "reindexByDate"));
    }

    /**
     * Run the alfresco-elasticsearch-reindexing container.
     *
     * @param envParam
     *            Any environment variables to override from the defaults.
     */
    public static void reindex(Map<String, String> envParam)
    {
        // Run the reindexing container.
        Map<String, String> env = AlfrescoStackInitializer.getReindexEnvBasic();
        env.putAll(envParam);

        try (GenericContainer reindexingComponent = new GenericContainer(getImagesConfig().getReIndexingImage())
                .withEnv(env)
                .withNetwork(AlfrescoStackInitializer.network)
                .withStartupCheckStrategy(
                        new IndefiniteWaitOneShotStartupCheckStrategy()))
        {
            reindexingComponent.start();
        }
    }

    public static Map<String, String> getReindexEnvBasic()
    {
        DatabaseType databaseType = getImagesConfig().getDatabaseType();

        Map<String, String> env = new HashMap<>(
                Map.of("SPRING_ELASTICSEARCH_REST_URIS", "http://elasticsearch:9200",
                        "SPRING_DATASOURCE_URL", databaseType.getUrl(),
                        "SPRING_DATASOURCE_USERNAME", databaseType.getUsername(),
                        "SPRING_DATASOURCE_PASSWORD", databaseType.getPassword(),
                        "ELASTICSEARCH_INDEX_NAME", CUSTOM_ALFRESCO_INDEX,
                        "SPRING_ACTIVEMQ_BROKER-URL", "nio://activemq:61616",
                        "JAVA_TOOL_OPTIONS", "-Xmx1g",
                        "ALFRESCO_ACCEPTEDCONTENTMEDIATYPESCACHE_BASEURL", "http://transform-core-aio:8090/transform/config"));
        return env;
    }

    private void startOrFail(Startable... startables)
    {
        try
        {
            Startables.deepStart(startables).get();
        }
        catch (Exception e)
        {
            Assert.fail("Unable to start containers", e);
        }

    }

    protected GenericContainer<?> createLiveIndexingContainer()
    {
        return new GenericContainer<>(getImagesConfig().getLiveIndexingImage())
                .withNetwork(network)
                .withNetworkAliases("live-indexing")
                .withEnv("ELASTICSEARCH_INDEXNAME", CUSTOM_ALFRESCO_INDEX)
                .withEnv("SPRING_ELASTICSEARCH_REST_URIS", "http://elasticsearch:9200")
                .withEnv("SPRING_ACTIVEMQ_BROKERURL", "nio://activemq:61616")
                .withEnv("ALFRESCO_SHAREDFILESTORE_BASEURL", "http://shared-file-store:8099/alfresco/api/-default-/private/sfs/versions/1/file/")
                .withEnv("ALFRESCO_ACCEPTEDCONTENTMEDIATYPESCACHE_BASEURL", "http://transform-core-aio:8090/transform/config")
                .withEnv("JAVA_TOOL_OPTIONS", "-Xmx2g -agentlib:jdwp=transport=dt_socket,address=*:5005,server=y,suspend=n")
                .withExposedPorts(5005);
    }

    protected GenericContainer createSearchEngineContainer()
    {
        return getImagesConfig().getSearchEngineType() == SearchEngineType.OPENSEARCH_ENGINE ? createOpensearchContainer() : createElasticContainer();
    }

    protected GenericContainer createDashboardsContainer()
    {
        return getImagesConfig().getSearchEngineType() == SearchEngineType.OPENSEARCH_ENGINE ? createOpensearchDashboardsContainer() : createKibanaContainer();
    }

    protected GenericContainer createElasticContainer()
    {
        return new GenericContainer<>(getImagesConfig().getElasticsearchImage())
                .withNetwork(network)
                .withNetworkAliases("elasticsearch")
                .withExposedPorts(9200)
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                .withEnv("ES_JAVA_OPTS", "-Xms2g -Xmx2g");
    }

    protected GenericContainer createOpensearchContainer()
    {
        return new GenericContainer<>(getImagesConfig().getOpensearchImage())
                .withNetwork(network)
                .withNetworkAliases("elasticsearch")
                .withExposedPorts(9200)
                .withEnv("plugins.security.disabled", "true")
                .withEnv("discovery.type", "single-node")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms2g -Xmx2g");
    }

    protected GenericContainer createOpensearchDashboardsContainer()
    {
        return new GenericContainer(getImagesConfig().getOpensearchDashboardsImage())
                .withNetwork(network)
                .withNetworkAliases("kibana")
                .withExposedPorts(5601)
                .withEnv("ELASTICSEARCH_HOSTS", "http://elasticsearch:9200");
    }

    protected GenericContainer createKibanaContainer()
    {
        return new GenericContainer(getImagesConfig().getKibanaImage())
                .withNetwork(network)
                .withNetworkAliases("kibana")
                .withExposedPorts(5601)
                .withEnv("ELASTICSEARCH_HOSTS", "http://elasticsearch:9200");
    }

    private GenericContainer createAMQContainer()
    {
        return new GenericContainer(getImagesConfig().getActiveMqImage())
                .withNetwork(network)
                .withNetworkAliases("activemq")
                .withEnv("JAVA_OPTS", "-Xms512m -Xmx1g")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2))
                .withExposedPorts(61616, 8161, 5672, 61613);
    }

    private PostgreSQLContainer createPosgresContainer()
    {
        return (PostgreSQLContainer) new PostgreSQLContainer(getImagesConfig().getPostgreSQLImage())
                .withPassword(DatabaseType.POSTGRESQL_DB.getPassword())
                .withUsername(DatabaseType.POSTGRESQL_DB.getUsername())
                .withDatabaseName("alfresco")
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    private MySQLContainer createMySqlContainer()
    {
        return (MySQLContainer) new MySQLContainer(getImagesConfig().getMySQLImage())
                .withPassword(DatabaseType.MYSQL_DB.getPassword())
                .withUsername(DatabaseType.MYSQL_DB.getUsername())
                .withDatabaseName("alfresco")
                .withNetwork(network)
                .withNetworkAliases("mysql")
                .withStartupTimeout(Duration.ofMinutes(2));

    }

    private MariaDBContainer createMariaDBContainer()
    {
        return new MariaDBContainer<>(getImagesConfig().getMariaDBImage())
                .withPassword(DatabaseType.MARIA_DB.getPassword())
                .withUsername(DatabaseType.MARIA_DB.getUsername())
                .withDatabaseName("alfresco")
                .withNetwork(network)
                .withNetworkAliases("mariadb")
                .withStartupTimeout(Duration.ofMinutes(2));

    }

    private JdbcDatabaseContainer createMsSqlContainer()
    {
        return new MSSQLServerContainer<>(getImagesConfig().getMsSqlImage())
                .acceptLicense()
                .withPassword(DatabaseType.MSSQL_DB.getPassword())
                .withNetwork(network)
                .withNetworkAliases("mssql")
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    private OracleContainer createOracleDBContainer()
    {
        DockerImageName oracleImageName = DockerImageName.parse(getImagesConfig().getOracleImage());

        return new OracleContainer(oracleImageName)
                .withNetwork(network)
                .withNetworkAliases("oracle");
    }

    private GenericContainer createSfsContainer()
    {
        return new GenericContainer(getImagesConfig().getSharedFileStoreImage())
                .withNetwork(network)
                .withNetworkAliases("shared-file-store")
                .withEnv("JAVA_OPTS", "-Xms256m -Xmx512m")
                .withEnv("scheduler.content.age.millis", "86400000")
                .withEnv("scheduler.cleanup.interval", "86400000")
                .withExposedPorts(8099)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    private GenericContainer createTransformCoreContainer()
    {
        return new GenericContainer(getImagesConfig().getTransformCoreAIOImage())
                .withNetwork(network)
                .withNetworkAliases("transform-core-aio")
                .withEnv("JAVA_OPTS", "-Xms512m -Xmx1024m")
                .withEnv("ACTIVEMQ_URL", "nio://activemq:61616")
                .withEnv("FILE_STORE_URL", "http://shared-file-store:8099/alfresco/api/-default-/private/sfs/versions/1/file")
                .withExposedPorts(8090)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    private GenericContainer createTransformRouterContainer()
    {
        return new GenericContainer(getImagesConfig().getTransformRouterImage())
                .withNetwork(network)
                .withNetworkAliases("transform-router")
                .withEnv("JAVA_OPTS", "-Xms256m -Xmx512m")
                .withEnv("ACTIVEMQ_URL", "nio://activemq:61616")
                .withEnv("CORE_AIO_URL", "http://transform-core-aio:8090")
                .withEnv("FILE_STORE_URL", "http://shared-file-store:8099/alfresco/api/-default-/private/sfs/versions/1/file")
                .withExposedPorts(8095)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    protected GenericContainer createAlfrescoContainer()
    {
        DatabaseType databaseType = getImagesConfig().getDatabaseType();
        return new GenericContainer(getImagesConfig().getRepositoryImage())
                .withEnv("CATALINA_OPTS", "\"-agentlib:jdwp=transport=dt_socket,address=*:8000,server=y,suspend=n\"")
                .withEnv("JAVA_TOOL_OPTIONS",
                        "-Dencryption.keystore.type=JCEKS " +
                                "-Dencryption.cipherAlgorithm=DESede/CBC/PKCS5Padding " +
                                "-Dencryption.keyAlgorithm=DESede " +
                                "-Dencryption.keystore.location=/usr/local/tomcat/shared/classes/alfresco/extension/keystore/keystore " +
                                "-Dmetadata-keystore.password=mp6yc0UD9e -Dmetadata-keystore.aliases=metadata " +
                                "-Dmetadata-keystore.metadata.password=oKIWzVdEdA -Dmetadata-keystore.metadata.algorithm=DESede")
                .withEnv("JAVA_OPTS",
                        "-Delasticsearch.createIndexIfNotExists=true " +
                                "-Dindex.subsystem.name=elasticsearch " +
                                "-Delasticsearch.host=elasticsearch " +
                                "-Delasticsearch.indexName=" + CUSTOM_ALFRESCO_INDEX + " " +
                                "-Ddb.driver=" + databaseType.getDriver() + " " +
                                "-Ddb.url=" + escapeSemicolonInUrlForJavaOptsUsage(databaseType.getUrl()) + " " +
                                "-Ddb.username=" + databaseType.getUsername() + " " +
                                "-Ddb.password=" + databaseType.getPassword() + " " +
                                indentDbSettings(databaseType.getAdditionalDbSettings()) +
                                "-Dshare.host=127.0.0.1 " +
                                "-Dshare.port=8080 " +
                                "-Dalfresco.host=localhost " +
                                "-Dalfresco.port=8080 " +
                                "-Daos.baseUrlOverwrite=http://localhost:8080/alfresco/aos " +
                                "-Dmessaging.broker.url=\"failover:(nio://activemq:61616)?timeout=3000&jms.useCompression=true\" " +
                                "-Ddeployment.method=DOCKER_COMPOSE " +
                                "-Dtransform.service.enabled=true " +
                                "-Dtransform.service.url=http://transform-router:8095 " +
                                "-Dsfs.url=http://shared-file-store:8099 " +
                                "-DlocalTransform.core-aio.url=http://transform-core-aio:8090/ " +
                                "-Dcsrf.filter.enabled=false " +
                                "-Dalfresco.restApi.basicAuthScheme=true " +
                                "-Dquery.cmis.queryConsistency=EVENTUAL " +
                                "-Dquery.fts.queryConsistency=EVENTUAL " +
                                "-Xms1g -Xmx2g ")
                .withNetwork(network)
                .withNetworkAliases("alfresco")
                .waitingFor(new LogMessageWaitStrategy().withRegEx(".*Server startup in.*\\n"))
                .withStartupTimeout(Duration.ofMinutes(7))
                .withExposedPorts(8080, 8000)
                .withClasspathResourceMapping("exactTermSearch.properties",
                        "/usr/local/tomcat/webapps/alfresco/WEB-INF/classes/alfresco/search/elasticsearch/config/exactTermSearch.properties",
                        BindMode.READ_ONLY);
    }

    private String escapeSemicolonInUrlForJavaOptsUsage(String url)
    {
        return url.replace(";", "\\;");
    }

    private String indentDbSettings(Map<String, String> additionalDbSettings)
    {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> setting : additionalDbSettings.entrySet())
        {
            sb.append("-Ddb.").append(setting.getKey()).append("=").append(setting.getValue()).append(" ");
        }
        return sb.toString();
    }

    public static ImagesConfig getImagesConfig()
    {
        return DefaultImagesConfig.INSTANCE;
    }

    public interface ImagesConfig
    {
        String getReIndexingImage();

        String getLiveIndexingImage();

        String getElasticsearchImage();

        String getOpensearchImage();

        String getOpensearchDashboardsImage();

        String getActiveMqImage();

        String getTransformRouterImage();

        String getTransformCoreAIOImage();

        String getSharedFileStoreImage();

        String getPostgreSQLImage();

        String getMySQLImage();

        String getMariaDBImage();

        String getMsSqlImage();

        String getOracleImage();

        DatabaseType getDatabaseType();

        String getRepositoryImage();

        String getKibanaImage();

        SearchEngineType getSearchEngineType();
    }

    private static final class DefaultImagesConfig implements ImagesConfig
    {
        private static final DefaultImagesConfig INSTANCE = new DefaultImagesConfig(EnvHelper::getEnvProperty, MavenPropertyHelper::getMavenProperty);
        private final Function<String, String> envProperties;
        private final Function<String, String> mavenProperties;

        DefaultImagesConfig(Function<String, String> envProperties, Function<String, String> mavenProperties)
        {
            this.envProperties = envProperties;
            this.mavenProperties = mavenProperties;
        }

        @Override
        public String getReIndexingImage()
        {
            return getSystemProperty("reindeximage", "quay.io/alfresco/alfresco-elasticsearch-reindexing:latest");
        }

        @Override
        public String getLiveIndexingImage()
        {
            return getSystemProperty("indeximage", "quay.io/alfresco/alfresco-elasticsearch-live-indexing:latest");
        }

        @Override
        public String getElasticsearchImage()
        {
            return "docker.elastic.co/elasticsearch/elasticsearch:" + envProperties.apply("ELASTICSEARCH_TAG");
        }

        @Override
        public String getOpensearchImage()
        {
            return "opensearchproject/opensearch:" + envProperties.apply("OPENSEARCH_TAG");
        }

        @Override
        public String getOpensearchDashboardsImage()
        {
            return "opensearchproject/opensearch-dashboards:" + envProperties.apply("OPENSEARCH_DASHBOARDS_TAG");
        }

        @Override
        public String getActiveMqImage()
        {
            return "alfresco/alfresco-activemq:" + envProperties.apply("ACTIVEMQ_TAG");
        }

        @Override
        public String getTransformRouterImage()
        {
            return "quay.io/alfresco/alfresco-transform-router:" + mavenProperties.apply("dependency.alfresco-transform-service.version");
        }

        @Override
        public String getTransformCoreAIOImage()
        {
            return "alfresco/alfresco-transform-core-aio:" + mavenProperties.apply("dependency.alfresco-transform-core.version");
        }

        @Override
        public String getSharedFileStoreImage()
        {
            return "quay.io/alfresco/alfresco-shared-file-store:" + mavenProperties.apply("dependency.alfresco-transform-service.version");
        }

        @Override
        public String getPostgreSQLImage()
        {
            return "postgres:" + envProperties.apply("POSTGRES_TAG");
        }

        @Override
        public String getMySQLImage()
        {
            return "mysql:" + envProperties.apply("MYSQL_TAG");
        }

        @Override
        public String getMariaDBImage()
        {
            return "mariadb:" + envProperties.apply("MARIADB_TAG");
        }

        @Override
        public String getMsSqlImage()
        {
            return "mcr.microsoft.com/mssql/server:" + envProperties.apply("MSSQL_TAG");
        }

        @Override
        public String getOracleImage()
        {
            return "quay.io/alfresco/oracle-database:" + envProperties.apply("ORACLE_TAG");
        }

        @Override
        public String getRepositoryImage()
        {
            return getSystemProperty("repoimage", "alfresco/alfresco-content-repository:latest");
        }

        @Override
        public String getKibanaImage()
        {
            return "kibana:" + envProperties.apply("KIBANA_TAG");
        }

        @Override
        public SearchEngineType getSearchEngineType()
        {
            String searchEngineTypeProperty = mavenProperties.apply("search.engine.type");
            if (Strings.isNullOrEmpty(searchEngineTypeProperty))
            {
                Step.STEP("Defaulting search engine to Elasticsearch.");
                return SearchEngineType.ELASTICSEARCH_ENGINE;
            }
            return SearchEngineType.from(searchEngineTypeProperty);
        }

        @Override
        public DatabaseType getDatabaseType()
        {
            String databaseTypeProperty = mavenProperties.apply("database.type");
            if (Strings.isNullOrEmpty(databaseTypeProperty))
            {
                Step.STEP("Defaulting database to postgresql.");
                return DatabaseType.POSTGRESQL_DB;
            }
            return DatabaseType.from(databaseTypeProperty);
        }
    }

    private static class OracleContainer<SELF extends OracleContainer<SELF>> extends JdbcDatabaseContainer<SELF>
    {

        OracleContainer(DockerImageName dockerImageName)
        {
            super(dockerImageName);
            addExposedPort(1521);
            waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
        }

        @Override
        protected void configure()
        {
            addEnv("ORACLE_SID", "ORCL");
            addEnv("ORACLE_PDB", "PDB1");
            addEnv("ORACLE_PWD", DatabaseType.ORACLE_DB.getPassword());
            addEnv("ORACLE_CHARACTERSET", "UTF8");
        }

        @Override
        public SELF withNetwork(Network network)
        {
            return super.withNetwork(network);
        }

        @Override
        public SELF withNetworkAliases(String... aliases)
        {
            return super.withNetworkAliases(aliases);
        }

        @Override
        public SELF withCreateContainerCmdModifier(Consumer<CreateContainerCmd> modifier)
        {
            return super.withCreateContainerCmdModifier(modifier);
        }

        @Override
        public String getDriverClassName()
        {
            return DatabaseType.ORACLE_DB.getDriver();
        }

        @Override
        public String getJdbcUrl()
        {
            final String additionalUrlParams = constructUrlParameters("?", "&");
            return DatabaseType.ORACLE_DB.getUrl() + additionalUrlParams;
        }

        @Override
        public String getUsername()
        {
            return DatabaseType.ORACLE_DB.getUsername();
        }

        @Override
        public String getPassword()
        {
            return DatabaseType.ORACLE_DB.getPassword();
        }

        @Override
        protected void waitUntilContainerStarted()
        {
            try
            {
                ExecResult r = execInContainer("/bin/sh", "-c", "echo DISABLE_OOB=ON >> /opt/oracle/product/19c/dbhome_1/network/admin/sqlnet.ora");
                if (r.getExitCode() != 0)
                {
                    throw new IllegalStateException("Failed to disable OOB. " + r);
                }
            }
            catch (Exception e)
            {
                throw new IllegalStateException("Failed to disable OOB.", e);
            }
            getWaitStrategy().waitUntilReady(this);
        }

        @Override
        protected String getTestQueryString()
        {
            throw new UnsupportedOperationException("Test query is not supported for Oracle image.");
        }
    }
}
