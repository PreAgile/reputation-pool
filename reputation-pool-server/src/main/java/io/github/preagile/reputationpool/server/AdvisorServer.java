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
package io.github.preagile.reputationpool.server;

import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.core.port.ResourceStore;
import io.github.preagile.reputationpool.persistence.PostgresAuditTrail;
import io.github.preagile.reputationpool.persistence.PostgresResourceStore;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The composition root: the one place that assembles real parts — engine, strategy, broadcaster,
 * clock, randomness — into a {@link ResourcePool} and puts the {@link ReputationAdvisorService} on
 * a port. Everything else in this module takes its collaborators through the constructor, so this
 * is also the only place a production default (system clock, seeded-by-entropy random) is chosen;
 * tests assemble the same graph with {@code Clock.fixed(...)} and a seeded generator instead.
 *
 * <p>When a {@link ResourceStore} is supplied, this root also owns the pool's durable lifecycle:
 * restore-on-start (before any traffic), a periodic background checkpoint, and a final checkpoint on
 * an orderly shutdown. The store is an injected port — a concrete implementation (PostgreSQL) is
 * chosen only in {@link #main}. With no store the lifecycle hooks are all no-ops, so the in-memory
 * mode stays exactly as before.
 */
public final class AdvisorServer {

    private static final Logger LOG = System.getLogger(AdvisorServer.class.getName());

    /** Engine tuning mirrors the L1 adapter demos: window 10, cool after 2, recover after 2. */
    private static final int WINDOW_SIZE = 10;

    private static final int COOL_AFTER = 2;
    private static final int RECOVER_AFTER = 2;
    private static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(30);

    /** How often the background checkpointer saves the pool's snapshot to the store. */
    private static final Duration DEFAULT_CHECKPOINT_INTERVAL = Duration.ofSeconds(30);

    /** DB connection is env-driven; these are the variables {@link #main} reads. */
    private static final String ENV_DB_URL = "REPUTATION_POOL_DB_URL";

    private static final String ENV_DB_USERNAME = "REPUTATION_POOL_DB_USERNAME";
    private static final String ENV_DB_PASSWORD = "REPUTATION_POOL_DB_PASSWORD";

    private final Server server;
    private final EventBroadcaster broadcaster;
    private final ResourcePool pool;
    private final Optional<ResourceStore> store;
    private final Duration checkpointInterval;

    /** Started lazily in {@link #start()} only when a store is present; null otherwise. */
    private ScheduledExecutorService checkpointer;

    private AdvisorServer(
            Server server, EventBroadcaster broadcaster, ResourcePool pool, Optional<ResourceStore> store) {
        this.server = server;
        this.broadcaster = broadcaster;
        this.pool = pool;
        this.store = store;
        this.checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
    }

    /** Production assembly: system clock, default randomness, the default lease TTL, no store. */
    public static AdvisorServer create(int port) {
        return create(port, Clock.systemUTC(), RandomGenerator.getDefault(), DEFAULT_LEASE_TTL);
    }

    /** Test-friendly assembly with no store: every source of nondeterminism is handed in by the caller. */
    public static AdvisorServer create(int port, Clock clock, RandomGenerator random, Duration leaseTtl) {
        return assemble(port, clock, random, leaseTtl, Optional.empty(), Optional.empty());
    }

    /**
     * Store-aware assembly: the same graph as the no-store overload, plus a {@link ResourceStore} the
     * pool is restored from at startup and checkpointed to while it runs.
     *
     * @param store the durable store to restore from and checkpoint to; never null
     */
    public static AdvisorServer create(
            int port, Clock clock, RandomGenerator random, Duration leaseTtl, ResourceStore store) {
        Objects.requireNonNull(store, "store must not be null");
        return assemble(port, clock, random, leaseTtl, Optional.of(store), Optional.empty());
    }

    /**
     * Fully durable assembly: snapshot store plus an audit sink. Pool events then fan out through a
     * {@link CompositeEventSink} to both the live gRPC stream and {@code auditSink} — typically a
     * {@link PostgresAuditTrail} appending the trail. The caller owns the audit sink's lifecycle
     * (create it before, close it after {@link #shutdown} so the tail of the trail is flushed).
     *
     * @param store the durable store to restore from and checkpoint to; never null
     * @param auditSink the second consumer of every pool event; never null
     */
    public static AdvisorServer create(
            int port,
            Clock clock,
            RandomGenerator random,
            Duration leaseTtl,
            ResourceStore store,
            EventSink auditSink) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(auditSink, "auditSink must not be null");
        return assemble(port, clock, random, leaseTtl, Optional.of(store), Optional.of(auditSink));
    }

    /** The single assembler every overload family routes through. */
    private static AdvisorServer assemble(
            int port,
            Clock clock,
            RandomGenerator random,
            Duration leaseTtl,
            Optional<ResourceStore> store,
            Optional<EventSink> auditSink) {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(random, "random must not be null");
        Objects.requireNonNull(leaseTtl, "leaseTtl must not be null");
        EventBroadcaster broadcaster = new EventBroadcaster();
        // With an audit sink the stream and the trail sit as siblings under one fan-out; the pool
        // itself still holds exactly one sink, so the core stays untouched.
        EventSink poolSink = auditSink
                .<EventSink>map(audit -> new CompositeEventSink(List.of(broadcaster, audit)))
                .orElse(broadcaster);
        ResourcePool pool = new ResourcePool(
                new ReputationEngine(new AdaptiveCooldownPolicy(), WINDOW_SIZE, COOL_AFTER, RECOVER_AFTER),
                new WeightedRandomSelectionStrategy(),
                poolSink,
                clock,
                random,
                leaseTtl);
        // Restore before the server is even built, so the pool is fully rehydrated before it can accept
        // a single request. load() empty means first run — nothing to restore, no PoolSnapshot.empty needed.
        store.ifPresent(s -> s.load().ifPresent(pool::restore));
        Server server = ServerBuilder.forPort(port)
                .addService(new ReputationAdvisorService(pool, broadcaster))
                .build();
        return new AdvisorServer(server, broadcaster, pool, store);
    }

    public AdvisorServer start() throws IOException {
        server.start();
        if (store.isPresent()) {
            checkpointer = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
            checkpointer.scheduleAtFixedRate(
                    this::checkpoint,
                    checkpointInterval.toMillis(),
                    checkpointInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        return this;
    }

    /** The bound port; useful when created with port 0 (pick any free port). */
    public int port() {
        return server.getPort();
    }

    /**
     * The assembled pool. Package-private and intended for <strong>test-only</strong> inspection of the
     * durable lifecycle (restore-on-start, checkpoint, final save) — production code drives the pool
     * through the gRPC service, never through this accessor.
     *
     * @return the pool this server was assembled around
     */
    ResourcePool pool() {
        return pool;
    }

    /**
     * Writes the pool's current snapshot to the store, if one is present. Exception-isolated on purpose:
     * a failed save is logged at WARNING and swallowed, never rethrown.
     *
     * <p>This is what keeps the periodic {@code scheduleAtFixedRate} alive — that API cancels all future
     * runs the first time its task throws, so a checkpoint that let a transient DB error escape would
     * silently stop every later checkpoint. Swallowing here means one bad save is skipped, not fatal.
     * Extracting it as a method also lets tests trigger a save directly, with no scheduler timing.
     */
    void checkpoint() {
        store.ifPresent(s -> {
            try {
                s.save(pool.snapshot());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "checkpoint save failed; will retry on the next interval", e);
            }
        });
    }

    /**
     * Orderly shutdown that leaves a consistent final checkpoint. In order: (1) stop the periodic
     * checkpointer and await its termination so any in-flight periodic save finishes and is drained
     * before the final save — the two can then never overlap; (2) complete event streams so subscribers
     * see a clean end instead of a transport reset; (3) drain in-flight RPCs within the grace period so
     * the pool's state is final (any reports still arriving are applied first); (4) take one final
     * checkpoint of that now-stable state, so a planned restart loses nothing.
     *
     * <p>Every step is safe when no store is present — {@link #checkpoint()} is then a no-op and the
     * checkpointer was never started.
     */
    public void shutdown(Duration grace) throws InterruptedException {
        if (checkpointer != null) {
            checkpointer.shutdown();
            if (!checkpointer.awaitTermination(5, TimeUnit.SECONDS)) {
                checkpointer.shutdownNow();
            }
        }
        broadcaster.close();
        server.shutdown();
        if (!server.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            server.shutdownNow();
            server.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS);
        }
        checkpoint();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "reputation-pool-checkpointer");
            thread.setDaemon(true);
            return thread;
        };
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        String url = System.getenv(ENV_DB_URL);
        AdvisorServer advisor;
        PostgresAuditTrail auditTrail;
        if (url != null && !url.isBlank()) {
            LOG.log(Level.INFO, "starting in persistent mode (store backed by {0})", url);
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setUrl(url);
            dataSource.setUser(System.getenv(ENV_DB_USERNAME));
            dataSource.setPassword(System.getenv(ENV_DB_PASSWORD));
            // Flyway brings the schema up to date before the store touches any table.
            Flyway.configure().dataSource(dataSource).load().migrate();
            auditTrail = new PostgresAuditTrail(dataSource);
            advisor = AdvisorServer.create(
                    port,
                    Clock.systemUTC(),
                    RandomGenerator.getDefault(),
                    DEFAULT_LEASE_TTL,
                    new PostgresResourceStore(dataSource),
                    auditTrail);
        } else {
            LOG.log(Level.INFO, "starting in in-memory mode (no {0} set; state will not survive restart)", ENV_DB_URL);
            auditTrail = null;
            advisor = AdvisorServer.create(port);
        }
        advisor.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                advisor.shutdown(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                // Closed after the server has drained, so the trail's tail — including events emitted
                // by the very last RPCs — is flushed before the process exits.
                if (auditTrail != null) {
                    auditTrail.close();
                }
            }
        }));
        advisor.awaitTermination();
    }
}
