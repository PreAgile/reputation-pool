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

import io.github.preagile.reputationpool.core.domain.Blocklist;
import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration specification for {@link PostgresResourceStore} against a real PostgreSQL started by
 * Testcontainers and migrated by Flyway. It proves the SQL and transaction handling this class owns
 * (not just the pure mapping), most importantly that a blocklisted resource — including a permanently
 * blocked one — survives a save/load, the exact recovery regression the persistence layer exists to
 * prevent.
 *
 * <p>This lives in the {@code integrationTest} source set, which is not wired into {@code build}, so
 * {@code ./gradlew build} needs no Docker; it runs on demand and in CI.
 */
class PostgresResourceStoreIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private PostgresResourceStore store;

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

        // A clean schema per test: drop everything, then re-run the migration.
        Flyway flyway =
                Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();

        store = new PostgresResourceStore(dataSource);
    }

    @Test
    @DisplayName("save then load round-trips the whole snapshot — cells with windows, blocklist, registered")
    void roundTripsWholeSnapshot() {
        ResourceId proxy = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
        ResourceId account = new ResourceId(ResourceKind.ACCOUNT, "acct-42");
        Context market = new Context("marketplace-a");

        ReputationCell healthy = ReputationCell.fresh(proxy, market, Instant.parse("2026-07-12T09:00:00Z"));
        ReputationCell cooling = new ReputationCell(
                account,
                market,
                -3.5,
                4,
                0,
                List.of(
                        // A sub-millisecond latency: exercises nanosecond precision against a real DB.
                        new Outcome.Success(Duration.ofNanos(1_500_000)),
                        new Outcome.Failure(FailureType.TIMEOUT, Duration.ofMillis(2000)),
                        new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(50))),
                ResourceState.COOLING,
                Instant.parse("2026-07-12T10:00:00Z"),
                // Deliberately not the window's newest failure (BLOCKED, above): the cooling cause is its
                // own stored column, so a round-trip that re-derived it from the window would fail here.
                // The healthy cell above carries the other case, a null cause for a cell never cooled.
                FailureType.TIMEOUT,
                // updatedAt carries a sub-microsecond nanosecond fraction that timestamptz would truncate.
                Instant.ofEpochSecond(1_752_312_600L, 123_456_789));

        PoolSnapshot snapshot = new PoolSnapshot(
                Map.of(new CellKey(proxy, market), healthy, new CellKey(account, market), cooling),
                Blocklist.empty()
                        .block(account, Instant.parse("2026-07-12T12:00:00Z"))
                        .blockPermanently(proxy),
                Set.of(proxy, account));

        store.save(snapshot);

        assertThat(store.load()).contains(snapshot);
    }

    @Test
    @DisplayName("a blocklisted resource, including a permanently blocked one, survives save/load")
    void blocklistSurvivesRestart() {
        ResourceId finite = new ResourceId(ResourceKind.PROXY, "9.9.9.9:3128");
        ResourceId permanent = new ResourceId(ResourceKind.ACCOUNT, "banned-account");
        Instant expiry = Instant.parse("2026-07-12T15:00:00Z");

        Blocklist blocklist = Blocklist.empty().block(finite, expiry).blockPermanently(permanent);
        store.save(new PoolSnapshot(Map.of(), blocklist, Set.of(finite, permanent)));

        Optional<PoolSnapshot> loaded = store.load();
        assertThat(loaded).isPresent();

        Blocklist reloaded = loaded.get().blocklist();
        assertThat(reloaded.entries()).containsEntry(finite, expiry);
        // The permanent block round-trips as Instant.MAX (stored NULL), never re-lendable.
        assertThat(reloaded.entries()).containsEntry(permanent, Instant.MAX);
        assertThat(reloaded.isBlocked(permanent, Instant.parse("2999-01-01T00:00:00Z")))
                .isTrue();
    }

    @Test
    @DisplayName("first run — load() on a migrated but empty database is empty (no snapshot_meta row)")
    void firstRunIsEmpty() {
        assertThat(store.load()).isEmpty();
    }

    @Test
    @DisplayName("saving an empty pool then load() returns an empty snapshot, distinguished from first run")
    void emptyPoolIsDistinctFromFirstRun() {
        PoolSnapshot empty = new PoolSnapshot(Map.of(), Blocklist.empty(), Set.of());
        store.save(empty);

        assertThat(store.load()).contains(empty);
    }

    @Test
    @DisplayName("V6 backfills cooldown_cause on an existing V5 checkpoint from the newest window failure")
    void v6BackfillsTheCoolingCauseOfAnExistingCheckpoint() throws SQLException {
        // The other tests migrate a fresh schema, where the backfill has nothing to do. This one is
        // about the upgrade path: V6 must leave a live checkpoint with the ownership decision it
        // already had (the old code read the newest failure in the window), not reset every cooled
        // cell to "cause unknown", which would hand cells cooled by a site block to the prober.
        // @BeforeEach has already migrated to head; drop back to the pre-V6 schema to seed it
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        migrateTo("5");
        seedV5Checkpoint();

        migrateTo("6");

        // p1 is COOLING: the newest *failure* wins, and the Success recorded after it is skipped, which
        // is exactly what the removed backwards scan did
        assertThat(cooldownCauseOf("p1")).isEqualTo("BLOCKED");
        // p2 is COOLING but its window holds no failure at all — the conservative "not a block" answer
        assertThat(cooldownCauseOf("p2")).isNull();
        // p3 is HEALTHY: the column is only ever read for a cooling cell, so inventing a cause is noise
        assertThat(cooldownCauseOf("p3")).isNull();
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .target(MigrationVersion.fromVersion(version))
                .load()
                .migrate();
    }

    /** Writes rows in the shape V5 produced — no {@code cooldown_cause} column exists yet. */
    private void seedV5Checkpoint() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    """
                    INSERT INTO cell (pool_id, resource_kind, resource_value, context, score,
                        consecutive_failures, consecutive_successes, state, cooldown_until, updated_at)
                    VALUES ('default', 'PROXY', 'p1', 'ctx', -30, 3, 0, 'COOLING', 0, 0),
                           ('default', 'PROXY', 'p2', 'ctx', -5, 3, 0, 'COOLING', 0, 0),
                           ('default', 'PROXY', 'p3', 'ctx', 10, 0, 2, 'HEALTHY', 0, 0)""");
            statement.execute(
                    """
                    INSERT INTO cell_outcome (pool_id, resource_kind, resource_value, context, ordinal,
                        success, failure_type, latency_ns)
                    VALUES ('default', 'PROXY', 'p1', 'ctx', 0, false, 'TIMEOUT', 1),
                           ('default', 'PROXY', 'p1', 'ctx', 1, false, 'BLOCKED', 1),
                           ('default', 'PROXY', 'p1', 'ctx', 2, true, NULL, 1),
                           ('default', 'PROXY', 'p2', 'ctx', 0, true, NULL, 1),
                           ('default', 'PROXY', 'p3', 'ctx', 0, false, 'BLOCKED', 1)""");
        }
    }

    private String cooldownCauseOf(String resourceValue) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT cooldown_cause FROM cell WHERE resource_value = ?")) {
            statement.setString(1, resourceValue);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("cell %s exists", resourceValue).isTrue();
                return resultSet.getString(1);
            }
        }
    }
}
