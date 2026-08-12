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
package io.github.preagile.reputationpool.core.pool;

import io.github.preagile.reputationpool.core.domain.Blocklist;
import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.core.port.MetricsSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;

/**
 * The pool's single entry point and aggregate root: it composes the blocklist, selection strategy,
 * lease registry, and reputation engine into the four operations a caller sees — {@link #acquire},
 * {@link #report}, {@link #renew}, {@link #release} — plus resource registration and manual
 * blocklisting.
 *
 * <p>Everything beneath it was built pure or self-contained so it could be tested in isolation; this
 * facade is where they connect and where side effects enter. It is the one place that reads the
 * {@link Clock} (deriving the {@code now} it passes down), draws from the injected
 * {@link RandomGenerator} for selection, pushes {@link PoolEvent}s to the {@link EventSink} port, and
 * reports acquisition latency and lease occupancy to the {@link MetricsSink} port. The metrics port is
 * the continuous-measurement sibling of the discrete {@link EventSink}; both default to a no-op so an
 * assembly can leave either unwired.
 *
 * <p>It owns the layer's three pieces of shared state, each with its own atomic discipline: the
 * reputation cells in a {@link ConcurrentHashMap} updated by per-key {@code compute}; the blocklist
 * in an {@link AtomicReference} swapped by compare-and-set; and the leases in a {@link LeaseRegistry}.
 * {@link #acquire} reads the three gates then claims a lease atomically, retrying the next candidate
 * if it loses the claim race.
 *
 * <p>Selection currently ranks by a resource's per-context score; the two-layer effective score
 * ({@code globalBase + contextDelta}) is a later refinement.
 */
public final class ResourcePool {

    private final ConcurrentHashMap<CellKey, ReputationCell> cells = new ConcurrentHashMap<>();
    private final AtomicReference<Blocklist> blocklist = new AtomicReference<>(Blocklist.empty());
    private final LeaseRegistry leases = new LeaseRegistry();
    private final Set<ResourceId> registered = ConcurrentHashMap.newKeySet();

    private final ReputationEngine engine;
    private final SelectionStrategy strategy;
    private final EventSink events;
    private final MetricsSink metrics;
    private final Clock clock;
    private final RandomGenerator random;
    private final Duration leaseTtl;

    /**
     * Assembles a pool that reports events but no metrics — the metrics port defaults to
     * {@link MetricsSink#noop()}, so an existing assembly keeps working without wiring it.
     *
     * @param engine the reputation decision function
     * @param strategy how to choose among eligible candidates
     * @param events where pool events are emitted
     * @param clock the source of {@code now}; use {@code Clock.fixed(...)} in tests
     * @param random the source of randomness for selection; seed it in tests for reproducibility
     * @param leaseTtl how long a granted lease stays valid before the expiry safety net reclaims it
     * @throws NullPointerException if any reference argument is null
     * @throws IllegalArgumentException if {@code leaseTtl} is zero or negative
     */
    public ResourcePool(
            ReputationEngine engine,
            SelectionStrategy strategy,
            EventSink events,
            Clock clock,
            RandomGenerator random,
            Duration leaseTtl) {
        this(engine, strategy, events, MetricsSink.noop(), clock, random, leaseTtl);
    }

