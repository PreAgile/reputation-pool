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
package io.github.preagile.reputationpool.prober;

import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.pool.ProbeCandidate;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.port.EventSink;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * Closes the gap {@code acquire()} leaves open: a {@code COOLING} cell is never offered as a
 * candidate (see {@code ResourcePool#isSelectable}), so once its cooldown has passed there is no
 * lease-driven traffic left to report a success and let it probate into {@code RECOVERING}. This
 * class tests it directly instead — no lease, exactly the way {@code ResourcePool#report} already
 * allows — off two paths that are meant to run together, not as phases:
 *
 * <ul>
 *   <li><b>Event path (fast).</b> As an {@link EventSink}, it sits in a fan-out (e.g.
 *       {@code CompositeEventSink}) next to whatever else consumes {@link PoolEvent}s. On a
 *       {@link PoolEvent.ResourceCooled}, it schedules one probe at {@code until} (plus a small
 *       random jitter, so many resources cooling together do not all get probed in the same instant).
 *   <li><b>Backstop path (safety net).</b> {@link #backstopSweep()}, meant to be invoked periodically
 *       by the caller's own scheduler (the same one that already checkpoints), calls
 *       {@link ResourcePool#dueForRecoveryProbe} and probes anything the event path missed — a
 *       dropped event, or a restart between a {@code ResourceCooled} firing and its scheduled probe
 *       time. The event path alone would leave that gap; shipping only the backstop would just be
 *       slower to react. Both exist for a reason neither covers alone.
 * </ul>
 *
 * <p>A probe that comes back a failure flows through the normal {@code report()} path and re-cools
 * with whatever {@code CooldownPolicy} is configured — no separate backoff is invented here, so a
 * resource that keeps failing its probe is throttled by the same curve that punished it in the first
 * place.
 *
 * <p>Dispatch is by {@link ResourceKind}: a kind with no registered {@link RecoveryProbe} is simply
 * never proactively probed. Each dispatch runs on its own virtual thread (JEP 491 — a probe blocking
 * inside {@code synchronized} no longer pins a carrier), so many probes can be in flight without a
 * bounded pool to size. A {@code (resource, context)} already scheduled or running is never
 * double-dispatched.
 *
 * <p>Every failure mode is isolated: a probe that throws, or that fails to report, is logged and
 * dropped — never propagated to the emitting thread ({@link #emit}) or the sweeping caller
 * ({@link #backstopSweep}). {@link #close} stops accepting new work and lets in-flight probes finish.
 */
public final class RecoveryScheduler implements EventSink, AutoCloseable {

    /** Default upper bound on the random delay added before a probe fires, to avoid a thundering herd. */
    public static final Duration DEFAULT_MAX_JITTER = Duration.ofSeconds(5);

    private static final Logger LOG = System.getLogger(RecoveryScheduler.class.getName());

    private final ResourcePool pool;
    private final Map<ResourceKind, RecoveryProbe> probesByKind;
    private final Clock clock;
    private final RandomGenerator random;
    private final Duration maxJitter;
    private final Set<CellKey> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timers;
    private final ExecutorService probeRunners;

    /**
     * Creates a scheduler with {@link #DEFAULT_MAX_JITTER}.
     *
     * @param pool the pool to probe and report back into
     * @param probesByKind how to actually test a resource, by its kind; a kind absent from this map
     *     is never proactively probed
     * @param clock the source of {@code now}; use {@code Clock.fixed(...)} in tests
     * @param random the source of jitter; seed it in tests for reproducibility
     * @throws NullPointerException if any argument is null
     */
    public RecoveryScheduler(
            ResourcePool pool, Map<ResourceKind, RecoveryProbe> probesByKind, Clock clock, RandomGenerator random) {
        this(pool, probesByKind, clock, random, DEFAULT_MAX_JITTER);
    }

    /**
     * Creates a scheduler with a configurable jitter bound.
     *
     * @param pool the pool to probe and report back into
     * @param probesByKind how to actually test a resource, by its kind; a kind absent from this map
     *     is never proactively probed
     * @param clock the source of {@code now}; use {@code Clock.fixed(...)} in tests
     * @param random the source of jitter; seed it in tests for reproducibility
     * @param maxJitter the upper bound of the random delay added before a probe fires; {@link Duration#ZERO}
     *     disables jitter (every probe fires exactly at its due instant)
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code maxJitter} is negative
     */
    public RecoveryScheduler(
            ResourcePool pool,
            Map<ResourceKind, RecoveryProbe> probesByKind,
            Clock clock,
            RandomGenerator random,
            Duration maxJitter) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.probesByKind = Map.copyOf(Objects.requireNonNull(probesByKind, "probesByKind must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        Objects.requireNonNull(maxJitter, "maxJitter must not be null");
        if (maxJitter.isNegative()) {
            throw new IllegalArgumentException("maxJitter must not be negative");
        }
        this.maxJitter = maxJitter;
        this.timers = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("recovery-scheduler-timer"));
        this.probeRunners = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * The event-path trigger: on {@link PoolEvent.ResourceCooled}, schedules a probe at {@code until}.
     * Every other event kind is ignored — this sink only cares about the moment a cell starts cooling.
     *
     * @param event the fact just emitted by the pool; never null
     */
    @Override
    public void emit(PoolEvent event) {
        if (event instanceof PoolEvent.ResourceCooled cooled) {
            scheduleAt(cooled.resource(), cooled.context(), cooled.until());
        }
    }

    /**
     * The backstop path: probes every candidate {@link ResourcePool#dueForRecoveryProbe} reports due
     * right now. Meant to be called periodically by the caller's own scheduler — this method does not
     * loop or sleep itself.
     */
    public void backstopSweep() {
        Instant now = clock.instant();
        for (ProbeCandidate candidate : pool.dueForRecoveryProbe(now)) {
            scheduleAt(candidate.resource(), candidate.context(), now);
        }
    }

    /** Schedules one probe at {@code at} (plus jitter), unless one is already pending for this cell. */
    private void scheduleAt(ResourceId resource, Context context, Instant at) {
        CellKey key = new CellKey(resource, context);
        if (!pending.add(key)) {
            return; // already scheduled or in flight for this (resource, context)
        }
        long delayNanos = Math.max(0L, Duration.between(clock.instant(), at).toNanos());
        long jitterNanos = maxJitter.isZero() ? 0L : (long) (random.nextDouble() * maxJitter.toNanos());
        try {
            timers.schedule(() -> dispatch(key), delayNanos + jitterNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            // Only reachable once close() has shut the timer down (RejectedExecutionException); the
            // scheduler is going away, so drop the key rather than leak it forever.
            pending.remove(key);
        }
    }

    /** Runs the probe for {@code key} on a fresh virtual thread, then frees the key regardless of outcome. */
    private void dispatch(CellKey key) {
        probeRunners.execute(() -> {
            try {
                runProbe(key.resource(), key.context());
            } finally {
                pending.remove(key);
            }
        });
    }

    private void runProbe(ResourceId resource, Context context) {
        RecoveryProbe probe = probesByKind.get(resource.kind());
        if (probe == null) {
            return; // this resource kind has no active probe; leave it to the next lease-driven attempt
        }
        try {
            probe.test(resource, context).ifPresent(outcome -> pool.report(resource, context, outcome));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "recovery probe failed for " + resource + " in " + context, e);
        }
    }

    /**
     * Stops accepting new scheduled or backstop-triggered probes. In-flight probes are allowed to
     * finish; this does not block waiting for them.
     */
    @Override
    public void close() {
        timers.shutdownNow();
        probeRunners.shutdown();
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
