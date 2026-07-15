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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.statistics.Statistics;

/**
 * Property specification for {@link HttpProxyOutcomeClassifier}: the classifier is a <em>total</em>
 * mapping. Any int whatsoever — not just codes that look like HTTP — classifies to a defined
 * {@link Outcome} without throwing, and any transport error from the documented hierarchy (wrapped
 * or not) classifies to a defined {@code Failure}. The example tests in
 * {@link HttpProxyOutcomeClassifierTest} document <em>which</em> outcome each chosen code maps to;
 * these properties prove the mapping has no holes anywhere in the domain.
 */
class HttpProxyOutcomeClassifierPropertyTest {

    private static final Duration THRESHOLD = HttpProxyOutcomeClassifier.DEFAULT_SLOW_THRESHOLD;

    private final HttpProxyOutcomeClassifier classifier = new HttpProxyOutcomeClassifier();

    @Property
    @Label("every int status code classifies to a defined Outcome and never throws")
    void everyStatusCodeClassifiesToADefinedOutcome(@ForAll int statusCode, @ForAll("latencies") Duration latency) {
        Outcome outcome = classifier.classifyResponse(statusCode, latency);

        assertThat(outcome).isNotNull();
        Statistics.label("status class").collect(statusClassOf(statusCode));
    }

    @Property
    @Label("a 2xx is a Success or a SLOW failure, decided by the threshold alone")
    void aTwoXxIsASuccessOrASlowFailureOnly(
            @ForAll @IntRange(min = 200, max = 299) int statusCode, @ForAll("latencies") Duration latency) {
        Outcome outcome = classifier.classifyResponse(statusCode, latency);

        if (latency.compareTo(THRESHOLD) > 0) {
            assertThat(outcome).isEqualTo(new Outcome.Failure(FailureType.SLOW, latency));
            Statistics.label("2xx verdict").collect("SLOW failure");
        } else {
            assertThat(outcome).isEqualTo(new Outcome.Success(latency));
            Statistics.label("2xx verdict").collect("Success");
        }
    }

    @Property
    @Label("a non-2xx never classifies as a Success, and never as SLOW")
    void aNonTwoXxIsAlwaysANonSlowFailure(
            @ForAll("nonTwoXxStatusCodes") int statusCode, @ForAll("latencies") Duration latency) {
        Outcome outcome = classifier.classifyResponse(statusCode, latency);

        assertThat(outcome).isInstanceOf(Outcome.Failure.class);
        FailureType type = ((Outcome.Failure) outcome).type();
        // SLOW is reserved for a response that worked; a non-2xx did not
        assertThat(type).isNotEqualTo(FailureType.SLOW);
        Statistics.label("failure type").collect(type);
        // the weighting must actually reach every branch of the mapping, not just the fallback
        Statistics.label("failure type").coverage(coverage -> {
            coverage.check(FailureType.BLOCKED).count(c -> c > 0);
            coverage.check(FailureType.TIMEOUT).count(c -> c > 0);
            coverage.check(FailureType.CONNECTION_RESET).count(c -> c > 0);
        });
    }

    @Property
    @Label("every transport error classifies to the documented Failure and never throws, wrapped or not")
    void everyTransportErrorClassifiesToTheDocumentedFailure(
            @ForAll("classifiedTransportErrors") Tuple.Tuple2<Throwable, FailureType> error,
            @ForAll("latencies") Duration latency) {
        Outcome outcome = classifier.classifyError(error.get1(), latency);

        assertThat(outcome).isEqualTo(new Outcome.Failure(error.get2(), latency));
        Statistics.label("classified as").collect(error.get2());
    }

    @Property
    @Label("a negative latency is rejected for every status code, as the contract promises")
    void aNegativeLatencyIsRejectedForEveryStatusCode(
            @ForAll int statusCode, @ForAll("negativeLatencies") Duration latency) {
        assertThatThrownBy(() -> classifier.classifyResponse(statusCode, latency))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Non-negative latencies straddling the 2s threshold, nanosecond-precision. */
    @Provide
    Arbitrary<Duration> latencies() {
        return Arbitraries.longs().between(0L, 5_000_000_000L).map(Duration::ofNanos);
    }

    @Provide
    Arbitrary<Duration> negativeLatencies() {
        return Arbitraries.longs().between(-5_000_000_000L, -1L).map(Duration::ofNanos);
    }

    /**
     * The whole int line minus [200, 300): negative codes and absurd values included on purpose,
     * with real HTTP codes and the classifier's documented specials (401/403/407/429, 408/504)
     * weighted in so every branch of the mapping — not just the fallback — actually occurs.
     */
    @Provide
    Arbitrary<Integer> nonTwoXxStatusCodes() {
        return Arbitraries.frequencyOf(
                        Tuple.of(6, Arbitraries.integers()),
                        Tuple.of(3, Arbitraries.integers().between(100, 599)),
                        Tuple.of(1, Arbitraries.of(401, 403, 407, 429, 408, 504)))
                .filter(code -> code < 200 || code >= 300);
    }

    /**
     * A transport error paired with the {@link FailureType} the classifier documents for it, wrapped
     * zero to three times in the async wrappers ({@link CompletionException},
     * {@link ExecutionException}) the classifier promises to unwrap. Unknown throwables — including a
     * wrapper with no cause to unwrap — fall back to {@code CONNECTION_RESET}.
     */
    @Provide
    Arbitrary<Tuple.Tuple2<Throwable, FailureType>> classifiedTransportErrors() {
        Arbitrary<Tuple.Tuple2<Throwable, FailureType>> causes = Arbitraries.of(
                Tuple.of(new SSLException("handshake refused"), FailureType.TLS_HANDSHAKE),
                Tuple.of(new SSLHandshakeException("bad certificate"), FailureType.TLS_HANDSHAKE),
                Tuple.of(new HttpTimeoutException("request timed out"), FailureType.TIMEOUT),
                Tuple.of(new HttpConnectTimeoutException("connect timed out"), FailureType.TIMEOUT),
                Tuple.of(new SocketTimeoutException("read timed out"), FailureType.TIMEOUT),
                Tuple.of(new ConnectException("connection refused"), FailureType.CONNECTION_RESET),
                Tuple.of(new IOException("broken pipe"), FailureType.CONNECTION_RESET),
                Tuple.of(new RuntimeException("something unexpected"), FailureType.CONNECTION_RESET),
                Tuple.of(new CompletionException("wrapper with no cause", null), FailureType.CONNECTION_RESET));
        return Combinators.combine(causes, Arbitraries.integers().between(0, 3))
                .as((cause, depth) -> Tuple.of(wrap(cause.get1(), depth), cause.get2()));
    }

    private static Throwable wrap(Throwable cause, int depth) {
        Throwable wrapped = cause;
        for (int i = 0; i < depth; i++) {
            wrapped = (i % 2 == 0) ? new CompletionException(wrapped) : new ExecutionException(wrapped);
        }
        return wrapped;
    }

    private static String statusClassOf(int statusCode) {
        if (statusCode < 100) {
            return "below 100 (not HTTP)";
        }
        if (statusCode >= 600) {
            return "600 and above (not HTTP)";
        }
        return (statusCode / 100) + "xx";
    }
}