    /**
     * Assembles a pool that reports both events and metrics.
     *
     * @param engine the reputation decision function
     * @param strategy how to choose among eligible candidates
     * @param events where pool events are emitted
     * @param metrics where acquisition latency and lease occupancy are reported; use
     *     {@link MetricsSink#noop()} to discard them
     * @param clock the source of {@code now}; use {@code Clock.fixed(...)} in tests
     * @param random the source of randomness for selection; seed it in tests for reproducibility
     * @param leaseTtl how long a granted lease stays valid before the expiry safety net reclaims it
     * @throws NullPointerException if any reference argument is null
     * @throws IllegalArgumentException if {@code leaseTtl} is zero or negative
     */
    public ResourcePool(
            ReputationEngine engine,
            SelectionStrategy strategy,
            EventSink events,
            MetricsSink metrics,
            Clock clock,
            RandomGenerator random,
            Duration leaseTtl) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        requirePositive(leaseTtl);
        this.leaseTtl = leaseTtl;
    }

    /**
     * Adds a resource to the pool's candidate pool. Idempotent.
     *
     * @param resource the resource to make eligible for {@link #acquire}
     * @throws NullPointerException if {@code resource} is null
     */
    public void register(ResourceId resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        registered.add(resource);
    }

    /**
     * Leases one registered resource for {@code context}: a resource that is not blocklisted, not
     * already leased, and in a selectable state ({@code HEALTHY} or {@code RECOVERING}, plus the
     * half-open case described on {@link #isSelectable}), chosen by the strategy and weighted by
     * reputation. Emits {@link PoolEvent.ResourceLeased} on success and
     * {@link PoolEvent.AcquisitionRejected} when nothing is available, and reports the call's latency
     * and the resulting lease occupancy to the {@link MetricsSink}.
     *
     * @param context the context to lease for
     * @return the granted lease, or {@link Optional#empty()} if nothing is available
     * @throws NullPointerException if {@code context} is null
     */
    public Optional<Lease> acquire(Context context) {
        Objects.requireNonNull(context, "context must not be null");
        Instant startedAt = clock.instant();
        Optional<Lease> lease = claim(context, startedAt);
        // Only touch the metrics path when a sink actually records: leaseOccupancy's argument is an
        // O(active-leases) scan, and the default noop() sink (what every existing assembly gets) must not
        // make each acquire pay it. The rejection below is an event, not a metric, so it always fires.
        if (metrics.isEnabled()) {
            // Latency is measured on the same injected clock the rest of the pool reads, not on a
            // separate wall-clock source, so tests drive it deterministically; clamp at zero so a
            // non-monotonic clock stepping back never reports a negative duration.
            long latencyNanos =
                    Math.max(0L, Duration.between(startedAt, clock.instant()).toNanos());
            metrics.acquisitionLatency(latencyNanos);
        }
        if (lease.isEmpty()) {
            events.emit(new PoolEvent.AcquisitionRejected(context, startedAt));
        }
        if (metrics.isEnabled()) {
            metrics.leaseOccupancy(leases.activeCount(startedAt), registered.size());
        }
        return lease;
    }

    /** The selection-and-claim core of {@link #acquire}, returning the lease or empty at {@code now}. */
    private Optional<Lease> claim(Context context, Instant now) {
        Blocklist currentBlocklist = blocklist.get();

        var candidates = new ArrayList<ReputationCell>();
        for (ResourceId id : registered) {
            if (currentBlocklist.isBlocked(id, now) || leases.isLeased(id, now)) {
                continue;
            }
            ReputationCell cell = cells.get(new CellKey(id, context));
            if (cell == null) {
                cell = ReputationCell.fresh(id, context, now);
            }
            if (isSelectable(cell, now)) {
                candidates.add(cell);
            }
        }

        while (!candidates.isEmpty()) {
            Optional<ReputationCell> pick = strategy.select(candidates, random);
            if (pick.isEmpty()) {
                return Optional.empty();
            }
            ResourceId chosen = pick.get().resourceId();
            Optional<Lease> lease = leases.tryAcquire(chosen, context, now, leaseTtl);
            if (lease.isPresent()) {
                if (blocklist.get().isBlocked(chosen, now)) {
                    // blocklisted after our snapshot but before the claim: undo it, so a block() that has
                    // already returned can never be bypassed by an in-flight acquire
                    leases.release(chosen, lease.get().token());
                } else {
                    events.emit(new PoolEvent.ResourceLeased(
                            chosen, context, now, lease.get().expiresAt()));
                    return lease;
                }
            }
            candidates.removeIf(
                    candidate -> candidate.resourceId().equals(chosen)); // lost the race or just blocked; try the next
        }
        return Optional.empty();
    }

    /**
     * Records the outcome of using a resource in a context, advancing its reputation through the
     * engine and emitting any events the transition produces. Creates a fresh cell on first use.
     *
     * @param resource the resource that was used
     * @param context the context it was used in
     * @param outcome the result of the use
     * @throws NullPointerException if any argument is null
     */
    public void report(ResourceId resource, Context context, Outcome outcome) {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Instant now = clock.instant();
        List<PoolEvent> produced = new ArrayList<>();
        cells.compute(new CellKey(resource, context), (key, cell) -> {
            ReputationCell current = (cell != null) ? cell : ReputationCell.fresh(resource, context, now);
            ReputationEngine.Result result = engine.apply(current, outcome, now);
            produced.addAll(result.events());
            return result.cell();
        });
        produced.forEach(events::emit); // emit outside the compute's lock
    }

    /**
     * Extends a lease the caller still holds, using this pool's TTL.
     *
     * @param lease the lease to extend
     * @return the extended lease, or {@link Optional#empty()} if it could not be renewed
     * @throws NullPointerException if {@code lease} is null
     */
    public Optional<Lease> renew(Lease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        Instant now = clock.instant();
        if (blocklist.get().isBlocked(lease.resource(), now)) {
            return Optional.empty(); // a blocklisted resource cannot be kept alive; let the lease lapse
        }
        return leases.renew(lease.resource(), lease.token(), now, leaseTtl);
    }

    /**
     * Returns a leased resource to the pool. Emits {@link PoolEvent.LeaseReleased} if a lease was
     * actually released.
     *
     * @param lease the lease to release
     * @return whether a lease held under the caller's token was released
     * @throws NullPointerException if {@code lease} is null
     */
    public boolean release(Lease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        boolean released = leases.release(lease.resource(), lease.token());
        if (released) {
            Instant now = clock.instant();
            events.emit(new PoolEvent.LeaseReleased(lease.resource(), lease.context(), now));
            // a release is the other lease transition (an acquire is the first), so resample the gauge —
            // guarded so the default noop() sink never pays the activeCount() scan
            if (metrics.isEnabled()) {
                metrics.leaseOccupancy(leases.activeCount(now), registered.size());
            }
        }
        return released;
    }

    /**
     * Blocklists a resource for {@code duration}, isolating it from selection everywhere. Emits
     * {@link PoolEvent.ResourceBlocklisted}.
     *
     * @param resource the resource to isolate
     * @param duration how long the block lasts
     * @throws NullPointerException if {@code resource} or {@code duration} is null
     * @throws IllegalArgumentException if {@code duration} is zero or negative
     */
    public void block(ResourceId resource, Duration duration) {
        Objects.requireNonNull(resource, "resource must not be null");
        requirePositive(duration);
        Instant now = clock.instant();
        Instant until = now.plus(duration);
        blocklist.updateAndGet(current -> current.sweepExpired(now).block(resource, until));
        events.emit(new PoolEvent.ResourceBlocklisted(resource, now, until));
    }

    /**
     * Blocklists a resource with no expiry, released only by {@link #unblock}. Emits
     * {@link PoolEvent.ResourceBlocklisted} with {@link Instant#MAX} as the sentinel expiry.
     *
     * @param resource the resource to isolate permanently
     * @throws NullPointerException if {@code resource} is null
     */
    public void blockPermanently(ResourceId resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        Instant now = clock.instant();
        blocklist.updateAndGet(current -> current.sweepExpired(now).blockPermanently(resource));
        events.emit(new PoolEvent.ResourceBlocklisted(resource, now, Instant.MAX));
    }

    /**
     * Releases a resource from the blocklist. Emits {@link PoolEvent.ResourceUnblocked} only if the
     * resource was actually blocklisted.
     *
     * @param resource the resource to release from isolation
     * @throws NullPointerException if {@code resource} is null
     */
    public void unblock(ResourceId resource) {
        Objects.requireNonNull(resource, "resource must not be null");
        Instant now = clock.instant();
        Blocklist previous =
                blocklist.getAndUpdate(current -> current.sweepExpired(now).release(resource));
        if (previous.entries().containsKey(resource)) {
            events.emit(new PoolEvent.ResourceUnblocked(resource, now));
        }
    }

    /**
     * Captures the pool's whole durable state — cells, blocklist, and registered resources — as one
     * immutable {@link PoolSnapshot} for a {@link io.github.preagile.reputationpool.core.port.ResourceStore}
     * to persist. Leases are runtime coordination and are intentionally not included.
     *
     * <p>Each of the three structures is read with its own atomic discipline, so the snapshot is
     * internally consistent per field; it does not freeze the whole pool, so an operation racing with
     * this call may land in this snapshot or the next checkpoint. That is fine for a periodic
     * checkpoint — the missed change is captured next time.
     *
     * @return the current durable state
     */
    public PoolSnapshot snapshot() {
        return new PoolSnapshot(Map.copyOf(cells), blocklist.get(), Set.copyOf(registered));
    }

    /**
     * Loads a previously captured {@link PoolSnapshot} into this pool, rehydrating cells, blocklist,
     * and registered resources. Leases are not restored: nothing is held immediately after a restart.
     *
     * <p>Intended to be called <strong>once at startup, before the pool serves any traffic</strong>.
     * It is not safe against concurrent operations — it writes the three structures without a global
     * lock — but it now replaces all durable state with the snapshot rather than merging into it, so
     * running it on a non-empty pool leaves the pool as exactly the snapshot instead of corrupting it.
     *
     * @param snapshot the durable state to load
     * @throws NullPointerException if {@code snapshot} is null
     */
    public void restore(PoolSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        cells.clear();
        cells.putAll(snapshot.cells());
        blocklist.set(snapshot.blocklist());
        registered.clear();
        registered.addAll(snapshot.registered());
    }

    /**
     * The {@code (resource, context)} cells stuck in {@code COOLING} past their own cooldown — the
     * mirror image of {@link #isSelectable}'s filter in {@link #claim}. {@code acquire} never selects
     * a {@code COOLING} cell, so once {@code cooldownUntil} has passed there is no lease-driven
     * traffic left to report a success and let it probate into {@code RECOVERING}: without an outer
     * component calling {@link #report} for it directly, it would stay {@code COOLING} forever.
     *
     * <p>This is a read-only query, not a decision: it names candidates, it does not act on them. A
     * candidate already blocklisted or currently leased (by another context — see {@link LeaseRegistry})
     * is excluded, mirroring the two guards {@link #claim} applies before checking selectability —
     * probing a resource real traffic is using right now, or one an operator has explicitly isolated,
     * would defeat both.
     *
     * <p>A cell whose latest failure is a {@link FailureType#BLOCKED} is excluded too, and for a
     * different reason: {@link #isSelectable} admits it as a half-open trial instead. The split is
     * deliberate and total — a synthetic probe owns the four transport failures it can actually
     * measure, half-open owns the one failure that lives in the site rather than the resource. Both
     * sides read the same {@link #lastFailureWasSiteBlock} predicate off the same cell, so no cell is
     * ever both and the two mechanisms never race to move the same cell out of {@code COOLING}.
     *
     * @param now the instant to evaluate cooldown expiry against
     * @return the due candidates, in no particular order; empty if none are due
     * @throws NullPointerException if {@code now} is null
     */
    public List<ProbeCandidate> dueForRecoveryProbe(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        Blocklist currentBlocklist = blocklist.get();
        var due = new ArrayList<ProbeCandidate>();
        for (ReputationCell cell : cells.values()) {
            if (cell.state() != ResourceState.COOLING || now.isBefore(cell.cooldownUntil())) {
                continue;
            }
            if (lastFailureWasSiteBlock(cell)) {
                continue; // half-open admission owns this one; see isSelectable
            }
            if (currentBlocklist.isBlocked(cell.resourceId(), now) || leases.isLeased(cell.resourceId(), now)) {
                continue;
            }
            due.add(new ProbeCandidate(cell.resourceId(), cell.context(), cell.cooldownUntil()));
        }
        return List.copyOf(due);
    }

    /**
     * The selection gate: {@code HEALTHY} and {@code RECOVERING} always, plus the half-open case —
     * a {@code COOLING} cell whose cooldown has elapsed and whose latest failure was a
     * {@link FailureType#BLOCKED}, which earns one trial request.
     *
     * <p>A block lives in the relationship between this resource and one specific site, not in the
     * resource itself, so a synthetic probe against a neutral target cannot judge it: a {@code 200}
     * from an unrelated URL says nothing about a site that is still refusing us. Real traffic is the
     * only signal with full fidelity — whatever the workload does to get blocked is exactly what gets
     * retried. That is the classic circuit-breaker half-open state, and the blast radius is already
     * bounded by two guards that exist for other reasons: {@link #claim} skips any resource with a
     * live lease, so at most one trial is ever in flight, and a cooled cell holds the lowest score
     * among candidates, so {@link WeightedRandomSelectionStrategy} gives it only the exploration
     * floor. Should the trial fail, {@code ReputationEngine}'s cooling guard has already lapsed with
     * the cooldown, so it re-cools at the next step of the backoff curve.
     */
    private static boolean isSelectable(ReputationCell cell, Instant now) {
        ResourceState state = cell.state();
        if (state == ResourceState.HEALTHY || state == ResourceState.RECOVERING) {
            return true;
        }
        return state == ResourceState.COOLING && !now.isBefore(cell.cooldownUntil()) && lastFailureWasSiteBlock(cell);
    }

    /**
     * Whether the most recent failure this cell has seen is a {@link FailureType#BLOCKED}, read back
     * from the outcome window the cell already carries rather than from a new field — nothing to
     * migrate, nothing to keep in sync with the engine. Successes are skipped over, which is what the
     * backwards scan is for.
     *
     * <p><b>The latest failure, not strictly the one that set {@code cooldownUntil}.</b> Usually they
     * are the same, but a failure arriving while the cell is already cooling is still appended to the
     * window even though {@code ReputationEngine}'s "already being punished for this incident" guard
     * keeps it from restarting the cooldown — so a late report can change the answer mid-cooldown, in
     * either direction. Outcomes carry no timestamp, so the window cannot distinguish the two; telling
     * them apart would need a cause field on the cell, and the freshest failure is the better signal
     * anyway. Both directions are self-correcting within one trial: a block observed during a
     * transport cooldown gets its half-open trial early (on the shorter cooldown the transport failure
     * sized), and if the site is still refusing us that trial fails {@code BLOCKED} past the cooldown,
     * so the engine re-cools it on the block's own much longer curve; a transport failure observed
     * during a block's cooldown hands the cell to the prober, whose synthetic success at worst buys
     * one round of real traffic that re-cools it. What does not vary is the partition: both callers
     * ask this same question of the same cell, so no cell is ever claimed by both mechanisms.
     *
     * <p>A window holding no failure at all — a freshly registered cell, or a cooled one whose
     * causing failure has since been pushed out by later outcomes — reads as "not a block", the
     * conservative answer: it leaves the cell on the prober's side of the split instead of admitting
     * real traffic on a guess.
     */
    private static boolean lastFailureWasSiteBlock(ReputationCell cell) {
        List<Outcome> window = cell.window();
        for (int i = window.size() - 1; i >= 0; i--) {
            if (window.get(i) instanceof Outcome.Failure failure) {
                return failure.type() == FailureType.BLOCKED;
            }
        }
        return false;
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }
}
