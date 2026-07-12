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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.flywaydb.core.Flyway;
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
                        new Outcome.Success(Duration.ofMillis(120)),
                        new Outcome.Failure(FailureType.TIMEOUT, Duration.ofMillis(2000)),
                        new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(50))),
                ResourceState.COOLING,
                Instant.parse("2026-07-12T10:00:00Z"),
                Instant.parse("2026-07-12T09:30:00Z"));

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
}
