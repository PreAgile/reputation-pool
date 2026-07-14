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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration specification for {@link PostgresAuditTrail} against a real PostgreSQL started by
 * Testcontainers and migrated by Flyway. It proves the SQL this class owns — the append into
 * {@code audit_event} — and above all the trail's defining property: rows only ever accumulate in
 * {@code seq} order, across batches and across restarts, and a block that was later lifted is still in
 * the history.
 *
 * <p>{@code close()} is the flush barrier: the writer is asynchronous by contract, so each test closes
 * the trail before reading, which deterministically drains everything accepted. This lives in the
 * {@code integrationTest} source set, which is not wired into {@code build}, so {@code ./gradlew
 * build} needs no Docker; it runs on demand and in CI.
 */
class PostgresAuditTrailIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ResourceId PROXY = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final ResourceId ACCOUNT = new ResourceId(ResourceKind.ACCOUNT, "acct-42");
    private static final Context MARKET = new Context("marketplace-a");
    private static final Instant AT = Instant.parse("2026-07-13T00:00:00Z");

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

        // A clean schema per test: drop everything, then re-run the migrations (V1 snapshot + V2 audit).
        Flyway flyway =
                Flyway.configure().dataSource(dataSource).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    @DisplayName("emit appends one row per event, in occurrence order, across every event kind")
    void appendsOneRowPerEventInOrder() {
        // One of each sealed case, with a nanosecond-fraction instant to prove losslessness end to end.
        Instant precise = AT.plusNanos(123_456_789);
        List<PoolEvent> emitted = List.of(
                new PoolEvent.ResourceLeased(PROXY, MARKET, AT, AT.plusSeconds(30)),
                new PoolEvent.ResourceCooled(
                        PROXY, MARKET, AT.plusSeconds(1), AT.plusSeconds(3601), FailureType.TIMEOUT),
                new PoolEvent.ResourceRecovered(PROXY, MARKET, precise),
                new PoolEvent.ResourceBlocklisted(ACCOUNT, AT.plusSeconds(3), AT.plusSeconds(7200)),
                new PoolEvent.ResourceUnblocked(ACCOUNT, AT.plusSeconds(4)),
                new PoolEvent.LeaseReleased(PROXY, MARKET, AT.plusSeconds(5)));

        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            emitted.forEach(trail::emit);
        }

        assertThat(readTrail()).containsExactlyElementsOf(emitted);
    }

    @Test
    @DisplayName("append-only: a restarted trail accumulates onto the existing history, rewriting nothing")
    void restartAccumulatesInsteadOfRewriting() {
        List<PoolEvent> firstRun = List.of(
                new PoolEvent.ResourceLeased(PROXY, MARKET, AT, AT.plusSeconds(30)),
                new PoolEvent.LeaseReleased(PROXY, MARKET, AT.plusSeconds(1)));
        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            firstRun.forEach(trail::emit);
        }

        // A new trail over the same database — the restart. Unlike the snapshot store's whole-replace,
        // nothing is deleted: the earlier rows must still lead the ledger.
        PoolEvent afterRestart = new PoolEvent.ResourceUnblocked(PROXY, AT.plusSeconds(60));
        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            trail.emit(afterRestart);
        }

        assertThat(readTrail()).containsExactly(firstRun.get(0), firstRun.get(1), afterRestart);
    }

    @Test
    @DisplayName("a blocklisting survives in the trail even after the resource is unblocked")
    void blocklistingSurvivesUnblocking() {
        PoolEvent blocked = new PoolEvent.ResourceBlocklisted(ACCOUNT, AT, AT.plusSeconds(3600));
        PoolEvent unblocked = new PoolEvent.ResourceUnblocked(ACCOUNT, AT.plusSeconds(600));

        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            trail.emit(blocked);
            trail.emit(unblocked);
        }

        // The current state says "not blocked"; only the trail still knows it ever was. That history
        // surviving the lift is the whole point of append-only.
        assertThat(readTrail()).containsExactly(blocked, unblocked);
    }

    @Test
    @DisplayName("a permanent block (Instant.MAX) round-trips through the NULL until column")
    void permanentBlockRoundTrips() {
        PoolEvent permanent = new PoolEvent.ResourceBlocklisted(ACCOUNT, AT, Instant.MAX);

        try (PostgresAuditTrail trail = new PostgresAuditTrail(dataSource)) {
            trail.emit(permanent);
        }

        assertThat(readTrail()).containsExactly(permanent);
        assertThat(((PoolEvent.ResourceBlocklisted) readTrail().get(0)).until()).isEqualTo(Instant.MAX);
    }

    /** The whole ledger in {@code seq} order, rebuilt into domain events via the mapper. */
    private List<PoolEvent> readTrail() {
        String sql =
                """
                SELECT event_type, resource_kind, resource_value, context, occurred_at, until, cause
                FROM audit_event
                ORDER BY seq""";
        List<PoolEvent> events = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                long untilNanos = resultSet.getLong(6);
                Long until = resultSet.wasNull() ? null : untilNanos;
                events.add(AuditEventMapper.toEvent(new AuditEventMapper.AuditRow(
                        resultSet.getString(1),
                        resultSet.getString(2),
                        resultSet.getString(3),
                        resultSet.getString(4),
                        resultSet.getLong(5),
                        until,
                        resultSet.getString(7))));
            }
        } catch (SQLException e) {
            throw new PersistenceException("failed to read the audit trail", e);
        }
        return events;
    }
}
