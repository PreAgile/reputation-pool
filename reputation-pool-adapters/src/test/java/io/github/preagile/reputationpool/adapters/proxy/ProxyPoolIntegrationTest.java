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
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.github.preagile.reputationpool.core.port.EventSink;
import io.github.preagile.reputationpool.core.testing.SettableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end: the proxy adapter probes a real (WireMock) HTTP endpoint, classifies the result, and
 * feeds it to the core pool — driving cooling and recovery for real. This is the first integration
 * test in the repo; the core itself performs no I/O, so integration lives here in the adapter module.
 */
class ProxyPoolIntegrationTest {

    private static final Context CTX = new Context("cpeats");
    private static final Instant START = Instant.parse("2026-07-08T00:00:00Z");

    private WireMockServer wireMock;
    private HttpClient http;
    private SettableClock clock;
    private List<PoolEvent> events;
    private ResourcePool pool;
    private ProxyEndpoint endpoint;
    private ResourceId endpointId;
    private final OutcomeClassifier classifier = new HttpProxyOutcomeClassifier();

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        http = HttpClient.newHttpClient();
        clock = new SettableClock(START);
        events = new CopyOnWriteArrayList<>();
        EventSink sink = events::add;
        // coolAfter = 2, recoverAfter = 2 so the loops stay short
        var engine = new ReputationEngine(new AdaptiveCooldownPolicy(), 10, 2, 2);
        pool = new ResourcePool(
                engine,
                new WeightedRandomSelectionStrategy(),
                sink,
                clock,
                new java.util.Random(1),
                Duration.ofMinutes(5));
        endpoint = new ProxyEndpoint("demo", ProxyType.RESIDENTIAL, "localhost", wireMock.port(), null);
        endpointId = endpoint.toResourceId();
        pool.register(endpointId);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    /** One probe: hit /probe through the adapter's HTTP client and classify the response. */
    private Outcome probeOnce() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(wireMock.baseUrl() + "/probe"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        // fixed latency: this test exercises status-code classification, not the slow threshold
        return classifier.classifyResponse(response.statusCode(), Duration.ofMillis(10));
    }

    @Test
    void aFlakyEndpointCoolsThenRecoversThroughThePool() throws Exception {
        // --- Phase 1: the endpoint keeps returning 403 -> classified BLOCKED -> cools ---
        wireMock.stubFor(get(urlEqualTo("/probe")).willReturn(aResponse().withStatus(403)));
        for (int i = 0; i < 2; i++) { // coolAfter = 2
            var lease = pool.acquire(CTX);
            assertThat(lease).as("should still lend before it cools").isPresent();
            pool.report(endpointId, CTX, probeOnce()); // 403 -> BLOCKED
            pool.release(lease.get());
        }
        assertThat(events).anyMatch(PoolEvent.ResourceCooled.class::isInstance);
        assertThat(pool.acquire(CTX)).as("cooling resource is not selectable").isEmpty();

        // --- Phase 2: cooldown passes and the endpoint recovers (200) -> classified Success ---
        clock.set(START.plusSeconds(3 * 3600)); // past the BLOCKED cooldown (7200s at two failures)
        wireMock.resetAll();
        wireMock.stubFor(get(urlEqualTo("/probe")).willReturn(aResponse().withStatus(200)));
        for (int i = 0; i < 2; i++) { // recoverAfter = 2; probes report directly (no lease while cooling)
            pool.report(endpointId, CTX, probeOnce()); // 200 -> Success
        }
        assertThat(events).anyMatch(PoolEvent.ResourceRecovered.class::isInstance);
        assertThat(pool.acquire(CTX))
                .as("recovered resource is selectable again")
                .isPresent();
    }
}
