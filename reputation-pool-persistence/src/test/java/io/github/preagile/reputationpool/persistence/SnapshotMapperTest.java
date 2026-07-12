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
 * regression that matters most: {@link Instant#MAX} does not fit an epoch-nanos {@code bigint}, so it
 * must survive the trip through SQL {@code NULL}. Precision is the second: latency is carried in
 * nanoseconds and instants as epoch-nanoseconds so sub-millisecond and sub-microsecond values are not
 * silently truncated.
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
            assertThat(row.latencyNs()).isEqualTo(1_234_000_000L);

            assertThat(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyNs()))
                    .isEqualTo(success);
        }

        @Test
        @DisplayName("a Failure preserves its type and latency")
        void failureRoundTrips() {
            Outcome failure = new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(50));

            OutcomeRow row = SnapshotMapper.outcomeToRow(failure);
            assertThat(row.success()).isFalse();
            assertThat(row.failureType()).isEqualTo("BLOCKED");
            assertThat(row.latencyNs()).isEqualTo(50_000_000L);

            assertThat(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyNs()))
                    .isEqualTo(failure);
        }

        @Test
        @DisplayName("every FailureType name round-trips")
        void everyFailureTypeRoundTrips() {
            for (FailureType type : FailureType.values()) {
                Outcome failure = new Outcome.Failure(type, Duration.ofMillis(7));
                OutcomeRow row = SnapshotMapper.outcomeToRow(failure);
                assertThat(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyNs()))
                        .isEqualTo(failure);
            }
        }

        @Test
        @DisplayName("sub-millisecond latency survives the round-trip (nanosecond precision, not millis)")
        void subMillisecondLatencyRoundTrips() {
            // A single nanosecond and 1.5ms both truncate to the wrong value under toMillis().
            Outcome oneNano = new Outcome.Success(Duration.ofNanos(1));
            Outcome onePointFiveMillis = new Outcome.Failure(FailureType.SLOW, Duration.ofNanos(1_500_000));

            OutcomeRow nanoRow = SnapshotMapper.outcomeToRow(oneNano);
            assertThat(nanoRow.latencyNs()).isEqualTo(1L);
            assertThat(SnapshotMapper.toOutcome(nanoRow.success(), nanoRow.failureType(), nanoRow.latencyNs()))
                    .isEqualTo(oneNano);

            OutcomeRow subMilliRow = SnapshotMapper.outcomeToRow(onePointFiveMillis);
            assertThat(subMilliRow.latencyNs()).isEqualTo(1_500_000L);
            assertThat(SnapshotMapper.toOutcome(
                            subMilliRow.success(), subMilliRow.failureType(), subMilliRow.latencyNs()))
                    .isEqualTo(onePointFiveMillis);
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
                rebuilt.add(SnapshotMapper.toOutcome(row.success(), row.failureType(), row.latencyNs()));
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
            assertThat(SnapshotMapper.blocklistUntilToEpochNanos(Instant.MAX)).isNull();
            assertThat(SnapshotMapper.epochNanosToBlocklistUntil(null)).isEqualTo(Instant.MAX);
        }

        @Test
        @DisplayName("a finite expiry maps to epoch-nanos and back to the same instant")
        void finiteBlockRoundTrips() {
            Instant until = Instant.parse("2026-07-12T10:15:30Z");

            Long stored = SnapshotMapper.blocklistUntilToEpochNanos(until);
            assertThat(stored).isNotNull();
            assertThat(SnapshotMapper.epochNanosToBlocklistUntil(stored)).isEqualTo(until);
        }
    }

    @Nested
    @DisplayName("plain instant columns round-trip as epoch-nanoseconds")
    class PlainInstant {

        @Test
        @DisplayName("the Instant.EPOCH 'not cooling' cooldown sentinel is preserved (epoch-nanos 0)")
        void epochSentinelPreserved() {
            assertThat(SnapshotMapper.instantToEpochNanos(Instant.EPOCH)).isZero();
            assertThat(SnapshotMapper.epochNanosToInstant(SnapshotMapper.instantToEpochNanos(Instant.EPOCH)))
                    .isEqualTo(Instant.EPOCH);
        }

        @Test
        @DisplayName("an ordinary instant is preserved")
        void ordinaryInstantPreserved() {
            Instant instant = Instant.parse("2026-07-12T08:00:00Z");
            assertThat(SnapshotMapper.epochNanosToInstant(SnapshotMapper.instantToEpochNanos(instant)))
                    .isEqualTo(instant);
        }

        @Test
        @DisplayName("a sub-microsecond instant (nanosecond fraction) round-trips exactly")
        void subMicrosecondInstantRoundTrips() {
            // timestamptz is microsecond-capped; the 789 trailing nanos would be lost there.
            Instant instant = Instant.ofEpochSecond(1_752_312_000L, 123_456_789);

            long epochNanos = SnapshotMapper.instantToEpochNanos(instant);
            Instant rebuilt = SnapshotMapper.epochNanosToInstant(epochNanos);
            assertThat(rebuilt).isEqualTo(instant);
            assertThat(rebuilt.getNano()).isEqualTo(123_456_789);
        }
    }
}
