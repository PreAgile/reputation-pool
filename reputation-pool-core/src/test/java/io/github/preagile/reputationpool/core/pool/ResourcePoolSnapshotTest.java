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
import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Behavior of {@link ResourcePool#snapshot()} and {@link ResourcePool#restore(PoolSnapshot)}: the
 * whole durable state (cells + blocklist + registered) survives a save/restore cycle unchanged, and
 * in particular a blocklisted resource stays blocklisted after a restart — the regression this
 * persistence layer exists to prevent.
 */
class ResourcePoolSnapshotTest {

    private static final Context[] CONTEXTS = {new Context("checkout"), new Context("search")};
    private static final Instant T0 = Instant.parse("2026-07-08T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int RESOURCES = 4;

    private static final EventSink NOOP = event -> {};

    private static ResourcePool freshPool(java.time.Clock clock) {
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 3, 2);
        return new ResourcePool(engine, new WeightedRandomSelectionStrategy(), NOOP, clock, new Random(1), TTL);
    }

    private static ResourceId proxy(int i) {
        return new ResourceId(ResourceKind.PROXY, "p" + i);
    }

    // --- round-trip property ---------------------------------------------------------------------

    private enum Op {
        REGISTER,
        REPORT_SUCCESS,
        REPORT_FAILURE,
        ACQUIRE,
        BLOCK,
        BLOCK_PERMANENTLY,
        UNBLOCK,
        ADVANCE_TIME
    }

    private record Step(Op op, int resource, int context, int minutes) {}

    @Provide
    Arbitrary<List<Step>> actionSequences() {
        var ops = Arbitraries.of(Op.values());
        var resources = Arbitraries.integers().between(0, RESOURCES - 1);
        var contexts = Arbitraries.integers().between(0, CONTEXTS.length - 1);
        var minutes = Arbitraries.integers().between(1, 120);
        return Combinators.combine(ops, resources, contexts, minutes)
                .as(Step::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(60);
    }

    @Property
    void snapshotThenRestoreReproducesTheWholeDurableState(@ForAll("actionSequences") List<Step> steps) {
        var clock = new SettableClock(T0);
        var source = freshPool(clock);
        drive(source, steps, clock);

        PoolSnapshot snapshot = source.snapshot();

        // a brand-new pool, same config, rehydrated from the snapshot alone
        var restored = freshPool(new SettableClock(T0));
        restored.restore(snapshot);

        assertThat(restored.snapshot())
                .as("cells + blocklist + registered must round-trip through save/restore")
                .isEqualTo(snapshot);
    }

    private static void drive(ResourcePool pool, List<Step> steps, SettableClock clock) {
        Instant now = T0;
        for (Step step : steps) {
            ResourceId resource = proxy(step.resource());
            Context context = CONTEXTS[step.context()];
            switch (step.op()) {
                case REGISTER -> pool.register(resource);
                case REPORT_SUCCESS -> pool.report(resource, context, new Outcome.Success(Duration.ofMillis(5)));
                case REPORT_FAILURE ->
                    pool.report(resource, context, new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(5)));
                case ACQUIRE -> pool.acquire(context);
                case BLOCK -> pool.block(resource, Duration.ofMinutes(step.minutes()));
                case BLOCK_PERMANENTLY -> pool.blockPermanently(resource);
                case UNBLOCK -> pool.unblock(resource);
                case ADVANCE_TIME -> {
                    now = now.plus(Duration.ofMinutes(step.minutes()));
                    clock.set(now);
                }
            }
        }
    }

    // --- the key regression ----------------------------------------------------------------------

    @Test
    void aBlocklistedResourceStaysBlocklistedAfterRestore() {
        var context = CONTEXTS[0];
        var source = freshPool(java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        source.register(proxy(0));
        source.block(proxy(0), Duration.ofHours(1));
        assertThat(source.acquire(context)).as("blocked before the snapshot").isEmpty();

        PoolSnapshot snapshot = source.snapshot();

        // simulate a restart: a fresh pool rehydrated from the snapshot only
        var restarted = freshPool(java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        restarted.restore(snapshot);

        assertThat(restarted.acquire(context))
                .as("a cells-only store would drop the blocklist and re-lend p0 after restart")
                .isEmpty();
        assertThat(restarted.snapshot().registered())
                .as("the registered set is preserved across the restart")
                .containsExactly(proxy(0));
    }

    @Test
    void restoreDoesNotResurrectLeases() {
        var source = freshPool(java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        source.register(proxy(0));
        assertThat(source.acquire(CONTEXTS[0])).as("held in the source pool").isPresent();

        var restarted = freshPool(java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        restarted.restore(source.snapshot());

        assertThat(restarted.acquire(CONTEXTS[0]))
                .as("leases are runtime-only: after a restart the resource is free again")
                .isPresent();
    }
}
