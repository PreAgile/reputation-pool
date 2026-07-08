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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class LeaseRegistryTest {

    private static final Context CTX = new Context("cpeats");
    private static final Instant NOW = Instant.parse("2026-07-08T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    private static ResourceId proxy(String value) {
        return new ResourceId(ResourceKind.PROXY, value);
    }

    @Test
    void tryAcquireOnAFreeResourceSucceeds() {
        var registry = new LeaseRegistry();
        var lease = registry.tryAcquire(proxy("p1"), CTX, NOW, TTL);
        assertThat(lease).isPresent();
        assertThat(lease.get().resource()).isEqualTo(proxy("p1"));
        assertThat(lease.get().context()).isEqualTo(CTX);
    }

    @Test
    void tryAcquireOnAnAlreadyLeasedResourceFails() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        registry.tryAcquire(id, CTX, NOW, TTL);
        assertThat(registry.tryAcquire(id, CTX, NOW, TTL)).isEmpty();
    }

    @Test
    void releaseAllowsReacquisition() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        var token = registry.tryAcquire(id, CTX, NOW, TTL).orElseThrow().token();
        assertThat(registry.release(id, token)).isTrue();
        assertThat(registry.tryAcquire(id, CTX, NOW, TTL)).isPresent();
    }

    @Test
    void releaseRequiresTheMatchingToken() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        var token = registry.tryAcquire(id, CTX, NOW, TTL).orElseThrow().token();
        assertThat(registry.release(id, token + 999)).isFalse();
        assertThat(registry.isLeased(id, NOW)).isTrue(); // still held by the real holder
    }

    @Test
    void aStaleTokenCannotEvictTheNewHolder() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        var first = registry.tryAcquire(id, CTX, NOW, Duration.ofSeconds(30)).orElseThrow();
        var afterExpiry = NOW.plusSeconds(31);
        var second = registry.tryAcquire(id, CTX, afterExpiry, TTL).orElseThrow(); // reclaims the expired lease

        // the original holder wakes up late and tries to release with its stale token
        assertThat(registry.release(id, first.token())).isFalse();
        assertThat(registry.isLeased(id, afterExpiry)).isTrue(); // the new holder is untouched
        assertThat(registry.release(id, second.token())).isTrue();
    }

    @Test
    void anExpiredLeaseCanBeReacquired() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        registry.tryAcquire(id, CTX, NOW, Duration.ofSeconds(30));
        assertThat(registry.tryAcquire(id, CTX, NOW.plusSeconds(31), TTL)).isPresent();
    }

    @Test
    void isLeasedReflectsActiveButNotExpired() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        registry.tryAcquire(id, CTX, NOW, Duration.ofSeconds(60));
        assertThat(registry.isLeased(id, NOW.plusSeconds(59))).isTrue();
        assertThat(registry.isLeased(id, NOW.plusSeconds(60))).isFalse(); // exclusive expiry
    }

    @Test
    void releasingAnUnleasedResourceReturnsFalse() {
        var registry = new LeaseRegistry();
        assertThat(registry.release(proxy("nope"), 1L)).isFalse();
    }

    @Test
    void renewExtendsALiveLease() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        var token = registry.tryAcquire(id, CTX, NOW, Duration.ofSeconds(60))
                .orElseThrow()
                .token();
        assertThat(registry.renew(id, token, NOW.plusSeconds(30), Duration.ofSeconds(60)))
                .isPresent();
        // without the renew it would have expired at NOW+60; now it lives to NOW+90
        assertThat(registry.isLeased(id, NOW.plusSeconds(80))).isTrue();
    }

    @Test
    void renewingBeforeExpiryKeepsALeaseAliveIndefinitely() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        var token = registry.tryAcquire(id, CTX, NOW, Duration.ofSeconds(60))
                .orElseThrow()
                .token();
        var now = NOW;
        for (int i = 0; i < 1000; i++) {
            now = now.plusSeconds(30); // renew well before the 60s expiry
            assertThat(registry.renew(id, token, now, Duration.ofSeconds(60))).isPresent();
            assertThat(registry.isLeased(id, now)).isTrue(); // never preempted by the TTL safety net
        }
    }

    @Test
    void renewWithAWrongTokenIsRejected() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        var token = registry.tryAcquire(id, CTX, NOW, Duration.ofSeconds(60))
                .orElseThrow()
                .token();
        assertThat(registry.renew(id, token + 1, NOW.plusSeconds(10), Duration.ofSeconds(60)))
                .isEmpty();
        // the real lease's expiry is unchanged
        assertThat(registry.isLeased(id, NOW.plusSeconds(59))).isTrue();
        assertThat(registry.isLeased(id, NOW.plusSeconds(60))).isFalse();
    }

    @Test
    void renewOnAnExpiredLeaseFails() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        var token = registry.tryAcquire(id, CTX, NOW, Duration.ofSeconds(30))
                .orElseThrow()
                .token();
        assertThat(registry.renew(id, token, NOW.plusSeconds(31), Duration.ofSeconds(60)))
                .isEmpty();
    }

    @Test
    void renewOnAnUnleasedResourceFails() {
        var registry = new LeaseRegistry();
        assertThat(registry.renew(proxy("nope"), 1L, NOW, TTL)).isEmpty();
    }

    @Test
    void rejectsNullArguments() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        assertThatThrownBy(() -> registry.tryAcquire(null, CTX, NOW, TTL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.tryAcquire(id, null, NOW, TTL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.tryAcquire(id, CTX, null, TTL)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.tryAcquire(id, CTX, NOW, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.isLeased(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.release(null, 1L)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.renew(null, 1L, NOW, TTL)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNonPositiveTtl() {
        var registry = new LeaseRegistry();
        var id = proxy("p1");
        assertThatThrownBy(() -> registry.tryAcquire(id, CTX, NOW, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.tryAcquire(id, CTX, NOW, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- invariants, attacked over many generated inputs (jqwik) ---

    @Property
    void aLeaseHoldsUntilItsExclusiveExpiryAndBlocksReacquire(
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String value,
            @ForAll @LongRange(min = 0, max = 4_000_000_000L) long baseEpoch,
            @ForAll @LongRange(min = 1, max = 1_000_000L) long ttlSeconds) {
        var registry = new LeaseRegistry();
        var id = proxy(value);
        var base = Instant.ofEpochSecond(baseEpoch);
        var ttl = Duration.ofSeconds(ttlSeconds);

        assertThat(registry.tryAcquire(id, CTX, base, ttl)).isPresent();
        assertThat(registry.isLeased(id, base)).isTrue();
        assertThat(registry.isLeased(id, base.plusSeconds(ttlSeconds))).isFalse(); // exclusive
        assertThat(registry.tryAcquire(id, CTX, base, ttl)).isEmpty(); // still held
    }

    @Property
    void reacquisitionAfterExpiryYieldsAStrictlyGreaterToken(
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String value,
            @ForAll @LongRange(min = 0, max = 4_000_000_000L) long baseEpoch,
            @ForAll @LongRange(min = 1, max = 1_000_000L) long ttlSeconds) {
        var registry = new LeaseRegistry();
        var id = proxy(value);
        var base = Instant.ofEpochSecond(baseEpoch);
        var ttl = Duration.ofSeconds(ttlSeconds);

        long first = registry.tryAcquire(id, CTX, base, ttl).orElseThrow().token();
        long second = registry.tryAcquire(id, CTX, base.plusSeconds(ttlSeconds), ttl)
                .orElseThrow()
                .token();
        assertThat(second).isGreaterThan(first);
    }

    // --- concurrency (the M2 lease-exclusivity gate) ---

    @Test
    void thirtyTwoThreadsRacingForOneResourceExactlyOneWins() throws Exception {
        var registry = new LeaseRegistry();
        var id = proxy("contended");
        int threads = 32;
        var pool = Executors.newFixedThreadPool(threads);
        var startGate = new CountDownLatch(1);
        var wins = new AtomicInteger();
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    if (registry.tryAcquire(id, CTX, NOW, TTL).isPresent()) {
                        wins.incrementAndGet();
                    }
                    return null;
                }));
            }
            startGate.countDown(); // release all threads at once
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(wins.get()).isEqualTo(1);
    }

    @Test
    void concurrentAcquireAndReleaseNeverDoubleLeases() throws Exception {
        var registry = new LeaseRegistry();
        var id = proxy("hot");
        int threads = 16;
        int iterations = 2000;
        var pool = Executors.newFixedThreadPool(threads);
        var startGate = new CountDownLatch(1);
        var holders = new AtomicInteger();
        var maxHolders = new AtomicInteger();
        var futures = new ArrayList<Future<?>>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < iterations; i++) {
                        var lease = registry.tryAcquire(id, CTX, NOW, TTL);
                        if (lease.isPresent()) {
                            // hold the lease across this window; exclusivity means no one else is here
                            maxHolders.accumulateAndGet(holders.incrementAndGet(), Math::max);
                            holders.decrementAndGet();
                            registry.release(id, lease.get().token());
                        }
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (var future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(maxHolders.get()).isLessThanOrEqualTo(1);
    }
}
