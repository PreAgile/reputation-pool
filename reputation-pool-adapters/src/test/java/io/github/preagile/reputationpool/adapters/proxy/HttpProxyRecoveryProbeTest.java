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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour specification for {@link HttpProxyRecoveryProbe}. WireMock plays the candidate proxy
 * itself (the client is configured with a {@code ProxySelector} pointing at it), so the request the
 * stub sees is the same absolute-form request a real upstream proxy would receive — this exercises
 * the actual "route through the proxy" path, not a direct call to a stand-in target.
 */
class HttpProxyRecoveryProbeTest {

    private static final Context CTX = new Context("cpeats");
    private static final URI TARGET = URI.create("http://reputation-pool.invalid/health");

    private WireMockServer wireMock;
    private ProxyEndpoint endpoint;
    private ResourceId resourceId;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        endpoint = new ProxyEndpoint("demo", ProxyType.RESIDENTIAL, "localhost", wireMock.port(), null);
        resourceId = endpoint.toResourceId();
    }

    @AfterEach
    void tearDown() {
        if (wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    @Test
    void aHealthyResponseIsASuccess() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), TARGET);

        Optional<Outcome> outcome = probe.test(resourceId, CTX);

        assertThat(outcome).isPresent();
        assertThat(outcome.get()).isInstanceOf(Outcome.Success.class);
    }

    @Test
    void anActiveBlockStatusClassifiesAsBlockedThroughTheSameClassifierRealTrafficUses() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(403)));
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), TARGET);

        Optional<Outcome> outcome = probe.test(resourceId, CTX);

        assertThat(outcome)
                .contains(new Outcome.Failure(FailureType.BLOCKED, outcome.get().latency()));
    }

    @Test
    void anUnreachableProxyClassifiesAsATransportFailureNotAnException() {
        wireMock.stop(); // the endpoint no longer accepts connections
        var probe = new HttpProxyRecoveryProbe(
                id -> Optional.of(endpoint), TARGET, new HttpProxyOutcomeClassifier(), Duration.ofSeconds(2));

        Optional<Outcome> outcome = probe.test(resourceId, CTX);

        assertThat(outcome).isPresent();
        assertThat(outcome.get()).isInstanceOf(Outcome.Failure.class);
    }

    @Test
    void anUnresolvableResourceIsSkippedNotFailed() {
        var probe = new HttpProxyRecoveryProbe(id -> Optional.empty(), TARGET);

        assertThat(probe.test(resourceId, CTX)).isEmpty();
    }

    @Test
    void rejectsNullConstructorArguments() {
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(null, TARGET)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(id -> Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () -> new HttpProxyRecoveryProbe(id -> Optional.empty(), TARGET, null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(
                        id -> Optional.empty(), TARGET, new HttpProxyOutcomeClassifier(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANonPositiveTimeout() {
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(
                        id -> Optional.empty(), TARGET, new HttpProxyOutcomeClassifier(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullArgumentsToTest() {
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), TARGET);
        assertThatThrownBy(() -> probe.test(null, CTX)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> probe.test(resourceId, null)).isInstanceOf(NullPointerException.class);
    }
}
