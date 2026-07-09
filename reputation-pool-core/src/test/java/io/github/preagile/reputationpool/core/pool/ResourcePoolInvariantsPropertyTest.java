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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Stateful property test of the {@link ResourcePool} facade (coverage for issue #27): random
 * interleavings of every pool operation must never violate the pool's signature invariants —
 *
 * <ul>
 *   <li>a blocklisted resource is never lent,
 *   <li>a resource is never leased twice at the same instant,
 *   <li>a cooling resource is not lendable until its cooldown has expired and a success reported.
 * </ul>
 *
 * <p>The test drives the pool through a generated action sequence while maintaining a shadow model
 * of what must be true. Cooldown transitions are learned from the emitted {@link PoolEvent}s, never
 * from pool internals, and the model is deliberately permissive at boundaries so any assertion
 * failure is a real violation.
 */
class ResourcePoolInvariantsPropertyTest {

    private static final Context CTX = new Context("cpeats");
    private static final Instant T0 = Instant.parse("2026-07-08T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int RESOURCES = 4;

    private enum Op {
        ACQUIRE,
        RELEASE,
        RENEW,
        REPORT_SUCCESS,
        REPORT_BLOCKED,
        BLOCK,
        BLOCK_PERMANENTLY,
        UNBLOCK,
        ADVANCE_TIME
    }

    private record Step(Op op, int resource, int minutes) {}

    @Provide
    Arbitrary<List<Step>> actionSequences() {
        var ops = Arbitraries.of(Op.values());
        var resources = Arbitraries.integers().between(0, RESOURCES - 1);
        var minutes = Arbitraries.integers().between(1, 90);
        return Combinators.combine(ops, resources, minutes)
                .as(Step::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(80);
    }

    @Property
    void noInterleavingOfPoolOperationsViolatesTheInvariants(@ForAll("actionSequences") List<Step> steps) {
        var clock = new SettableClock(T0);
        var events = new ArrayList<PoolEvent>();
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        var pool = new ResourcePool(
                engine, new WeightedRandomSelectionStrategy(), events::add, clock, new Random(42), TTL);
        var ids = new ResourceId[RESOURCES];
        for (int i = 0; i < RESOURCES; i++) {
            ids[i] = new ResourceId(ResourceKind.PROXY, "p" + i);
            pool.register(ids[i]);
        }

        // the shadow model: what the pool has promised, tracked from return values and events only
        Map<ResourceId, Lease> held = new HashMap<>();
        Map<ResourceId, Instant> blockedUntil = new HashMap<>(); // Instant.MAX marks a permanent block
        Map<ResourceId, Instant> coolingUntil = new HashMap<>();
        Instant now = T0;
        int seenEvents = 0;

        for (Step step : steps) {
            ResourceId resource = ids[step.resource()];
            switch (step.op()) {
                case ACQUIRE -> {
                    final Instant at = now; // effectively-final copy for the lambda
                    held.values().removeIf(lease -> !at.isBefore(lease.expiresAt())); // TTL reclaims
                    var lease = pool.acquire(CTX);
                    if (lease.isPresent()) {
                        ResourceId lent = lease.get().resource();
                        Instant blocked = blockedUntil.get(lent);
                        assertThat(blocked == null || !now.isBefore(blocked))
                                .as("blocklisted %s was lent at %s (blocked until %s)", lent, now, blocked)
                                .isTrue();
                        assertThat(held)
                                .as("%s was lent while already leased", lent)
                                .doesNotContainKey(lent);
                        assertThat(coolingUntil)
                                .as("cooling %s was lent at %s", lent, now)
                                .doesNotContainKey(lent);
                        held.put(lent, lease.get());
                    }
                }
                case RELEASE -> {
                    var lease = held.remove(resource);
                    if (lease != null) {
                        pool.release(lease);
                    }
                }
                case RENEW -> {
                    var lease = held.get(resource);
                    if (lease != null) {
                        var renewed = pool.renew(lease);
                        if (renewed.isPresent()) {
                            held.put(resource, renewed.get());
                        } else if (!now.isBefore(lease.expiresAt())) {
                            held.remove(resource); // it had already expired underneath us
                        }
                    }
                }
                case REPORT_SUCCESS -> {
                    pool.report(resource, CTX, new Outcome.Success(Duration.ofMillis(5)));
                    Instant cooled = coolingUntil.get(resource);
                    if (cooled != null && !now.isBefore(cooled)) {
                        coolingUntil.remove(resource); // COOLING -> RECOVERING: selectable again
                    }
                }
                case REPORT_BLOCKED ->
                    pool.report(resource, CTX, new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(5)));
                case BLOCK -> {
                    var duration = Duration.ofMinutes(step.minutes());
                    pool.block(resource, duration);
                    blockedUntil.put(resource, now.plus(duration));
                }
                case BLOCK_PERMANENTLY -> {
                    pool.blockPermanently(resource);
                    blockedUntil.put(resource, Instant.MAX);
                }
                case UNBLOCK -> {
                    pool.unblock(resource);
                    blockedUntil.remove(resource);
                }
                case ADVANCE_TIME -> {
                    now = now.plus(Duration.ofMinutes(step.minutes()));
                    clock.set(now);
                }
            }
            // learn cooldown transitions from the event stream, never from pool internals
            for (; seenEvents < events.size(); seenEvents++) {
                if (events.get(seenEvents) instanceof PoolEvent.ResourceCooled cooled
                        && cooled.context().equals(CTX)) {
                    coolingUntil.put(cooled.resource(), cooled.until());
                }
            }
        }
    }
}
