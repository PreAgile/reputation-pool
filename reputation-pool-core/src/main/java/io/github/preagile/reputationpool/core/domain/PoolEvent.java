/*
 * Copyright 2026 the reputation-pool authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
        /**
         * @throws NullPointerException if any component is null
         * @throws IllegalArgumentException if {@code until} is before {@code at} — a cooldown
         *     cannot end before it began
         */
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
        /**
         * @throws NullPointerException if any component is null
         */
        public ResourceRecovered {
            Objects.requireNonNull(resource, "resource must not be null");
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }

    /**
     * A resource was isolated from selection until {@code until} ({@link Instant#MAX} for a permanent
     * block). Resource-global, not per-context: a hard block removes it everywhere.
     */
    record ResourceBlocklisted(ResourceId resource, Instant at, Instant until) implements PoolEvent {
        /**
         * @throws NullPointerException if any component is null
         * @throws IllegalArgumentException if {@code until} is before {@code at}
         */
        public ResourceBlocklisted {
            Objects.requireNonNull(resource, "resource must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(until, "until must not be null");
            if (until.isBefore(at)) {
                throw new IllegalArgumentException("until must not be before at");
            }
        }
    }

    /** A resource was released from the blocklist and may be selected again. */
    record ResourceUnblocked(ResourceId resource, Instant at) implements PoolEvent {
        /**
         * @throws NullPointerException if any component is null
         */
        public ResourceUnblocked {
            Objects.requireNonNull(resource, "resource must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }

    /** A resource was leased to a caller for a context until {@code until}. */
    record ResourceLeased(ResourceId resource, Context context, Instant at, Instant until) implements PoolEvent {
        /**
         * @throws NullPointerException if any component is null
         * @throws IllegalArgumentException if {@code until} is before {@code at}
         */
        public ResourceLeased {
            Objects.requireNonNull(resource, "resource must not be null");
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(until, "until must not be null");
            if (until.isBefore(at)) {
                throw new IllegalArgumentException("until must not be before at");
            }
        }
    }

    /** A lease on a resource was released back to the pool for a context. */
    record LeaseReleased(ResourceId resource, Context context, Instant at) implements PoolEvent {
        /**
         * @throws NullPointerException if any component is null
         */
        public LeaseReleased {
            Objects.requireNonNull(resource, "resource must not be null");
            Objects.requireNonNull(context, "context must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }
}
