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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ReputationCell;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.port.EventSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * {@link RandomGenerator} for selection, and pushes {@link PoolEvent}s to the {@link EventSink} port.
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
    private final Clock clock;
    private final RandomGenerator random;
    private final Duration leaseTtl;

    /**
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
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
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
     * already leased, and in a selectable state ({@code HEALTHY} or {@code RECOVERING}), chosen by the
     * strategy and weighted by reputation. Emits {@link PoolEvent.ResourceLeased} on success.
     *
     * @param context the context to lease for
     * @return the granted lease, or {@link Optional#empty()} if nothing is available
     * @throws NullPointerException if {@code context} is null
     */
    public Optional<Lease> acquire(Context context) {
        Objects.requireNonNull(context, "context must not be null");
        Instant now = clock.instant();
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
            if (isSelectable(cell.state())) {
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
                events.emit(new PoolEvent.ResourceLeased(
                        chosen, context, now, lease.get().expiresAt()));
                return lease;
            }
            candidates.removeIf(candidate -> candidate.resourceId().equals(chosen)); // lost the race; try the next
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
        return leases.renew(lease.resource(), lease.token(), clock.instant(), leaseTtl);
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
            events.emit(new PoolEvent.LeaseReleased(lease.resource(), lease.context(), clock.instant()));
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
        blocklist.updateAndGet(current -> current.block(resource, until));
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
        blocklist.updateAndGet(current -> current.blockPermanently(resource));
        events.emit(new PoolEvent.ResourceBlocklisted(resource, clock.instant(), Instant.MAX));
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
        Blocklist previous = blocklist.getAndUpdate(current -> current.release(resource));
        if (previous.entries().containsKey(resource)) {
            events.emit(new PoolEvent.ResourceUnblocked(resource, clock.instant()));
        }
    }

    private static boolean isSelectable(ResourceState state) {
        return state == ResourceState.HEALTHY || state == ResourceState.RECOVERING;
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "duration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }

    /** The identity of a reputation cell: one per {@code (resource × context)} pair. */
    private record CellKey(ResourceId resource, Context context) {}
}
