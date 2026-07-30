package com.nivas.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks a test that runs against real Postgres, Redis, Elasticsearch and Kafka.
 *
 * <p>Everything about this project's correctness lives in the seams between those
 * four systems — a transactional outbox, cache invalidation, an atomic Lua rate
 * limiter, external document versioning. Mocking them would test the mocks. So
 * these are the tests that matter, and they use the real thing.
 *
 * <p>Deliberately <em>not</em> {@link Transactional}: a rolled-back test
 * transaction would never commit, and the outbox relay polls committed rows. A
 * transactional test here would silently pass while testing nothing.
 *
 * <p>Requires a Docker daemon. Without one the whole class is skipped rather than
 * failed, so {@code ./mvnw test} still works on a machine without Docker.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
@ActiveProfiles("test")
@RequiresDocker
public @interface IntegrationTest {
}
