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

import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.lincheck.Lincheck;
import org.junit.jupiter.api.Test;

/**
 * Proves the acquire undo path's actual promise — <em>a {@code block()} that has returned can never
 * be bypassed by an in-flight {@code acquire}</em> — under Lincheck's controlled interleavings, while
 * tolerating the spurious denial that makes this race inexpressible as a linearizability spec.
 *
 * <p><b>Why not a declarative {@code @Operation} spec.</b> This race is not linearizable, in two
 * ways, both by design of the optimistic retry loop. First, <em>which</em> resource acquire grants is
 * schedule-dependent policy, not contract: a transient claim can be observed by a concurrent acquire
 * and then undone. Second, acquire may fail spuriously: an acquire that observes a transient claim
 * returns empty even though the claim is later rolled back, leaving a history ("two acquires both
 * empty on a free resource") that no sequential order explains — the same reason
 * {@code tryLock}-style APIs write spurious failure into their contracts. Serializing acquire against
 * block under one lock would remove the spuriousness and contradict the layer's whole design.
 *
 * <p>What survives as black-box contract is real-time ordered: once {@code block()} has returned, a
 * <em>later-started</em> acquire must not grant. This test encodes exactly that with
 * {@link Lincheck#runConcurrentTest}: the blocker raises a flag only <em>after</em> {@code block()}
 * returns, the acquirer reads the flag <em>before</em> calling {@code acquire}, and a grant with the
 * flag already seen is a bypass. Removing the blocklist gating from {@code acquire} makes this fail
 * within seconds.
 *
 * <p><b>The undo re-check itself is beyond any black-box checker.</b> Removing only the post-claim
 * re-check leaves every observable history linearizable: a grant whose claim landed after a
 * concurrent {@code block()} returned is indistinguishable from an acquire that linearized just
 * before the block. What the undo buys is operational — once {@code block()} returns, no in-flight
 * acquire can still convert into <em>usage</em> of the burned resource — a real-time strengthening
 * living in the claim's timing, which no history of results can witness. Its regression guard is
 * therefore the sequential undo test in {@code ResourcePoolTest}, not a concurrency oracle.
 * Lease-lifecycle linearizability is proven separately by {@link ResourcePoolLincheckTest}, which
 * keeps the blocklist quiet — for the token reasons documented there.
 */
public class ResourcePoolBlockBypassLincheckTest {

    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration BLOCK = Duration.ofMinutes(10);
    private static final Context CONTEXT = new Context("lincheck");
    private static final ResourceId RESOURCE = new ResourceId(ResourceKind.PROXY, "10.0.0.1:8080");

    @Test
    void blockOnceReturnedIsNeverBypassedByInflightAcquire() {
        Lincheck.runConcurrentTest(Integer.getInteger("lincheck.runConcurrentTest.invocations", 50_000), () -> {
            ResourcePool pool = new ResourcePool(
                    new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 2, 2),
                    new FirstByIdSelectionStrategy(),
                    event -> {},
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new Random(42),
                    TTL);
            pool.register(RESOURCE);

            AtomicBoolean blockReturned = new AtomicBoolean();
            AtomicBoolean blockedBeforeStart = new AtomicBoolean();
            AtomicBoolean granted = new AtomicBoolean();
            Thread blocker = new Thread(() -> {
                pool.block(RESOURCE, BLOCK);
                blockReturned.set(true);
            });
            Thread acquirer = new Thread(() -> {
                blockedBeforeStart.set(blockReturned.get());
                granted.set(pool.acquire(CONTEXT).isPresent());
            });
            blocker.start();
            acquirer.start();
            try {
                blocker.join();
                acquirer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            // Asserted after join, on the test thread: an AssertionError inside a spawned
            // thread would die as an uncaught exception and never fail the invocation.
            // Spurious denial is allowed; granting after block() returned is the bypass.
            assertFalse(
                    blockedBeforeStart.get() && granted.get(),
                    "acquire granted a resource although block() had already returned");
        });
    }
}
