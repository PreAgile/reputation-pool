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
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
    /** The single-target case the javadoc describes: one URL shared by every context. */
    private static final Function<Context, Optional<URI>> TARGETS = ctx -> Optional.of(TARGET);

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
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), TARGETS);

        Optional<Outcome> outcome = probe.test(resourceId, CTX);

        assertThat(outcome).isPresent();
        assertThat(outcome.get()).isInstanceOf(Outcome.Success.class);
    }

    @Test
    void anActiveBlockStatusClassifiesAsBlockedThroughTheSameClassifierRealTrafficUses() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(403)));
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), TARGETS);

        Optional<Outcome> outcome = probe.test(resourceId, CTX);

        assertThat(outcome)
                .contains(new Outcome.Failure(FailureType.BLOCKED, outcome.get().latency()));
    }

    @Test
    void anUnreachableProxyClassifiesAsATransportFailureNotAnException() {
        wireMock.stop(); // the endpoint no longer accepts connections
        var probe = new HttpProxyRecoveryProbe(
                id -> Optional.of(endpoint), TARGETS, new HttpProxyOutcomeClassifier(), Duration.ofSeconds(2));

        Optional<Outcome> outcome = probe.test(resourceId, CTX);

        assertThat(outcome).isPresent();
        assertThat(outcome.get()).isInstanceOf(Outcome.Failure.class);
    }

    @Test
    void anUnresolvableResourceIsSkippedNotFailed() {
        var probe = new HttpProxyRecoveryProbe(id -> Optional.empty(), TARGETS);

        assertThat(probe.test(resourceId, CTX)).isEmpty();
    }

    @Test
    void eachContextIsProbedAtItsOwnTargetThroughTheSameProbe() {
        Context alpha = new Context("alpha");
        Context beta = new Context("beta");
        wireMock.stubFor(
                get(urlEqualTo("/alpha/robots.txt")).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(
                get(urlEqualTo("/beta/robots.txt")).willReturn(aResponse().withStatus(403)));
        // The map-backed resolver the javadoc describes; the hosts differ too, as two real sites would.
        Map<Context, URI> byContext = Map.of(
                alpha, URI.create("http://alpha.invalid/alpha/robots.txt"),
                beta, URI.create("http://beta.invalid/beta/robots.txt"));
        var probe =
                new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), ctx -> Optional.ofNullable(byContext.get(ctx)));

        Optional<Outcome> alphaOutcome = probe.test(resourceId, alpha);
        Optional<Outcome> betaOutcome = probe.test(resourceId, beta);

        // Each context's verdict comes from its own site: the same proxy is healthy for one and blocked
        // for the other, which a single shared target could not have told apart.
        assertThat(alphaOutcome).get().isInstanceOf(Outcome.Success.class);
        assertThat(betaOutcome).get().isInstanceOf(Outcome.Failure.class);
        assertThat(wireMock.findAll(getRequestedFor(urlEqualTo("/alpha/robots.txt"))))
                .as("alpha's probe went to alpha's target")
                .hasSize(1);
        assertThat(wireMock.findAll(getRequestedFor(urlEqualTo("/beta/robots.txt"))))
                .as("beta's probe went to beta's target")
                .hasSize(1);
    }

    @Test
    void aContextWithNoConfiguredTargetIsSkippedWithoutIssuingAnyRequest() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), ctx -> Optional.empty());

        assertThat(probe.test(resourceId, CTX)).isEmpty();

        // A skip, not a failure — and the request journal proves the proxy was never dialled at all,
        // so an unmapped context costs nothing rather than being reported as a broken resource.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void aMissingTargetIsReportedOncePerContextRatherThanOncePerProbe() {
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), ctx -> Optional.empty());
        var other = new Context("kurly");

        assertThat(probe.firstProbeMissingATargetFor(CTX))
                .as("the first probe for an unmapped context is the one worth logging")
                .isTrue();
        assertThat(probe.firstProbeMissingATargetFor(CTX))
                .as("the backstop sweep re-probes on a short period; repeating this would bury it")
                .isFalse();
        assertThat(probe.firstProbeMissingATargetFor(other))
                .as("a second missing mapping is its own misconfiguration and must still be reported")
                .isTrue();

        // the skip itself is unchanged by the reporting: it stays a skip on every probe, not just the
        // first, and never turns into an invented outcome
        assertThat(probe.test(resourceId, CTX)).isEmpty();
        assertThat(probe.test(resourceId, CTX)).isEmpty();
    }

    @Test
    void concurrentProbesForOneUnmappedContextReportItExactlyOnce() throws Exception {
        // Probes run one per virtual thread, so many can find the same missing mapping at once; a
        // check-then-act on a plain set would let several of them through.
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), ctx -> Optional.empty());
        int threads = 32;
        var start = new CountDownLatch(1);
        var reported = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                executor.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (probe.firstProbeMissingATargetFor(CTX)) {
                        reported.incrementAndGet();
                    }
                });
            }
            start.countDown();
        }

        assertThat(reported).hasValue(1);
    }

    @Test
    void rejectsNullConstructorArguments() {
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(null, TARGETS)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(id -> Optional.empty(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(
                        id -> Optional.empty(), null, new HttpProxyOutcomeClassifier(), Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                        () -> new HttpProxyRecoveryProbe(id -> Optional.empty(), TARGETS, null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(
                        id -> Optional.empty(), TARGETS, new HttpProxyOutcomeClassifier(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANonPositiveTimeout() {
        assertThatThrownBy(() -> new HttpProxyRecoveryProbe(
                        id -> Optional.empty(), TARGETS, new HttpProxyOutcomeClassifier(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullArgumentsToTest() {
        var probe = new HttpProxyRecoveryProbe(id -> Optional.of(endpoint), TARGETS);
        assertThatThrownBy(() -> probe.test(null, CTX)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> probe.test(resourceId, null)).isInstanceOf(NullPointerException.class);
    }
}
