package com.nivas.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the real {@code application.yml} without starting the application.
 *
 * <p>Configuration binding failures are startup failures, and startup needs
 * Postgres, Redis, Elasticsearch and Kafka — so on a machine without Docker a
 * typo'd property key would go unnoticed until deploy. This loads the actual
 * config file against the actual property classes and asserts it resolves.
 *
 * <p>It exists because of a concrete bug: Jackson 3 moved
 * {@code WRITE_DATES_AS_TIMESTAMPS} from {@code SerializationFeature} to
 * {@code DateTimeFeature}. The Jackson 2 spelling still looks entirely
 * reasonable in YAML and fails only when the context refreshes.
 */
class ApplicationConfigBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Loads src/main/resources/application.yml exactly as the app would.
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NivasProperties.class)
    static class PropertiesConfiguration {
    }

    @Test
    @DisplayName("application.yml binds cleanly, including every spring.jackson key")
    void applicationYamlBinds() {
        runner.run(context -> assertThat(context)
                .as("a failure here is almost always a property key that moved between versions")
                .hasNotFailed());
    }

    @Test
    @DisplayName("the nivas.* block binds to validated, sensible values")
    void nivasPropertiesBind() {
        runner.run(context -> {
            NivasProperties properties = context.getBean(NivasProperties.class);

            assertThat(properties.jwt().secret()).hasSizeGreaterThanOrEqualTo(32);
            assertThat(properties.jwt().issuer()).isEqualTo("nivas");
            assertThat(properties.jwt().accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(properties.jwt().refreshTokenTtl()).isEqualTo(Duration.ofDays(30));

            // An access token outliving its refresh token would be incoherent.
            assertThat(properties.jwt().accessTokenTtl())
                    .isLessThan(properties.jwt().refreshTokenTtl());

            assertThat(properties.cache().propertyDetailTtl()).isPositive();
            assertThat(properties.cache().searchResultsTtl()).isPositive();
            assertThat(properties.cache().localityTtl()).isPositive();

            assertThat(properties.rateLimit().search().permits()).isPositive();
            assertThat(properties.rateLimit().lead().permits()).isPositive();

            assertThat(properties.search().defaultPageSize())
                    .isPositive()
                    .isLessThanOrEqualTo(properties.search().maxPageSize());
        });
    }

    @Test
    @DisplayName("an invalid nivas.* value fails fast rather than binding to null")
    void invalidValuesAreRejected() {
        runner.withPropertyValues("nivas.jwt.secret=too-short")
                .run(context -> assertThat(context)
                        .as("a short HS256 key must be rejected at startup, not at first login")
                        .hasFailed());
    }
}
