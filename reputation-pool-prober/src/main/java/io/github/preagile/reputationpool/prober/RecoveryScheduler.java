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
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.pool.Lease;
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
import java.util.Optional;
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
 * class tests it directly instead, off two paths that are meant to run together, not as phases:
 *
 * <ul>
 *   <li><b>Event path (fast).</b> As an {@link EventSink}, it sits in a fan-out (e.g.
 *       {@code CompositeEventSink}) next to whatever else consumes {@link PoolEvent}s. On a
 *       {@link PoolEvent.ResourceCooled} whose cause is not a {@link FailureType#BLOCKED}, it
 *       schedules one probe at {@code until} (plus a small random jitter, so many resources cooling
 *       together do not all get probed in the same instant).
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
 * place. The re-cool travels straight back into {@link #emit} on the reporting thread, which is why
 * the order at the end of a probe is load-bearing (see {@link #dispatch}): both guards a probe holds —
 * its lease and its dedupe key — are released <em>before</em> the outcome is reported, so the fast
 * path re-arms for the next attempt instead of every repeat failure falling to the backstop (#93).
 *
 * <p>Dispatch is by {@link ResourceKind}: a kind with no registered {@link RecoveryProbe} is simply
 * never proactively probed. Each dispatch runs on its own virtual thread (JEP 491 — a probe blocking
 * inside {@code synchronized} no longer pins a carrier), so many probes can be in flight without a
 * bounded pool to size. A {@code (resource, context)} already scheduled or running is never
 * double-dispatched.
 *
 * <p>A probe owns the resource while it runs: the claim is taken at dispatch time with
 * {@link ResourcePool#tryAcquireForProbe} and released the moment {@code test} returns, so real
 * traffic cannot lease the resource mid-probe (#102). Claiming at dispatch rather than when the
 * candidate is named is deliberate — the jitter delay below is time in which no probe is running, and
 * holding the resource across it would take it away from traffic for nothing. A probe that cannot take
 * the claim is skipped, not retried or logged as a failure: real traffic reached the resource first,
 * which is the better recovery signal anyway, and the backstop sweep re-offers the cell if it is still
 * stuck.
 *
 * <p>Every failure mode is isolated: a probe that throws, or that fails to report, is logged and
 * dropped — never propagated to the emitting thread ({@link #emit}) or the sweeping caller
 * ({@link #backstopSweep}). {@link #close} stops accepting new work and lets in-flight probes finish.
 */
public final class RecoveryScheduler implements EventSink, AutoCloseable {

    /** Default upper bound on the random delay added before a probe fires, to avoid a thundering herd. */
    public static final Duration DEFAULT_MAX_JITTER = Duration.ofSeconds(5);

    /**
     * Default TTL of the lease a probe holds while it runs. Sized from the probe's own budget, not from
     * the pool's lease TTL: it is comfortably above the 10s connect-and-response budget of the HTTP
     * proxy probe this repo ships (so a slow-but-alive probe is never preempted mid-flight) and half
     * the server's 30s default lease TTL, so a prober that dies mid-probe returns the resource to
     * traffic in about the time the probe itself would have taken rather than holding it for a full
     * lease. An assembly whose probes cost more than that should raise it explicitly — the TTL is the
     * expiry safety net for a dead prober, not the normal unlock, which is the release at the end of
     * every probe.
     */
    public static final Duration DEFAULT_PROBE_LEASE_TTL = Duration.ofSeconds(15);

    private static final Logger LOG = System.getLogger(RecoveryScheduler.class.getName());

    private final ResourcePool pool;
    private final Map<ResourceKind, RecoveryProbe> probesByKind;
    private final Clock clock;
    private final RandomGenerator random;
    private final Duration maxJitter;
    private final Duration probeLeaseTtl;
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
        this(pool, probesByKind, clock, random, maxJitter, DEFAULT_PROBE_LEASE_TTL);
    }

    /**
     * Creates a scheduler with a configurable jitter bound and probe lease TTL.
     *
     * @param pool the pool to probe and report back into
     * @param probesByKind how to actually test a resource, by its kind; a kind absent from this map
     *     is never proactively probed
     * @param clock the source of {@code now}; use {@code Clock.fixed(...)} in tests
     * @param random the source of jitter; seed it in tests for reproducibility
     * @param maxJitter the upper bound of the random delay added before a probe fires; {@link Duration#ZERO}
     *     disables jitter (every probe fires exactly at its due instant)
     * @param probeLeaseTtl how long a probe's claim on its resource survives a prober that dies without
     *     releasing it; size it from the probe's own budget (see {@link #DEFAULT_PROBE_LEASE_TTL})
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code maxJitter} is negative, or {@code probeLeaseTtl} is
     *     zero or negative
     */
    public RecoveryScheduler(
            ResourcePool pool,
            Map<ResourceKind, RecoveryProbe> probesByKind,
            Clock clock,
            RandomGenerator random,
            Duration maxJitter,
            Duration probeLeaseTtl) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.probesByKind = Map.copyOf(Objects.requireNonNull(probesByKind, "probesByKind must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        Objects.requireNonNull(maxJitter, "maxJitter must not be null");
        if (maxJitter.isNegative()) {
            throw new IllegalArgumentException("maxJitter must not be negative");
        }
        this.maxJitter = maxJitter;
        Objects.requireNonNull(probeLeaseTtl, "probeLeaseTtl must not be null");
        if (probeLeaseTtl.isZero() || probeLeaseTtl.isNegative()) {
            throw new IllegalArgumentException("probeLeaseTtl must be positive");
        }
        this.probeLeaseTtl = probeLeaseTtl;
        this.timers = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("recovery-scheduler-timer"));
        this.probeRunners = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * The event-path trigger: on {@link PoolEvent.ResourceCooled}, schedules a probe at {@code until}.
     * Every other event kind is ignored — this sink only cares about the moment a cell starts cooling.
     *
     * <p>A cooldown caused by a {@link FailureType#BLOCKED} is skipped, for the reason
     * {@link ResourcePool#dueForRecoveryProbe} skips it on the backstop path: a block lives in the
     * relationship between the resource and one site, so a synthetic request cannot judge it, and
     * half-open admission owns that cell instead. The two paths have to apply the same rule or the
     * partition is not a partition — this one only reaches the cell sooner. The event already carries
     * the cause, which is the same value the pool now stores on the cell (#97), so both paths are
     * reading the same fact rather than two approximations of it.
     *
     * @param event the fact just emitted by the pool; never null
     */
    @Override
    public void emit(PoolEvent event) {
        if (event instanceof PoolEvent.ResourceCooled cooled && cooled.cause() != FailureType.BLOCKED) {
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

    /**
     * Schedules one probe at {@code at} (plus jitter), unless one is already pending for this cell. The
     * key is what makes the two paths safe to run together: whichever reaches a cell first owns the
     * probe, the other returns. It covers a window, not all time — {@link #dispatch} frees it as soon
     * as the probe stops using the resource, so a probe's own re-cool can schedule the next attempt.
     */
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

    /**
     * Runs one probe for {@code key} on a fresh virtual thread, in the one order that keeps both of the
     * probe's guards from outliving their purpose:
     *
     * <pre>{@code claim the resource -> test() -> release the claim -> free the key -> report}</pre>
     *
     * <p>Once {@code test} has returned the probe is done using the resource, so holding either guard
     * past that point protects nothing and costs something. The claim would keep real traffic off a
     * resource nobody is probing. The key would swallow the probe's own consequences: {@code report}
     * fans its events out synchronously on this thread, so a failed probe's {@code ResourceCooled}
     * arrives back in {@link #emit} — and if the key were still held, {@link #scheduleAt}'s dedupe
     * would drop that re-schedule as "already in flight" and every retry after the first failure would
     * fall to the backstop sweep (#93).
     *
     * <p>The two are one change, not two: freeing the key first without a claim to release would
     * re-arm the fast path into a probe that then cannot take the claim the finishing probe still
     * holds, and get skipped again by a new route.
     *
     * <p>Both releases happen even when {@code test} throws, and the report is isolated too — a probe
     * that throws, or a report that does, is logged and dropped rather than propagated to the timer
     * thread that dispatched it.
     */
    private void dispatch(CellKey key) {
        probeRunners.execute(() -> {
            Optional<Outcome> outcome = Optional.empty();
            try {
                outcome = probeUnderClaim(key);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "recovery probe failed for " + key.resource() + " in " + key.context(), e);
            } finally {
                pending.remove(key);
            }
            try {
                outcome.ifPresent(o -> pool.report(key.resource(), key.context(), o));
            } catch (RuntimeException e) {
                LOG.log(
                        Level.WARNING,
                        "reporting the recovery probe outcome failed for " + key.resource() + " in " + key.context(),
                        e);
            }
        });
    }

    /**
     * Claims the resource, tests it, and releases the claim, returning the outcome the caller must
     * report once both guards are down. Empty means there is nothing to report: no probe registered for
     * this kind, the resource was not free to probe, or the probe itself declined.
     */
    private Optional<Outcome> probeUnderClaim(CellKey key) {
        ResourceId resource = key.resource();
        Context context = key.context();
        RecoveryProbe probe = probesByKind.get(resource.kind());
        if (probe == null) {
            return Optional
                    .empty(); // this resource kind has no active probe; leave it to the next lease-driven attempt
        }
        Optional<Lease> claim = pool.tryAcquireForProbe(resource, context, clock.instant(), probeLeaseTtl);
        if (claim.isEmpty()) {
            // Normal operation, not a failure: traffic holds the resource, an operator has isolated it,
            // or the cell has moved on since it was named. Logged at DEBUG because there is nothing to act on.
            LOG.log(
                    Level.DEBUG,
                    () -> "recovery probe skipped for " + resource + " in " + context
                            + ": the resource is not free to probe right now");
            return Optional.empty();
        }
        try {
            return probe.test(resource, context);
        } finally {
            pool.release(claim.get());
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
