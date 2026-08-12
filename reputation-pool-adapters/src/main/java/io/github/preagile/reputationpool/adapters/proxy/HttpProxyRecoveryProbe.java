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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.Outcome;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.prober.RecoveryProbe;
import io.github.preagile.reputationpool.prober.RecoveryScheduler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The reference {@link RecoveryProbe} for {@code PROXY} resources: a plain {@code java.net.http}
 * request routed through the candidate proxy at a fixed, lightweight {@code target}, classified by
 * the same {@link OutcomeClassifier} normal traffic would use — this is a health check, not the
 * operation real traffic performs, so the target should be cheap and stable (a status endpoint, not
 * the site being scraped).
 *
 * <p>{@link RecoveryScheduler} calls {@link #test} with only a {@link ResourceId} — an opaque value
 * the core never parses (see {@link ProxyEndpoint#toResourceId()}) — so this probe needs a way back
 * to the actual {@code host:port} to dial. That is {@code endpoints}, supplied by the composition
 * root: whatever registered the resource in the first place already holds that mapping, and the core
 * has no reason to know it. A resource that no longer resolves (deregistered since it cooled) yields
 * {@link Optional#empty()} from {@code endpoints} and, in turn, from {@link #test} — a skip, not a
 * failure, matching {@link RecoveryProbe}'s contract.
 *
 * <p>Blocking is intentional: {@link RecoveryScheduler} dispatches each probe on its own virtual
 * thread (JEP 491), so a synchronous {@link HttpClient#send} pins nothing.
 */
public final class HttpProxyRecoveryProbe implements RecoveryProbe {

    /** Default budget for connecting and receiving a response before the attempt counts as a timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final Function<ResourceId, Optional<ProxyEndpoint>> endpoints;
    private final URI target;
    private final OutcomeClassifier classifier;
    private final Duration timeout;

    /**
     * Creates a probe with the default classifier ({@link HttpProxyOutcomeClassifier}) and
     * {@link #DEFAULT_TIMEOUT}.
     *
     * @param endpoints resolves a {@link ResourceId} back to the proxy to dial; empty means "skip"
     * @param target the fixed, lightweight URL to fetch through the candidate proxy
     * @throws NullPointerException if {@code endpoints} or {@code target} is null
     */
    public HttpProxyRecoveryProbe(Function<ResourceId, Optional<ProxyEndpoint>> endpoints, URI target) {
        this(endpoints, target, new HttpProxyOutcomeClassifier(), DEFAULT_TIMEOUT);
    }

    /**
     * Creates a probe with a configurable classifier and timeout.
     *
     * @param endpoints resolves a {@link ResourceId} back to the proxy to dial; empty means "skip"
     * @param target the fixed, lightweight URL to fetch through the candidate proxy
     * @param classifier turns the raw HTTP response or transport error into an {@link Outcome}
     * @param timeout the connect-and-response budget before the attempt counts as a timeout
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code timeout} is zero or negative
     */
    public HttpProxyRecoveryProbe(
            Function<ResourceId, Optional<ProxyEndpoint>> endpoints,
            URI target,
            OutcomeClassifier classifier,
            Duration timeout) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints must not be null");
        this.target = Objects.requireNonNull(target, "target must not be null");
        this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if {@code resource} or {@code context} is null
     */
    @Override
    public Optional<Outcome> test(ResourceId resource, Context context) {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Optional<ProxyEndpoint> endpoint = endpoints.apply(resource);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }

        HttpRequest request =
                HttpRequest.newBuilder(target).timeout(timeout).GET().build();

        // The client is per-probe because its proxy selector is: each candidate dials a different
        // host:port, so it cannot be shared or cached. An HttpClient owns a selector thread and an
        // executor, and only closing it releases them — left to the GC they accumulate one leaked
        // thread per probe. send() is synchronous, so nothing is in flight when close() runs.
        try (HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress(
                        endpoint.get().host(), endpoint.get().port())))
                .connectTimeout(timeout)
                .build()) {
            long startedAtNanos = System.nanoTime();
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                Duration latency = Duration.ofNanos(System.nanoTime() - startedAtNanos);
                return Optional.of(classifier.classifyResponse(response.statusCode(), latency));
            } catch (IOException e) {
                return Optional.of(classifier.classifyError(e, Duration.ofNanos(System.nanoTime() - startedAtNanos)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.of(classifier.classifyError(e, Duration.ofNanos(System.nanoTime() - startedAtNanos)));
            }
        }
    }
}
