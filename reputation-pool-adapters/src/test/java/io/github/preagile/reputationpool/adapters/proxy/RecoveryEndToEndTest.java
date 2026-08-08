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
package io.github.preagile.reputationpool.adapters.proxy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.preagile.reputationpool.core.domain.CellKey;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.domain.ResourceState;
import io.github.preagile.reputationpool.core.engine.CooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import io.github.preagile.reputationpool.prober.RecoveryProbe;
import io.github.preagile.reputationpool.prober.RecoveryScheduler;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end proof that {@link RecoveryScheduler} closes the gap {@link ProxyPoolIntegrationTest}
 * had to work around by hand: there, the test itself called {@code pool.report(...)} directly in a
 * loop with the comment "probes report directly (no lease while cooling)" — a human standing in for
 * the missing component. Here, once a resource cools, nothing in this test ever calls
 * {@code pool.report} again; a real {@link HttpProxyRecoveryProbe} against a real (WireMock) endpoint,
 * driven only by {@link RecoveryScheduler}, is what reports the recovery.
 */
class RecoveryEndToEndTest {

    private static final Context CTX = new Context("cpeats");
    private static final Instant START = Instant.parse("2026-07-08T00:00:00Z");
    private static final Duration TINY_COOLDOWN = Duration.ofMillis(80);

    private WireMockServer wireMock;

    @AfterEach
    void tearDown() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
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

    @Test
    void aCoolingProxyRecoversAutomaticallyOnceTheEndpointHealsWithNoManualReportCall() throws Exception {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(403)));

        var endpoint = new ProxyEndpoint("demo", ProxyType.RESIDENTIAL, "localhost", wireMock.port(), null);
        ResourceId resourceId = endpoint.toResourceId();
        URI target = URI.create("http://reputation-pool.invalid/health");
        RecoveryProbe httpProbe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), target);

        var clock = new SettableClock(START);
        List<PoolEvent> recorded = new CopyOnWriteArrayList<>();
        // A tiny fixed cooldown keeps this test fast; a real deployment would use AdaptiveCooldownPolicy.
        // coolAfter = 1, recoverAfter = 1: one BLOCKED failure cools it, one healthy probe fully recovers it.
        CooldownPolicy tiny = (type, consecutiveFailures) -> TINY_COOLDOWN;
        var engine = new ReputationEngine(tiny, 10, 1, 1);

        // The scheduler does not exist yet when the pool needs its sink; forward to it once it does —
        // the same shape as CompositeEventSink(broadcaster, auditSink) in the grpc module's assembly.
        var schedulerHolder = new RecoveryScheduler[1];
        EventSink sink = event -> {
            recorded.add(event);
            if (schedulerHolder[0] != null) {
                schedulerHolder[0].emit(event);
            }
        };
        var pool = new ResourcePool(
                engine, new WeightedRandomSelectionStrategy(), sink, clock, new Random(1), Duration.ofMinutes(5));
        pool.register(resourceId);

        try (var scheduler = new RecoveryScheduler(
                pool, Map.of(ResourceKind.PROXY, httpProbe), clock, new Random(1), Duration.ZERO)) {
            schedulerHolder[0] = scheduler;

            // Real traffic hits the blocked endpoint and reports it — the only report() call in this test.
            pool.report(resourceId, CTX, new Outcome.Failure(FailureType.BLOCKED, Duration.ofMillis(1)));
            assertThat(pool.acquire(CTX))
                    .as("cooling resource is not selectable")
                    .isEmpty();
            assertThat(recorded).hasAtLeastOneElementOfType(PoolEvent.ResourceCooled.class);

            // The endpoint heals and time passes the cooldown — from here on, nothing in this test calls
            // pool.report(); only the scheduler's own event path (already triggered by the ResourceCooled
            // above) and a periodic backstop sweep, exactly as a real deployment's scheduler would drive it.
            wireMock.resetAll();
            wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
            clock.set(START.plus(TINY_COOLDOWN).plusMillis(1));

            awaitTrue(
                    () -> {
                        scheduler.backstopSweep();
                        return stateOf(pool, resourceId, CTX) == ResourceState.HEALTHY;
                    },
                    Duration.ofSeconds(5));
        }

        assertThat(recorded).hasAtLeastOneElementOfType(PoolEvent.ResourceRecovered.class);
        assertThat(pool.acquire(CTX))
                .as("recovered resource is selectable again")
                .isPresent();
    }
}
