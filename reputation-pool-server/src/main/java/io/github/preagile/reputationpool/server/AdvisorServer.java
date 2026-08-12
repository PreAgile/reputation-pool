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

import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.core.port.ResourceStore;
import io.github.preagile.reputationpool.grpc.CompositeEventSink;
import io.github.preagile.reputationpool.grpc.EventBroadcaster;
import io.github.preagile.reputationpool.grpc.ReputationAdvisorService;
import io.github.preagile.reputationpool.persistence.PostgresAuditTrail;
import io.github.preagile.reputationpool.persistence.PostgresResourceStore;
import io.github.preagile.reputationpool.prober.RecoveryProbe;
import io.github.preagile.reputationpool.prober.RecoveryScheduler;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *
 * <p>When an {@link AuditRetention} is also supplied, the durable lifecycle gains one more chore: a
 * periodic purge that trims audit events older than the retention, riding the checkpointer's
 * scheduler with the same exception isolation. No retention means no purge task at all — the audit
 * trail then grows unbounded, exactly as before the knob existed.
 *
 * <p>When a {@code Map<ResourceKind, RecoveryProbe>} is supplied, the root also wires a
 * {@link RecoveryScheduler}: a {@code COOLING} resource is never offered by {@code acquire}, so
 * without this nothing lease-driven is left to report a success and let it probate out of
 * {@code COOLING} into {@code RECOVERING} (see {@code ResourcePool#dueForRecoveryProbe}). From
 * {@code RECOVERING} onward the resource is selectable again, so ordinary traffic — not further
 * probing — is what carries it the rest of the way to {@code HEALTHY}; the scheduler's job ends the
 * moment real traffic can take over. It sits in the same event fan-out as the broadcaster and audit
 * sink, and its periodic backstop sweep rides the same scheduler thread as the checkpoint. No probes
 * means no scheduler at all — recovery then stays exactly as unreachable as it always was.
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

    /** How often the retention task trims the audit trail, when retention is configured at all. */
    private static final Duration DEFAULT_AUDIT_PURGE_INTERVAL = Duration.ofHours(1);

    /**
     * How often the recovery backstop sweep runs, when recovery probes are configured at all. Chosen
     * to match {@link #DEFAULT_CHECKPOINT_INTERVAL} for now, not measured against real probe latency
     * or cooldown distributions — expect this to move once a real deployment's metrics exist to tune
     * it from (see issue #87).
     *
     * <p>It is also half the shortest cooldown this assembly can produce — {@code SLOW}'s 30s base at
     * the first cooling step ({@link #COOL_AFTER} = 2, so the curve doubles it) is 60s — which is why
     * 30s is a sane backstop and not an arbitrary number: a cell the event path misses waits at most
     * half a cooldown for the sweep to find it. Widening this widens that worst case for every cell the
     * fast path does not carry.
     */
    private static final Duration DEFAULT_RECOVERY_SWEEP_INTERVAL = Duration.ofSeconds(30);

    /** DB connection is env-driven; these are the variables {@link #main} reads. */
    private static final String ENV_DB_URL = "REPUTATION_POOL_DB_URL";

    private static final String ENV_DB_USERNAME = "REPUTATION_POOL_DB_USERNAME";
    private static final String ENV_DB_PASSWORD = "REPUTATION_POOL_DB_PASSWORD";

    /** Opt-in audit retention as an ISO-8601 duration (e.g. {@code P30D}); unset means never purge. */
    private static final String ENV_AUDIT_RETENTION = "REPUTATION_POOL_AUDIT_RETENTION";

    private final Server server;
    private final EventBroadcaster broadcaster;
    private final ResourcePool pool;
    private final Optional<ResourceStore> store;
    private final Duration checkpointInterval;
    private final Clock clock;
    private final Optional<AuditRetention> auditRetention;
    private final Optional<RecoveryScheduler> recoveryScheduler;

    /** Started lazily in {@link #start()} only when a store or a recovery scheduler is present; null otherwise. */
    private ScheduledExecutorService checkpointer;

    private AdvisorServer(
            Server server,
            EventBroadcaster broadcaster,
            ResourcePool pool,
            Optional<ResourceStore> store,
            Clock clock,
            Optional<AuditRetention> auditRetention,
            Optional<RecoveryScheduler> recoveryScheduler) {
        this.server = server;
        this.broadcaster = broadcaster;
        this.pool = pool;
        this.store = store;
        this.checkpointInterval = DEFAULT_CHECKPOINT_INTERVAL;
        this.clock = clock;
        this.auditRetention = auditRetention;
        this.recoveryScheduler = recoveryScheduler;
    }

    /** Production assembly: system clock, default randomness, the default lease TTL, no store. */
    public static AdvisorServer create(int port) {
        return create(port, Clock.systemUTC(), RandomGenerator.getDefault(), DEFAULT_LEASE_TTL);
    }

    /** Test-friendly assembly with no store: every source of nondeterminism is handed in by the caller. */
    public static AdvisorServer create(int port, Clock clock, RandomGenerator random, Duration leaseTtl) {
        return assemble(
                port, clock, random, leaseTtl, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
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
        return assemble(
                port,
                clock,
                random,
                leaseTtl,
                Optional.of(store),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
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
        return assemble(
                port,
                clock,
                random,
                leaseTtl,
                Optional.of(store),
                Optional.of(auditSink),
                Optional.empty(),
                Optional.empty());
    }

    /**
     * The fully durable assembly of the audit-sink overload, plus a bounded trail: {@code retention}
     * turns on the periodic purge that trims audit events older than {@code retention.maxAge()}. The
     * purge task rides the same scheduler as the checkpoint, computes its cutoff from the injected
     * {@code clock}, and is exception-isolated the same way — a failing purge is logged and retried on
     * the next interval, never fatal. Without this overload (no retention configured) nothing is ever
     * purged: bounding the trail is strictly opt-in.
     *
     * @param store the durable store to restore from and checkpoint to; never null
     * @param auditSink the second consumer of every pool event; never null
     * @param retention how much audit history to keep and the purger that trims the rest; never null
     */
    public static AdvisorServer create(
            int port,
            Clock clock,
            RandomGenerator random,
            Duration leaseTtl,
            ResourceStore store,
            EventSink auditSink,
            AuditRetention retention) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(auditSink, "auditSink must not be null");
        Objects.requireNonNull(retention, "retention must not be null");
        return assemble(
                port,
                clock,
                random,
                leaseTtl,
                Optional.of(store),
                Optional.of(auditSink),
                Optional.of(retention),
                Optional.empty());
    }

    /**
     * The no-store assembly plus recovery probing: a {@link RecoveryScheduler} is wired into the event
     * fan-out and its backstop sweep rides the periodic scheduler, exactly as the store-aware overloads
     * ride it for checkpointing. Recovery does not require persistence — a resource still probates out
     * of {@code COOLING} in an in-memory pool the same way it would in a durable one.
     *
     * @param recoveryProbes how to actively test a resource by its kind; a kind absent from this map is
     *     never proactively probed (see {@code RecoveryScheduler}); never null
     */
    public static AdvisorServer create(
            int port,
            Clock clock,
            RandomGenerator random,
            Duration leaseTtl,
            Map<ResourceKind, RecoveryProbe> recoveryProbes) {
        Objects.requireNonNull(recoveryProbes, "recoveryProbes must not be null");
        return assemble(
                port,
                clock,
                random,
                leaseTtl,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(recoveryProbes));
    }

    /**
     * The fully durable assembly (store, audit sink, retention) plus recovery probing — every
     * capability this composition root offers, combined.
     *
     * @param recoveryProbes how to actively test a resource by its kind; a kind absent from this map is
     *     never proactively probed (see {@code RecoveryScheduler}); never null
     */
    public static AdvisorServer create(
            int port,
            Clock clock,
            RandomGenerator random,
            Duration leaseTtl,
            ResourceStore store,
            EventSink auditSink,
            AuditRetention retention,
            Map<ResourceKind, RecoveryProbe> recoveryProbes) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(auditSink, "auditSink must not be null");
        Objects.requireNonNull(retention, "retention must not be null");
        Objects.requireNonNull(recoveryProbes, "recoveryProbes must not be null");
        return assemble(
                port,
                clock,
                random,
                leaseTtl,
                Optional.of(store),
                Optional.of(auditSink),
                Optional.of(retention),
                Optional.of(recoveryProbes));
    }

    /** The single assembler every overload family routes through. */
    private static AdvisorServer assemble(
            int port,
            Clock clock,
            RandomGenerator random,
            Duration leaseTtl,
            Optional<ResourceStore> store,
            Optional<EventSink> auditSink,
            Optional<AuditRetention> auditRetention,
            Optional<Map<ResourceKind, RecoveryProbe>> recoveryProbes) {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(random, "random must not be null");
        Objects.requireNonNull(leaseTtl, "leaseTtl must not be null");
        EventBroadcaster broadcaster = new EventBroadcaster();

        // RecoveryScheduler needs the pool it will call report() back into, but the pool needs its
        // EventSink (which the scheduler joins) before it can be constructed — a genuine cycle, not an
        // ordering oversight. A one-element forward reference breaks it: the sink below only starts
        // forwarding once schedulerHolder[0] is set, which happens right after `pool` exists.
        var schedulerHolder = new RecoveryScheduler[1];
        var sinks = new ArrayList<EventSink>();
        sinks.add(broadcaster);
        auditSink.ifPresent(sinks::add);
        recoveryProbes.ifPresent(ignored -> sinks.add(event -> {
            RecoveryScheduler scheduler = schedulerHolder[0];
            if (scheduler != null) {
                scheduler.emit(event);
            }
        }));
        EventSink poolSink = sinks.size() == 1 ? sinks.get(0) : new CompositeEventSink(List.copyOf(sinks));

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

        Optional<RecoveryScheduler> recoveryScheduler =
                recoveryProbes.map(probes -> new RecoveryScheduler(pool, probes, clock, random));
        recoveryScheduler.ifPresent(scheduler -> schedulerHolder[0] = scheduler);

        Server server = ServerBuilder.forPort(port)
                .addService(new ReputationAdvisorService(pool, broadcaster))
                .build();
        return new AdvisorServer(server, broadcaster, pool, store, clock, auditRetention, recoveryScheduler);
    }

    public AdvisorServer start() throws IOException {
        server.start();
        if (store.isPresent() || recoveryScheduler.isPresent()) {
            checkpointer = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
            scheduleLifecycleTasks(checkpointer);
        }
        return this;
    }

    /**
     * Puts the periodic lifecycle chores on {@code scheduler}: always the checkpoint (a no-op without
     * a store), the audit purge when retention is configured, and the recovery backstop sweep when
     * recovery probes are configured — each chore already guards its own precondition internally, so
     * scheduling all three unconditionally costs an absent one nothing but an idle tick. Package-private
     * so tests can hand in a recording scheduler and verify exactly what was scheduled (and run it),
     * with no scheduler timing; {@link #start()} hands in the real checkpointer executor.
     */
    void scheduleLifecycleTasks(ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(
                this::checkpoint, checkpointInterval.toMillis(), checkpointInterval.toMillis(), TimeUnit.MILLISECONDS);
        if (auditRetention.isPresent()) {
            scheduler.scheduleAtFixedRate(
                    this::purgeExpiredAuditEvents,
                    DEFAULT_AUDIT_PURGE_INTERVAL.toMillis(),
                    DEFAULT_AUDIT_PURGE_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
        if (recoveryScheduler.isPresent()) {
            scheduler.scheduleAtFixedRate(
                    this::recoveryBackstopSweep,
                    DEFAULT_RECOVERY_SWEEP_INTERVAL.toMillis(),
                    DEFAULT_RECOVERY_SWEEP_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
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
     * Trims the audit trail down to the configured retention, if any: everything older than
     * {@code clock.instant() - maxAge} is purged. Exception-isolated exactly like {@link #checkpoint()}
     * and for the same reason — {@code scheduleAtFixedRate} cancels all future runs the first time its
     * task throws, so a purge that let a transient DB error escape would silently end retention for the
     * rest of the process's life. Swallowing means one bad purge is skipped, not fatal. Extracted as a
     * method so tests can trigger a purge directly, with no scheduler timing.
     *
     * <p>With no retention configured this is a no-op — the trail then grows unbounded, exactly as it
     * did before the knob existed.
     */
    void purgeExpiredAuditEvents() {
        auditRetention.ifPresent(retention -> {
            try {
                long purged = retention.purger().purgeOlderThan(clock.instant().minus(retention.maxAge()));
                if (purged > 0) {
                    LOG.log(Level.INFO, "audit retention purged {0} events older than {1}", purged, retention.maxAge());
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "audit purge failed; will retry on the next interval", e);
            }
        });
    }

    /**
     * Probes every {@code COOLING} candidate {@code ResourcePool#dueForRecoveryProbe} reports due right
     * now, when recovery probes are configured at all. {@link RecoveryScheduler#backstopSweep} is
     * already exception-isolated per candidate for the same reason {@link #checkpoint()} is —
     * {@code scheduleAtFixedRate} cancels all future runs the first time its task throws.
     *
     * <p>With no recovery probes configured this is a no-op.
     */
    void recoveryBackstopSweep() {
        recoveryScheduler.ifPresent(RecoveryScheduler::backstopSweep);
    }

    /**
     * Orderly shutdown that leaves a consistent final checkpoint. In order: (1) stop the periodic
     * checkpointer and await its termination so any in-flight periodic save finishes and is drained
     * before the final save — the two can then never overlap; (2) stop accepting new recovery probes
     * (in-flight ones are left to finish on their own, same as {@link RecoveryScheduler#close}
     * documents); (3) complete event streams so subscribers see a clean end instead of a transport
     * reset; (4) drain in-flight RPCs within the grace period so the pool's state is final (any reports
     * still arriving are applied first); (5) take one final checkpoint of that now-stable state, so a
     * planned restart loses nothing.
     *
     * <p>Every step is safe when no store is present — {@link #checkpoint()} is then a no-op and the
     * checkpointer was never started (unless recovery probes alone are what started it).
     */
    public void shutdown(Duration grace) throws InterruptedException {
        if (checkpointer != null) {
            checkpointer.shutdown();
            if (!checkpointer.awaitTermination(5, TimeUnit.SECONDS)) {
                checkpointer.shutdownNow();
            }
        }
        recoveryScheduler.ifPresent(RecoveryScheduler::close);
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
            // The reference server is a single-tenant host, so it owns the one "default" pool namespace
            // introduced by V3 — explicit here so the composition root names the pool it checkpoints to.
            PostgresResourceStore resourceStore = new PostgresResourceStore(dataSource, Clock.systemUTC(), "default");
            String retentionEnv = System.getenv(ENV_AUDIT_RETENTION);
            if (retentionEnv != null && !retentionEnv.isBlank()) {
                // Opt-in retention: the trail is bounded only when the operator says how much history
                // to keep (ISO-8601, e.g. P30D). Unset keeps the original never-purged behavior.
                AuditRetention retention =
                        new AuditRetention(Duration.parse(retentionEnv.trim()), auditTrail::purgeOlderThan);
                LOG.log(Level.INFO, "audit retention enabled: keeping {0} of history", retention.maxAge());
                advisor = AdvisorServer.create(
                        port,
                        Clock.systemUTC(),
                        RandomGenerator.getDefault(),
                        DEFAULT_LEASE_TTL,
                        resourceStore,
                        auditTrail,
                        retention);
            } else {
                advisor = AdvisorServer.create(
                        port,
                        Clock.systemUTC(),
                        RandomGenerator.getDefault(),
                        DEFAULT_LEASE_TTL,
                        resourceStore,
                        auditTrail);
            }
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
