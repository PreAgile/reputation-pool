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
import java.sql.Timestamp;
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
 *       fit a {@code timestamptz}. It is stored as SQL {@code NULL} ({@code until} column) and read
 *       back to {@link Instant#MAX}. This is the same {@code Instant.MAX}/{@code timestamptz} mismatch
 *       the proto layer hit; here the round-trip is pinned by a unit test.
 *   <li><b>Outcome window.</b> A {@link Outcome} becomes a flat {@link OutcomeRow} (and back), so a
 *       cell's window can be stored one row per outcome with no JSON library.
 * </ul>
 */
final class SnapshotMapper {

    private SnapshotMapper() {}

    /** The flat, column-shaped form of one {@link Outcome} within a cell's window. */
    record OutcomeRow(boolean success, String failureType, long latencyMs) {}

    /**
     * The row form of {@code outcome}: a {@code Success} has {@code success=true} and a {@code null}
     * failure type; a {@code Failure} has {@code success=false} and its {@link FailureType} name.
     * Latency is stored as whole milliseconds.
     */
    static OutcomeRow outcomeToRow(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Success s -> new OutcomeRow(true, null, s.latency().toMillis());
            case Outcome.Failure f ->
                new OutcomeRow(false, f.type().name(), f.latency().toMillis());
        };
    }

    /**
     * Rebuilds an {@link Outcome} from its stored columns — the inverse of {@link #outcomeToRow}. When
     * {@code success} is false, {@code failureType} is the {@link FailureType} name and must be
     * non-null.
     */
    static Outcome toOutcome(boolean success, String failureType, long latencyMs) {
        Duration latency = Duration.ofMillis(latencyMs);
        return success ? new Outcome.Success(latency) : new Outcome.Failure(FailureType.valueOf(failureType), latency);
    }

    /**
     * The {@code until} column for a blocklist expiry: {@link Instant#MAX} (a permanent block) maps to
     * SQL {@code NULL}; any finite expiry maps to a {@link Timestamp}.
     */
    static Timestamp blocklistUntilToTimestamp(Instant until) {
        return until.equals(Instant.MAX) ? null : Timestamp.from(until);
    }

    /**
     * A blocklist expiry from its {@code until} column: SQL {@code NULL} means a permanent block and
     * maps back to {@link Instant#MAX}; a present {@link Timestamp} maps to its instant.
     */
    static Instant timestampToBlocklistUntil(Timestamp until) {
        return until == null ? Instant.MAX : until.toInstant();
    }

    /** A plain (non-null) instant column, e.g. {@code cooldown_until} or {@code updated_at}. */
    static Timestamp toTimestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    /** The instant of a plain (non-null) timestamp column — the inverse of {@link #toTimestamp}. */
    static Instant toInstant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
