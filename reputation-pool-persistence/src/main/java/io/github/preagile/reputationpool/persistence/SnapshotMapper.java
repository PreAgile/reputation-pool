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
package io.github.preagile.reputationpool.persistence;

import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import java.time.Duration;
import java.time.Instant;

/**
 * Pure, database-free translation between domain values and their relational row form. Every method
 * is a static function of its arguments with no JDBC and no clock, so the mapping — the part most
 * likely to hide a bug — is unit-testable without a database. {@link PostgresResourceStore} does the
 * I/O; this class does the shape-shifting.
 *
 * <p>Two representation choices live here, both isolated behind these helpers:
 *
 * <ul>
 *   <li><b>Permanent block.</b> A permanent block is {@link Instant#MAX} in the domain, which does not
 *       fit an epoch-nanos {@code bigint}. It is stored as SQL {@code NULL} ({@code until} column) and
 *       read back to {@link Instant#MAX}. This is the same {@code Instant.MAX} mismatch the proto layer
 *       hit; here the round-trip is pinned by a unit test.
 *   <li><b>Instant precision.</b> Instants are stored as epoch-nanosecond {@code bigint}, not
 *       {@code timestamptz}: PostgreSQL {@code timestamptz} is microsecond-capped and would silently
 *       truncate the domain's nanosecond-precision {@link Instant}s. Epoch-nanos {@code bigint} makes
 *       the round-trip lossless, and it is a persistence-only representation choice (no domain change).
 *   <li><b>Outcome window.</b> A {@link Outcome} becomes a flat {@link OutcomeRow} (and back), so a
 *       cell's window can be stored one row per outcome with no JSON library. Latency is carried as
 *       whole nanoseconds so sub-millisecond latencies survive the trip.
 * </ul>
 */
final class SnapshotMapper {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private SnapshotMapper() {}

    /** The flat, column-shaped form of one {@link Outcome} within a cell's window. */
    record OutcomeRow(boolean success, String failureType, long latencyNs) {}

    /**
     * The row form of {@code outcome}: a {@code Success} has {@code success=true} and a {@code null}
     * failure type; a {@code Failure} has {@code success=false} and its {@link FailureType} name.
     * Latency is stored as whole nanoseconds.
     */
    static OutcomeRow outcomeToRow(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Success s -> new OutcomeRow(true, null, s.latency().toNanos());
            case Outcome.Failure f ->
                new OutcomeRow(false, f.type().name(), f.latency().toNanos());
        };
    }

    /**
     * Rebuilds an {@link Outcome} from its stored columns — the inverse of {@link #outcomeToRow}. When
     * {@code success} is false, {@code failureType} is the {@link FailureType} name and must be
     * non-null.
     */
    static Outcome toOutcome(boolean success, String failureType, long latencyNs) {
        Duration latency = Duration.ofNanos(latencyNs);
        return success ? new Outcome.Success(latency) : new Outcome.Failure(FailureType.valueOf(failureType), latency);
    }

    /**
     * The {@code until} column for a blocklist expiry: {@link Instant#MAX} (a permanent block) maps to
     * SQL {@code NULL} (returned here as a boxed {@code null}); any finite expiry maps to its
     * epoch-nanosecond {@code bigint}.
     */
    static Long blocklistUntilToEpochNanos(Instant until) {
        return until.equals(Instant.MAX) ? null : instantToEpochNanos(until);
    }

    /**
     * A blocklist expiry from its {@code until} column: SQL {@code NULL} means a permanent block and
     * maps back to {@link Instant#MAX}; a present epoch-nanos value maps to its instant.
     */
    static Instant epochNanosToBlocklistUntil(Long until) {
        return until == null ? Instant.MAX : epochNanosToInstant(until);
    }

    /**
     * A plain (non-null) instant column (e.g. {@code cooldown_until} or {@code updated_at}) as
     * epoch-nanoseconds. Uses exact arithmetic so an overflow surfaces rather than silently wrapping.
     */
    static long instantToEpochNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    /** The instant of a plain epoch-nanos column — the inverse of {@link #instantToEpochNanos}. */
    static Instant epochNanosToInstant(long epochNanos) {
        return Instant.ofEpochSecond(
                Math.floorDiv(epochNanos, NANOS_PER_SECOND), Math.floorMod(epochNanos, NANOS_PER_SECOND));
    }
}
