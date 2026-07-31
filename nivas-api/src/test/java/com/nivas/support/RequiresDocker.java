package com.nivas.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import org.testcontainers.DockerClientFactory;

/**
 * Skips rather than fails when no Docker daemon is reachable.
 *
 * <p>A hard failure would make {@code ./mvnw test} unusable on a machine without
 * Docker, which pushes people towards {@code -DskipTests} — and then the unit
 * tests stop running too. Skipping keeps the fast tests useful everywhere.
 *
 * <p>That convenience has a sharp edge, and it bit on the very first real run:
 * Testcontainers looks for {@code /var/run/docker.sock}, which OrbStack and
 * Colima do not create, so the entire integration suite skipped and the build
 * still reported {@code BUILD SUCCESS}. A green build that tested nothing is
 * worse than a red one.
 *
 * <p>Setting {@code NIVAS_REQUIRE_DOCKER=true} — as CI does — turns an
 * unreachable daemon into a failure, so a misconfigured runner cannot pass by
 * skipping everything.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresDocker.DockerAvailableCondition.class)
public @interface RequiresDocker {

    /** Set in any environment where skipping should be treated as a failure. */
    String REQUIRE_DOCKER_ENV = "NIVAS_REQUIRE_DOCKER";

    class DockerAvailableCondition implements ExecutionCondition {

        // Probing the daemon takes a moment and the answer cannot change within
        // a single JVM run, so it is resolved once.
        private static volatile Boolean dockerAvailable;

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }

            if (Boolean.parseBoolean(System.getenv(REQUIRE_DOCKER_ENV))) {
                throw new IllegalStateException("""
                        No Docker daemon is reachable, and %s is set.

                        Testcontainers looks for /var/run/docker.sock, which OrbStack and Colima
                        do not create. If you are using one of those, point it at the real socket:

                          export DOCKER_HOST="unix://$HOME/.orbstack/run/docker.sock"
                          export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock

                        Failing rather than skipping on purpose: a skipped integration suite
                        still reports BUILD SUCCESS, which is how a broken pipeline goes green.
                        """.formatted(REQUIRE_DOCKER_ENV));
            }

            return ConditionEvaluationResult.disabled(
                    "Skipped: no Docker daemon. Start Docker (or Colima/OrbStack) to run integration "
                            + "tests, and see the note in RequiresDocker about the socket path.");
        }

        private static boolean isDockerAvailable() {
            Boolean cached = dockerAvailable;
            if (cached != null) {
                return cached;
            }
            synchronized (DockerAvailableCondition.class) {
                if (dockerAvailable == null) {
                    boolean available;
                    try {
                        available = DockerClientFactory.instance().isDockerAvailable();
                    } catch (Throwable ex) {
                        available = false;
                    }
                    dockerAvailable = available;
                }
                return dockerAvailable;
            }
        }
    }
}
