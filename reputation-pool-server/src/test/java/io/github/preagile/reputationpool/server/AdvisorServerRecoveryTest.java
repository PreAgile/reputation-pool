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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.port.ResourceStore;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import io.github.preagile.reputationpool.prober.RecoveryProbe;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Verifies the composition root's recovery wiring (issue #87): a {@code Map<ResourceKind,
 * RecoveryProbe>} joins the event fan-out and the periodic scheduler gains a backstop sweep, mirroring
 * how {@link AdvisorServerPersistenceTest} verifies the store-backed lifecycle.
 *
 * <p>Unlike that sibling, {@code recoveryBackstopSweep()} cannot be asserted with zero timing:
 * {@code RecoveryScheduler} genuinely dispatches on a background scheduler and virtual threads (that
 * is the point — it never blocks the caller), so these tests poll a real, short-lived condition
 * instead of a fixed sleep, the same pattern {@code RecoverySchedulerTest} uses.
 */
class AdvisorServerRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Context CTX = new Context("marketplace-a");
    private static final ResourceId PROXY = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(10);
        }
    }

    private static ResourceState stateOf(ResourcePool pool, ResourceId resource, Context context) {
        var cell = pool.snapshot().cells().get(new CellKey(resource, context));
        return cell == null ? null : cell.state();
    }

    /**
     * Under {@link AdaptiveCooldownPolicy}'s real minutes-to-hours curve (fixed inside this composition
     * root — not something its public API lets a caller shrink for a test), the event path's own
     * {@code ResourceCooled} registration is a real scheduled task minutes or hours out the instant a
     * resource cools. That is correct in production (the same real clock drives both the delay and
     * {@code report()}'s own {@code now}), but it means a single live server can never usefully be
     * driven through the event path inside a fast test.
     *
     * <p>So this test exercises the backstop path the way it actually earns its place in production:
     * across a restart. A first server cools the resource and is discarded before its own (real,
     * hours-away) scheduled probe could ever fire — modelling a process that dies right after cooling.
     * A second server restores the same {@code COOLING} cell from the store; its scheduler has never
     * seen a {@code ResourceCooled} event for this resource, so only {@link
     * AdvisorServer#recoveryBackstopSweep} can ever find it. This is exactly the restart gap issue #87
     * names as the reason the backstop path exists at all, not just the event path.
     */
    @Test
    void recoveryBackstopSweep_movesARestoredCoolingResourceIntoRecoveringAfterARestart() throws Exception {
        var store = new FakeResourceStore();
        AdvisorServer beforeRestart = AdvisorServer.create(0, fixedClock(), new Random(42), TTL, store);
        beforeRestart.pool().register(PROXY);
        // Default engine tuning (coolAfter = 2): two failures are needed to cool it. A TLS_HANDSHAKE and
        // not a BLOCKED, because since #90 a site block is recovered by half-open admission rather than
        // by a probe — dueForRecoveryProbe would never name it, and the sweep below would find nothing.
        beforeRestart.pool().report(PROXY, CTX, new Outcome.Failure(FailureType.TLS_HANDSHAKE, Duration.ofMillis(1)));
        beforeRestart.pool().report(PROXY, CTX, new Outcome.Failure(FailureType.TLS_HANDSHAKE, Duration.ofMillis(1)));
        beforeRestart.checkpoint(); // persists the COOLING cell; never start()ed, so no thread to leak

        // Past the 10m TLS_HANDSHAKE cooldown (coolAfter = 2: base 300s x 2^1) by the time the "new
        // process" looks at it.
        var clock = new SettableClock(NOW.plus(Duration.ofHours(5)));
        var probed = new CountDownLatch(1);
        RecoveryProbe alwaysHealthy = (resource, context) -> {
            probed.countDown();
            return Optional.of(new Outcome.Success(Duration.ofMillis(1)));
        };
        AdvisorServer afterRestart = AdvisorServer.create(
                0,
                clock,
                new Random(42),
                TTL,
                store,
                event -> {},
                new AuditRetention(Duration.ofDays(365), instant -> 0L),
                Map.of(ResourceKind.PROXY, alwaysHealthy));

        assertThat(stateOf(afterRestart.pool(), PROXY, CTX))
                .as("restored from the store exactly as it cooled")
                .isEqualTo(ResourceState.COOLING);

        // With recoverAfter = 2, one probe success lands it in RECOVERING, not HEALTHY — from there,
        // ordinary traffic (not further probing) is what AdvisorServer's own doc says carries it the
        // rest of the way; RecoverySchedulerTest already covers the recoverAfter = 1 full-recovery case.
        awaitTrue(
                () -> {
                    afterRestart.recoveryBackstopSweep();
                    return stateOf(afterRestart.pool(), PROXY, CTX) == ResourceState.RECOVERING;
                },
                Duration.ofSeconds(5));
        assertThat(probed.getCount()).isZero();
    }

    @Test
    void recoveryProbes_joinTheSameEventFanOutAsTheAuditSink() {
        List<PoolEvent> audited = new CopyOnWriteArrayList<>();
        AdvisorServer server = AdvisorServer.create(
                0,
                fixedClock(),
                new Random(42),
                TTL,
                new FakeResourceStore(),
                audited::add,
                new AuditRetention(Duration.ofDays(1), instant -> 0L),
                Map.of(ResourceKind.PROXY, (resource, context) -> Optional.empty()));

        server.pool().register(PROXY);
        server.pool().report(PROXY, CTX, new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(1)));
        server.pool().report(PROXY, CTX, new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(1)));

        assertThat(audited)
                .as("the audit sink still receives every event, unaffected by the recovery scheduler "
                        + "joining the same fan-out")
                .hasAtLeastOneElementOfType(PoolEvent.ResourceCooled.class);
    }

    @Test
    void noRecoveryProbes_recoveryBackstopSweepAndShutdownAreNoOps() throws IOException, InterruptedException {
        AdvisorServer server = AdvisorServer.create(0, fixedClock(), new Random(42), TTL);

        assertThatCode(() -> {
                    server.recoveryBackstopSweep();
                    server.start();
                    server.shutdown(Duration.ofSeconds(10));
                })
                .doesNotThrowAnyException();
    }

    @Test
    void recoveryProbesAloneWithNoStore_startAndShutdownStillManageASchedulerCleanly() {
        AdvisorServer server = AdvisorServer.create(
                0, fixedClock(), new Random(42), TTL, Map.of(ResourceKind.PROXY, (r, c) -> Optional.empty()));

        assertThatCode(() -> {
                    server.start();
                    server.shutdown(Duration.ofSeconds(10));
                })
                .as("recovery probes alone (no store) still start and cleanly stop a scheduler")
                .doesNotThrowAnyException();
    }

    /** A minimal in-memory {@link ResourceStore}, local to this test (the sibling's is private there). */
    private static final class FakeResourceStore implements ResourceStore {
        private PoolSnapshot saved;

        @Override
        public void save(PoolSnapshot snapshot) {
            this.saved = snapshot;
        }

        @Override
        public Optional<PoolSnapshot> load() {
            return Optional.ofNullable(saved);
        }
    }
}
