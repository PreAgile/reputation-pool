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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.persistence.SnapshotMapper.OutcomeRow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Behavior specification for {@link SnapshotMapper} — the row&#8596;domain translation, exercised
 * with no database so it runs in the Docker-free {@code build}. The permanent-block case is the
 * regression that matters most: {@link Instant#MAX} does not fit a {@code timestamptz}, so it must
 * survive the trip through SQL {@code NULL}.
 */
class SnapshotMapperTest {

    @Nested
    @DisplayName("the outcome window round-trips through its row form")
    class OutcomeWindow {

        @Test
        @DisplayName("a Success preserves its latency and has no failure type")
        void successRoundTrips() {
            Outcome success = new Outcome.Success(Duration.ofMillis(1234));

            OutcomeRow row = SnapshotMapper.outcomeToRow(success);
            assertThat(row.success()).isTrue();
            assertThat(row.failureType()).isNull();
            assertThat(row.latencyMs()).isEqualTo(1234);

            assertThat(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyMs()))
                    .isEqualTo(success);
        }

        @Test
        @DisplayName("a Failure preserves its type and latency")
        void failureRoundTrips() {
            Outcome failure = new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(50));

            OutcomeRow row = SnapshotMapper.outcomeToRow(failure);
            assertThat(row.success()).isFalse();
            assertThat(row.failureType()).isEqualTo("BLOCKED");
            assertThat(row.latencyMs()).isEqualTo(50);

            assertThat(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyMs()))
                    .isEqualTo(failure);
        }

        @Test
        @DisplayName("every FailureType name round-trips")
        void everyFailureTypeRoundTrips() {
            for (FailureType type : FailureType.values()) {
                Outcome failure = new Outcome.Failure(type, Duration.ofMillis(7));
                OutcomeRow row = SnapshotMapper.outcomeToRow(failure);
                assertThat(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyMs()))
                        .isEqualTo(failure);
            }
        }

        @Test
        @DisplayName("a mixed window keeps its order when stored by ordinal and read back")
        void windowOrderPreservedByOrdinal() {
            List<Outcome> window = List.of(
                    new Outcome.Success(Duration.ofMillis(10)),
                    new Outcome.Failure(FailureType.TIMEOUT, Duration.ofMillis(20)),
                    new Outcome.Failure(FailureType.SLOW, Duration.ofMillis(30)),
                    new Outcome.Success(Duration.ofMillis(40)));

            // ordinal -> row, as the store writes it
            List<OutcomeRow> rows =
                    window.stream().map(SnapshotMapper::outcomeToRow).toList();
            // rows read back ordered by ordinal -> outcomes, as the store reads it
            List<Outcome> rebuilt = new ArrayList<>();
            for (OutcomeRow row : rows) {
                rebuilt.add(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyMs()));
            }

            assertThat(rebuilt).containsExactlyElementsOf(window);
        }
    }

    @Nested
    @DisplayName("blocklist expiry maps to the until column")
    class BlocklistUntil {

        @Test
        @DisplayName("a permanent block (Instant.MAX) maps to NULL and back to Instant.MAX")
        void permanentBlockIsNull() {
            assertThat(SnapshotMapper.blocklistUntilToTimestamp(Instant.MAX)).isNull();
            assertThat(SnapshotMapper.timestampToBlocklistUntil(null)).isEqualTo(Instant.MAX);
        }

        @Test
        @DisplayName("a finite expiry maps to a timestamp and back to the same instant")
        void finiteBlockRoundTrips() {
            Instant until = Instant.parse("2026-07-12T10:15:30Z");

            Timestamp stored = SnapshotMapper.blocklistUntilToTimestamp(until);
            assertThat(stored).isNotNull();
            assertThat(SnapshotMapper.timestampToBlocklistUntil(stored)).isEqualTo(until);
        }
    }

    @Nested
    @DisplayName("plain instant columns round-trip")
    class PlainInstant {

        @Test
        @DisplayName("the Instant.EPOCH 'not cooling' cooldown sentinel is preserved")
        void epochSentinelPreserved() {
            assertThat(SnapshotMapper.toInstant(SnapshotMapper.toTimestamp(Instant.EPOCH)))
                    .isEqualTo(Instant.EPOCH);
        }

        @Test
        @DisplayName("an ordinary instant is preserved")
        void ordinaryInstantPreserved() {
            Instant instant = Instant.parse("2026-07-12T08:00:00Z");
            assertThat(SnapshotMapper.toInstant(SnapshotMapper.toTimestamp(instant)))
                    .isEqualTo(instant);
        }
    }
}
