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

import io.github.preagile.reputationpool.core.domain.Blocklist;
import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import io.github.preagile.reputationpool.core.port.ResourceStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * A {@link ResourceStore} backed by PostgreSQL over plain JDBC (no Spring, JPA, or Hibernate). The
 * whole {@link PoolSnapshot} is the unit of persistence, stored across normalized tables and replaced
 * atomically on every {@link #save}.
 *
 * <p><b>Whole-replace in one transaction.</b> {@code save} turns autocommit off, deletes every row of
 * the four state tables, re-inserts the snapshot, upserts the single {@code snapshot_meta} marker
 * row, and commits. A reader therefore never observes a half-written checkpoint, and the store never
 * accumulates stale cells or blocklist entries. This matches the port's "replacing any previous one"
 * contract.
 *
 * <p><b>First run vs. empty pool.</b> {@code load} keys off {@code snapshot_meta}: no marker row means
 * nothing was ever saved and it returns {@link Optional#empty()} (first run); a marker row present
 * with no cells/blocklist/registered means an empty pool <em>was</em> saved and it returns an empty
 * snapshot. That distinction is the whole reason for the marker table.
 *
 * <p>The row&#8596;domain translation lives in {@link SnapshotMapper} as pure functions, so the tricky
 * parts (permanent block as {@code NULL}, outcome window round-trip) are tested without a database.
 * This class owns only the SQL and transaction handling.
 */
public final class PostgresResourceStore implements ResourceStore {

    /** The pool namespace used when a caller does not supply one — matches the {@code V3} column default. */
    private static final String DEFAULT_POOL_ID = "default";

    private final DataSource dataSource;
    private final Clock clock;
    private final String poolId;

    /**
     * Creates a store over {@code dataSource} for the {@code default} pool, timestamping each saved
     * checkpoint with the system UTC clock. Backward-compatible entry point: a single-pool host (the
     * reference server) keeps its exact prior behavior, now expressed as the {@code default} namespace.
     *
     * @param dataSource the pooled connection source to PostgreSQL; never null
     */
    public PostgresResourceStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC(), DEFAULT_POOL_ID);
    }

    /**
     * Creates a {@code default}-pool store over {@code dataSource} using {@code clock} for the
     * {@code snapshot_meta} timestamp — the injectable-time overload, used by tests. The clock touches
     * only the marker timestamp; it is never read back into the reconstructed snapshot.
     *
     * @param dataSource the pooled connection source to PostgreSQL; never null
     * @param clock the clock stamping {@code saved_at}; never null
     */
    public PostgresResourceStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, DEFAULT_POOL_ID);
    }

    /**
     * Creates a store scoped to one {@code poolId} — the namespace every read and write is confined to.
     * Two stores over the same {@code dataSource} with different pool ids see and touch disjoint rows:
     * {@code save} deletes and re-inserts only this pool's rows, and {@code load} reads only this pool's
     * rows, so one pool's checkpoint never overwrites another's. This is the constructor a multi-tenant
     * host uses, one store per tenant.
     *
     * @param dataSource the pooled connection source to PostgreSQL; never null
     * @param clock the clock stamping {@code saved_at}; never null
     * @param poolId the pool namespace this store is confined to; never null
     */
    public PostgresResourceStore(DataSource dataSource, Clock clock, String poolId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.poolId = Objects.requireNonNull(poolId, "poolId must not be null");
    }

    @Override
    public void save(PoolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                deleteAll(connection);
                insertCells(connection, snapshot.cells());
                insertBlocklist(connection, snapshot.blocklist());
                insertRegistered(connection, snapshot.registered());
                upsertMeta(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    // Keep the original failure as the primary exception; a failed rollback is
                    // secondary context, not a replacement for what actually went wrong.
                    e.addSuppressed(rollbackEx);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Avoid masking the primary exception; the connection is closing anyway.
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("failed to save snapshot", e);
        }
    }

    @Override
    public Optional<PoolSnapshot> load() {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            int previousIsolation = connection.getTransactionIsolation();
            // Read every table inside one REPEATABLE READ transaction so a concurrent save() commit
            // cannot yield a torn snapshot (e.g. cells from one checkpoint, blocklist from the next).
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                Optional<PoolSnapshot> snapshot;
                if (!snapshotExists(connection)) {
                    snapshot = Optional.empty();
                } else {
                    Map<CellKey, ReputationCell> cells = loadCells(connection);
                    Blocklist blocklist = loadBlocklist(connection);
                    Set<ResourceId> registered = loadRegistered(connection);
                    snapshot = Optional.of(new PoolSnapshot(cells, blocklist, registered));
                }
                connection.commit();
                return snapshot;
            } finally {
                try {
                    connection.setTransactionIsolation(previousIsolation);
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                    // Avoid masking the primary exception; the connection is closing anyway.
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("failed to load snapshot", e);
        }
    }

    // --- save helpers -------------------------------------------------------

    private void deleteAll(Connection connection) throws SQLException {
        // Every delete is narrowed to this pool's rows with WHERE pool_id = ?, so a save() never touches
        // another pool's state. Deleting this pool's cell rows cascades to their cell_outcome rows (the
        // pool-scoped ON DELETE CASCADE), so no explicit DELETE FROM cell_outcome is needed.
        try (PreparedStatement deleteCell = connection.prepareStatement("DELETE FROM cell WHERE pool_id = ?");
                PreparedStatement deleteBlocklist =
                        connection.prepareStatement("DELETE FROM blocklist_entry WHERE pool_id = ?");
                PreparedStatement deleteRegistered =
                        connection.prepareStatement("DELETE FROM registered_resource WHERE pool_id = ?")) {
            deleteCell.setString(1, poolId);
            deleteBlocklist.setString(1, poolId);
            deleteRegistered.setString(1, poolId);
            deleteCell.executeUpdate();
            deleteBlocklist.executeUpdate();
            deleteRegistered.executeUpdate();
        }
    }

    private void insertCells(Connection connection, Map<CellKey, ReputationCell> cells) throws SQLException {
        String insertCell =
                """
                INSERT INTO cell (pool_id, resource_kind, resource_value, context, score, consecutive_failures,
                    consecutive_successes, state, cooldown_until, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        String insertOutcome =
                """
                INSERT INTO cell_outcome (pool_id, resource_kind, resource_value, context, ordinal, success,
                    failure_type, latency_ns)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
        try (PreparedStatement cellStatement = connection.prepareStatement(insertCell);
                PreparedStatement outcomeStatement = connection.prepareStatement(insertOutcome)) {
            for (ReputationCell cell : cells.values()) {
                ResourceId resource = cell.resourceId();
                cellStatement.setString(1, poolId);
                cellStatement.setString(2, resource.kind().name());
                cellStatement.setString(3, resource.value());
                cellStatement.setString(4, cell.context().value());
                cellStatement.setDouble(5, cell.score());
                cellStatement.setInt(6, cell.consecutiveFailures());
                cellStatement.setInt(7, cell.consecutiveSuccesses());
                cellStatement.setString(8, cell.state().name());
                cellStatement.setLong(9, SnapshotMapper.instantToEpochNanos(cell.cooldownUntil()));
                cellStatement.setLong(10, SnapshotMapper.instantToEpochNanos(cell.updatedAt()));
                cellStatement.addBatch();

                List<Outcome> window = cell.window();
                for (int ordinal = 0; ordinal < window.size(); ordinal++) {
                    SnapshotMapper.OutcomeRow row = SnapshotMapper.outcomeToRow(window.get(ordinal));
                    outcomeStatement.setString(1, poolId);
                    outcomeStatement.setString(2, resource.kind().name());
                    outcomeStatement.setString(3, resource.value());
                    outcomeStatement.setString(4, cell.context().value());
                    outcomeStatement.setInt(5, ordinal);
                    outcomeStatement.setBoolean(6, row.success());
                    outcomeStatement.setString(7, row.failureType());
                    outcomeStatement.setLong(8, row.latencyNs());
                    outcomeStatement.addBatch();
                }
            }
            cellStatement.executeBatch();
            outcomeStatement.executeBatch();
        }
    }

    private void insertBlocklist(Connection connection, Blocklist blocklist) throws SQLException {
        String sql = "INSERT INTO blocklist_entry (pool_id, resource_kind, resource_value, until) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<ResourceId, Instant> entry : blocklist.entries().entrySet()) {
                ResourceId resource = entry.getKey();
                statement.setString(1, poolId);
                statement.setString(2, resource.kind().name());
                statement.setString(3, resource.value());
                Long until = SnapshotMapper.blocklistUntilToEpochNanos(entry.getValue());
                if (until == null) {
                    statement.setNull(4, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(4, until);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertRegistered(Connection connection, Set<ResourceId> registered) throws SQLException {
        String sql = "INSERT INTO registered_resource (pool_id, resource_kind, resource_value) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ResourceId resource : registered) {
                statement.setString(1, poolId);
                statement.setString(2, resource.kind().name());
                statement.setString(3, resource.value());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void upsertMeta(Connection connection) throws SQLException {
        String sql = "INSERT INTO snapshot_meta (pool_id, saved_at) VALUES (?, ?) "
                + "ON CONFLICT (pool_id) DO UPDATE SET saved_at = EXCLUDED.saved_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, poolId);
            statement.setTimestamp(2, Timestamp.from(clock.instant()));
            statement.executeUpdate();
        }
    }

    // --- load helpers -------------------------------------------------------

    private boolean snapshotExists(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT 1 FROM snapshot_meta WHERE pool_id = ?")) {
            statement.setString(1, poolId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private Map<CellKey, ReputationCell> loadCells(Connection connection) throws SQLException {
        Map<Coordinate, List<Outcome>> windows = loadWindows(connection);
        Map<CellKey, ReputationCell> cells = new LinkedHashMap<>();
        String sql =
                """
                SELECT resource_kind, resource_value, context, score, consecutive_failures,
                    consecutive_successes, state, cooldown_until, updated_at
                FROM cell WHERE pool_id = ?""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, poolId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ResourceId resource =
                            new ResourceId(ResourceKind.valueOf(resultSet.getString(1)), resultSet.getString(2));
                    Context context = new Context(resultSet.getString(3));
                    Coordinate coordinate = new Coordinate(resource, context);
                    ReputationCell cell = new ReputationCell(
                            resource,
                            context,
                            resultSet.getDouble(4),
                            resultSet.getInt(5),
                            resultSet.getInt(6),
                            windows.getOrDefault(coordinate, List.of()),
                            ResourceState.valueOf(resultSet.getString(7)),
                            SnapshotMapper.epochNanosToInstant(resultSet.getLong(8)),
                            SnapshotMapper.epochNanosToInstant(resultSet.getLong(9)));
                    cells.put(new CellKey(resource, context), cell);
                }
            }
        }
        return cells;
    }

    private Map<Coordinate, List<Outcome>> loadWindows(Connection connection) throws SQLException {
        Map<Coordinate, List<Outcome>> windows = new HashMap<>();
        String sql =
                """
                SELECT resource_kind, resource_value, context, success, failure_type, latency_ns
                FROM cell_outcome
                WHERE pool_id = ?
                ORDER BY resource_kind, resource_value, context, ordinal""";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, poolId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ResourceId resource =
                            new ResourceId(ResourceKind.valueOf(resultSet.getString(1)), resultSet.getString(2));
                    Context context = new Context(resultSet.getString(3));
                    Outcome outcome = SnapshotMapper.toOutcome(
                            resultSet.getBoolean(4), resultSet.getString(5), resultSet.getLong(6));
                    windows.computeIfAbsent(new Coordinate(resource, context), key -> new ArrayList<>())
                            .add(outcome);
                }
            }
        }
        return windows;
    }

    private Blocklist loadBlocklist(Connection connection) throws SQLException {
        Map<ResourceId, Instant> entries = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resource_kind, resource_value, until FROM blocklist_entry WHERE pool_id = ?")) {
            statement.setString(1, poolId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ResourceId resource =
                            new ResourceId(ResourceKind.valueOf(resultSet.getString(1)), resultSet.getString(2));
                    long untilNanos = resultSet.getLong(3);
                    Long until = resultSet.wasNull() ? null : untilNanos;
                    entries.put(resource, SnapshotMapper.epochNanosToBlocklistUntil(until));
                }
            }
        }
        return new Blocklist(entries);
    }

    private Set<ResourceId> loadRegistered(Connection connection) throws SQLException {
        Set<ResourceId> registered = new java.util.HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT resource_kind, resource_value FROM registered_resource WHERE pool_id = ?")) {
            statement.setString(1, poolId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    registered.add(
                            new ResourceId(ResourceKind.valueOf(resultSet.getString(1)), resultSet.getString(2)));
                }
            }
        }
        return registered;
    }

    /** The relational identity of a cell — its {@code (resource, context)} pair — used to group a window's rows. */
    private record Coordinate(ResourceId resource, Context context) {}
}
