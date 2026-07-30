package com.nivas.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The four backing services, as containers.
 *
 * <p>Every container is a singleton bean in a shared context, so Spring's test
 * context cache starts them once for the whole suite rather than once per class.
 * Booting Elasticsearch per test class would dominate the run time.
 *
 * <p>{@code @ServiceConnection} replaces the hand-written
 * {@code @DynamicPropertySource} plumbing — Boot derives the URLs, ports and
 * credentials from the running container itself, so the test configuration
 * cannot drift from what actually started.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    /** Pinned to the same major version as production; a float here makes failures unreproducible. */
    private static final String POSTGRES_IMAGE = "postgres:17-alpine";
    private static final String REDIS_IMAGE = "redis:7.4-alpine";
    private static final String ELASTICSEARCH_IMAGE =
            "docker.elastic.co/elasticsearch/elasticsearch:9.1.0";
    private static final String KAFKA_IMAGE = "apache/kafka:3.9.0";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
                .withDatabaseName("nivas")
                .withUsername("nivas")
                .withPassword("nivas")
                // Flyway runs against this on startup, exactly as in production —
                // which means the migrations themselves are under test.
                .withReuse(true);
    }

    /**
     * Redis has no dedicated Testcontainers module here, so a plain container is
     * paired with a named service connection. Boot maps {@code "redis"} onto
     * {@code spring.data.redis.*} from the container's mapped port.
     */
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(6379)
                .withReuse(true);
    }

    @Bean
    @ServiceConnection
    ElasticsearchContainer elasticsearch() {
        return new ElasticsearchContainer(DockerImageName.parse(ELASTICSEARCH_IMAGE))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node")
                // 1GB is enough for a test index and keeps CI runners from swapping.
                .withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g")
                // Otherwise a nearly-full CI disk flips indices to read-only and
                // every write fails with a cryptic cluster_block_exception.
                .withEnv("cluster.routing.allocation.disk.threshold_enabled", "false")
                .withReuse(true);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE))
                .withReuse(true);
    }
}
