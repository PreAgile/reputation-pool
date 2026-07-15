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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration specification for {@link PostgresAuditTrail#purgeOlderThan(Instant)} against a real
 * PostgreSQL — the age-based retention that bounds the otherwise unbounded trail. The contract under
 * test: purge only ever trims the <em>oldest tail</em> of the ledger (strictly older than the cutoff,
 * to the nanosecond), survivors are byte-identical afterwards, rows appended mid-purge are never
 * caught in the sweep, a run stops at the first batch where fresher-stamped rows appear (stragglers
 * wait for the next run), and a no-op purge costs one bounded batch-sized read.
 *
 * <p>Old rows are seeded by direct SQL rather than through the trail: the seeding is not what is under
 * test, and direct inserts give exact control over {@code occurred_at} down to the nanosecond the
 * cutoff tests need. Mid-purge interleaving rides the {@code afterPurgeRound} seam — a synchronous
 * hook between delete rounds — so overlap is proven deterministically, with no thread timing at all.
 */
class PostgresAuditTrailRetentionIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant CUTOFF = Instant.parse("2026-07-13T00:00:00Z");

    private PGSimpleDataSource dataSource;

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @BeforeEach
    void migrateFreshSchema() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        Flyway flyway =
                Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    @DisplayName("the cutoff is exact to the nanosecond: strictly-older rows go, the cutoff row and"
            + " everything after it stay")
    void cutoffIsExactToTheNanosecond() {
        insertRowAt(CUTOFF.minusNanos(1));
        insertRowAt(CUTOFF);
        insertRowAt(CUTOFF.plusNanos(1));

        long purged;
        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            purged = trail.purgeOlderThan(CUTOFF);
        }

        assertThat(purged).isEqualTo(1);
        assertThat(readOccurredAtNanos())
                .containsExactly(
                        SnapshotMapper.instantToEpochNanos(CUTOFF),
                        SnapshotMapper.instantToEpochNanos(CUTOFF.plusNanos(1)));
    }

    @Test
    @DisplayName("append-only survives the purge: surviving rows keep their seq, their columns, and"
            + " their order byte-for-byte — only the oldest tail is gone")
    void survivorsAreUntouchedByteForByte() {
        insertRowAt(CUTOFF.minusSeconds(60));
        insertRowAt(CUTOFF.minusSeconds(30));
        insertFullRow(
                "RESOURCE_COOLED",
                "PROXY",
                "1.2.3.4:8080",
                "marketplace-a",
                CUTOFF,
                CUTOFF.plusSeconds(3600),
                "TIMEOUT");
        insertFullRow("RESOURCE_BLOCKLISTED", "ACCOUNT", "acct-42", null, CUTOFF.plusSeconds(1), null, null);
        insertFullRow("LEASE_RELEASED", "PROXY", "1.2.3.4:8080", "marketplace-b", CUTOFF.plusSeconds(2), null, null);

        List<StoredRow> freshBefore = readAllRows().stream()
                .filter(row -> row.occurredAtNanos() >= cutoffNanos())
                .toList();

        long purged;
        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            purged = trail.purgeOlderThan(CUTOFF);
        }

        assertThat(purged).isEqualTo(2);
        assertThat(readAllRows())
                .as("purge never rewrites history; it only shortens the far end")
                .containsExactlyElementsOf(freshBefore);
    }

    @Test
    @DisplayName("rows appended while a purge is mid-flight are never caught in the sweep — later"
            + " rounds walk past them and only the old tail goes")
    void rowsAppendedMidPurgeAreNeverLost() {
        int oldRows = 30; // three full rounds at batch size 10, so the seam fires mid-purge
        int freshRows = 5;
        seedOldRows(oldRows);

        // The afterPurgeRound seam runs synchronously between delete rounds, on the purging thread:
        // rows inserted here provably exist while the purge is still mid-flight — deterministic
        // overlap, no thread timing. Insert once, after the first round.
        AtomicBoolean inserted = new AtomicBoolean();
        Runnable appendMidPurge = () -> {
            if (inserted.compareAndSet(false, true)) {
                for (int i = 0; i < freshRows; i++) {
                    insertRowAt(CUTOFF.plusSeconds(i));
                }
            }
        };

        long purged;
        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource, 16, 10, appendMidPurge)) {
            purged = trail.purgeOlderThan(CUTOFF);
        }

        assertThat(inserted).as("the fresh rows really landed mid-purge").isTrue();
        assertThat(purged).isEqualTo(oldRows);
        List<Long> remaining = readOccurredAtNanos();
        assertThat(remaining).hasSize(freshRows);
        assertThat(remaining).allSatisfy(nanos -> assertThat(nanos).isGreaterThanOrEqualTo(cutoffNanos()));
    }

    @Test
    @DisplayName("an empty trail is a no-op purge")
    void emptyTableIsANoOp() {
        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            assertThat(trail.purgeOlderThan(CUTOFF)).isZero();
        }
    }

    @Test
    @DisplayName("an all-fresh trail is a no-op purge: nothing deleted, every row still there")
    void allFreshTableIsANoOp() {
        insertRowAt(CUTOFF);
        insertRowAt(CUTOFF.plusSeconds(1));
        insertRowAt(CUTOFF.plusSeconds(2));
        List<StoredRow> before = readAllRows();

        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            assertThat(trail.purgeOlderThan(CUTOFF)).isZero();
        }

        assertThat(readAllRows()).containsExactlyElementsOf(before);
    }

    @Test
    @DisplayName("a mixed batch — expired and fresh rows interleaved by emitter timestamp skew —"
            + " deletes its expired rows and stops the run; the next run takes the stragglers")
    void mixedBatchStopsTheRunAndTheNextRunTakesTheStragglers() {
        // seq order tracks time order only as closely as emitters' stamps agree. Manufacture the skew
        // shape: a fresh-stamped row sitting between expired ones. One run must delete the expired
        // rows it fetched alongside the fresh one, then stop rather than scan on — the expired rows
        // beyond that batch are stragglers for the next run, which is how the retention bound
        // degrades gracefully (by at most one purge period) instead of scanning past every fresh row.
        insertRowAt(CUTOFF.minusSeconds(10)); // seq 1, expired
        insertRowAt(CUTOFF.minusSeconds(9)); //  seq 2, expired
        insertRowAt(CUTOFF.minusSeconds(8)); //  seq 3, expired
        insertRowAt(CUTOFF.plusSeconds(1)); //   seq 4, fresh (skewed stamp)
        insertRowAt(CUTOFF.minusSeconds(7)); //  seq 5, expired straggler
        insertRowAt(CUTOFF.minusSeconds(6)); //  seq 6, expired straggler
        insertRowAt(CUTOFF.minusSeconds(5)); //  seq 7, expired straggler

        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource, 16, 4)) {
            assertThat(trail.purgeOlderThan(CUTOFF))
                    .as("the first run deletes the mixed batch's expired rows (seq 1-3) and stops")
                    .isEqualTo(3);
            assertThat(readOccurredAtNanos())
                    .as("the fresh row and the stragglers beyond the mixed batch survive the run")
                    .containsExactly(
                            SnapshotMapper.instantToEpochNanos(CUTOFF.plusSeconds(1)),
                            SnapshotMapper.instantToEpochNanos(CUTOFF.minusSeconds(7)),
                            SnapshotMapper.instantToEpochNanos(CUTOFF.minusSeconds(6)),
                            SnapshotMapper.instantToEpochNanos(CUTOFF.minusSeconds(5)));

            assertThat(trail.purgeOlderThan(CUTOFF))
                    .as("the next run (the next hour, operationally) takes the stragglers")
                    .isEqualTo(3);
            assertThat(readOccurredAtNanos())
                    .containsExactly(SnapshotMapper.instantToEpochNanos(CUTOFF.plusSeconds(1)));
        }
    }

    @Test
    @DisplayName("a tail larger than one delete batch is purged completely, across as many rounds as" + " it takes")
    void multiBatchTailIsPurgedCompletely() {
        seedOldRows(25);
        insertRowAt(CUTOFF.plusSeconds(5));

        long purged;
        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource, 16, 10)) {
            purged = trail.purgeOlderThan(CUTOFF); // rounds of 10, 10, 5
        }

        assertThat(purged).isEqualTo(25);
        assertThat(readOccurredAtNanos()).containsExactly(SnapshotMapper.instantToEpochNanos(CUTOFF.plusSeconds(5)));
    }

    @Test
    @DisplayName("a tail that is an exact multiple of the batch size still terminates: the final empty"
            + " round ends the loop")
    void exactMultipleOfBatchSizeTerminates() {
        seedOldRows(20);

        long purged;
        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource, 16, 10)) {
            purged = trail.purgeOlderThan(CUTOFF); // rounds of 10, 10, then 0
        }

        assertThat(purged).isEqualTo(20);
        assertThat(readOccurredAtNanos()).isEmpty();
    }

    private long cutoffNanos() {
        return SnapshotMapper.instantToEpochNanos(CUTOFF);
    }

    /** One minimal RESOURCE_UNBLOCKED row stamped at {@code occurredAt}, straight into the table. */
    private void insertRowAt(Instant occurredAt) {
        insertFullRow("RESOURCE_UNBLOCKED", "PROXY", "1.2.3.4:8080", null, occurredAt, null, null);
    }

    private void insertFullRow(
            String eventType,
            String resourceKind,
            String resourceValue,
            String context,
            Instant occurredAt,
            Instant until,
            String cause) {
        String sql =
                """
                INSERT INTO audit_event (event_type, resource_kind, resource_value, context, occurred_at,
                    until, cause)
                VALUES (?, ?, ?, ?, ?, ?, ?)""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, eventType);
            statement.setString(2, resourceKind);
            statement.setString(3, resourceValue);
            statement.setString(4, context);
            statement.setLong(5, SnapshotMapper.instantToEpochNanos(occurredAt));
            if (until == null) {
                statement.setNull(6, Types.BIGINT);
            } else {
                statement.setLong(6, SnapshotMapper.instantToEpochNanos(until));
            }
            statement.setString(7, cause);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("failed to seed an audit row", e);
        }
    }

    /** Bulk-seeds {@code count} rows all strictly older than {@link #CUTOFF}, oldest first. */
    private void seedOldRows(int count) {
        String sql =
                """
                INSERT INTO audit_event (event_type, resource_kind, resource_value, context, occurred_at,
                    until, cause)
                VALUES ('RESOURCE_UNBLOCKED', 'PROXY', '1.2.3.4:8080', NULL, ?, NULL, NULL)""";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            long base = SnapshotMapper.instantToEpochNanos(CUTOFF) - count;
            for (int i = 0; i < count; i++) {
                statement.setLong(1, base + i);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new PersistenceException("failed to seed old audit rows", e);
        }
    }

    /** Every column of every row, in {@code seq} order — the byte-for-byte view the contract needs. */
    private List<StoredRow> readAllRows() {
        String sql =
                """
                SELECT seq, event_type, resource_kind, resource_value, context, occurred_at, until, cause
                FROM audit_event
                ORDER BY seq""";
        List<StoredRow> rows = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                long untilNanos = resultSet.getLong(7);
                Long until = resultSet.wasNull() ? null : untilNanos;
                rows.add(new StoredRow(
                        resultSet.getLong(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getString(5),
                        resultSet.getLong(6),
                        until,
                        resultSet.getString(8)));
            }
        } catch (SQLException e) {
            throw new PersistenceException("failed to read the audit trail", e);
        }
        return rows;
    }

    private List<Long> readOccurredAtNanos() {
        return readAllRows().stream().map(StoredRow::occurredAtNanos).toList();
    }

    /** A full audit row as stored, {@code seq} included, so equality means byte-identical survival. */
    private record StoredRow(
            long seq,
            String eventType,
            String resourceKind,
            String resourceValue,
            String context,
            long occurredAtNanos,
            Long untilNanos,
            String cause) {}
}
