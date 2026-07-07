package io.github.preagile.reputationpool.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A fact emitted when the engine makes a state transition — the observability and debugging
 * vocabulary of the pool.
 *
 * <p>The core emits facts only; how they are counted, streamed, or displayed is the concern of the
 * {@code EventSink} port implemented outside core. This keeps core independent of any logging or
 * metrics framework: swapping the observability stack never touches core.
 *
 * <p>Modelled as a {@code sealed} interface so consumers can {@code switch} over the closed set of
 * event kinds exhaustively, while each record carries the data specific to its kind. The set grows
 * as the engine gains transitions (blocklisting and lease events arrive with the concurrency layer);
 * adding a case then turns every existing exhaustive {@code switch} into a compile error until it is
 * handled.
 *
 * <p>{@link #at()} — the moment the event occurred — is the one field universal to every event and is
 * exposed on the interface so timestamps can be read without discriminating the case.
 */
public sealed interface PoolEvent {

    /** When the event occurred. */
    Instant at();

    /** A resource entered {@code COOLING} for a context until {@code until}, triggered by {@code cause}. */
    record ResourceCooled(ResourceId resource, Context context, Instant at, Instant until, FailureType cause)
            implements PoolEvent {
        public ResourceCooled {
            Objects.requireNonNull(resource, "resource must not be null");
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(until, "until must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
            // a cooldown cannot end before it began; also guards against swapping the two Instants
            if (until.isBefore(at)) {
                throw new IllegalArgumentException("until must not be before at");
            }
        }
    }

    /** A resource was promoted back to {@code HEALTHY} for a context. */
    record ResourceRecovered(ResourceId resource, Context context, Instant at) implements PoolEvent {
        public ResourceRecovered {
            Objects.requireNonNull(resource, "resource must not be null");
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }
}
