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
 * tests stop running too. Skipping keeps the fast tests useful everywhere while
 * CI, which always has Docker, still runs the full suite.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresDocker.DockerAvailableCondition.class)
public @interface RequiresDocker {

    class DockerAvailableCondition implements ExecutionCondition {

        // Probing the daemon takes a moment and the answer cannot change within
        // a single JVM run, so it is resolved once.
        private static volatile Boolean dockerAvailable;

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
            return ConditionEvaluationResult.disabled(
                    "Skipped: no Docker daemon. Start Docker (or Colima/OrbStack) to run integration tests.");
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
