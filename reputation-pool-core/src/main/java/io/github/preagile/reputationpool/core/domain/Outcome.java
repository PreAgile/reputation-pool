package io.github.preagile.reputationpool.core.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * The result of using a resource once — the engine's only reputation input.
 *
 * <p>An outcome is exactly one of {@link Success} or {@link Failure}. Modelling this as a
 * {@code sealed} interface tells the compiler the set of cases is closed, so a {@code switch} over
 * an {@code Outcome} that handles both is exhaustive without a {@code default}. Adding a third case
 * later would then turn every such {@code switch} into a compile error until it is handled — the
 * compiler, not the author, tracks the unhandled sites.
 *
 * <p>Both cases carry {@link #latency()} so callers and the engine can reason about timing (p95,
 * slow detection) uniformly, without first discriminating success from failure.
 */
public sealed interface Outcome {

    /** How long the use took. Never null; never negative. */
    Duration latency();

    /** A successful use. */
    record Success(Duration latency) implements Outcome {
        public Success {
            requireNonNegativeLatency(latency);
        }
    }

    /** A failed use, classified by the caller into a {@link FailureType}. */
    record Failure(FailureType type, Duration latency) implements Outcome {
        public Failure {
            Objects.requireNonNull(type, "type must not be null");
            requireNonNegativeLatency(latency);
        }
    }

    private static void requireNonNegativeLatency(Duration latency) {
        Objects.requireNonNull(latency, "latency must not be null");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
    }
}
