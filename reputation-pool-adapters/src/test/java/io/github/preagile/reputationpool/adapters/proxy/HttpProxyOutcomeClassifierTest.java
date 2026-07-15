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
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class HttpProxyOutcomeClassifierTest {

    private static final Duration FAST = Duration.ofMillis(50);
    private final OutcomeClassifier classifier = new HttpProxyOutcomeClassifier();

    private static FailureType failureType(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Outcome.Failure.class);
        return ((Outcome.Failure) outcome).type();
    }

    @Test
    void fastTwoHundredIsSuccess() {
        assertThat(classifier.classifyResponse(200, FAST)).isInstanceOf(Outcome.Success.class);
    }

    @Test
    void slowTwoHundredIsSlow() {
        // default slow threshold is 2s
        assertThat(failureType(classifier.classifyResponse(200, Duration.ofSeconds(5))))
                .isEqualTo(FailureType.SLOW);
    }

    @Test
    void theTwoXxRangeEdgesAreExact() {
        // The success window is [200, 300): 200 and 299 are inside and classify by latency alone;
        // 199 and 300 are one outside and are failures, never Success/SLOW. Pinned as examples so
        // the boundary does not depend on the property generator happening to draw the edge.
        assertThat(classifier.classifyResponse(200, FAST)).isInstanceOf(Outcome.Success.class);
        assertThat(classifier.classifyResponse(299, FAST)).isInstanceOf(Outcome.Success.class);
        assertThat(failureType(classifier.classifyResponse(199, FAST))).isEqualTo(FailureType.CONNECTION_RESET);
        assertThat(failureType(classifier.classifyResponse(300, FAST))).isEqualTo(FailureType.CONNECTION_RESET);
    }

    @Test
    void blockSignalsAreBlocked() {
        for (int status : new int[] {401, 403, 407, 429}) {
            assertThat(failureType(classifier.classifyResponse(status, FAST)))
                    .as("status %d", status)
                    .isEqualTo(FailureType.BLOCKED);
        }
    }

    @Test
    void timeoutStatusesAreTimeout() {
        assertThat(failureType(classifier.classifyResponse(408, FAST))).isEqualTo(FailureType.TIMEOUT);
        assertThat(failureType(classifier.classifyResponse(504, FAST))).isEqualTo(FailureType.TIMEOUT);
    }

    @Test
    void otherNonSuccessFallsBackToConnectionReset() {
        assertThat(failureType(classifier.classifyResponse(500, FAST))).isEqualTo(FailureType.CONNECTION_RESET);
    }

    @Test
    void transportErrorsMapByException() {
        assertThat(failureType(classifier.classifyError(new SSLHandshakeException("x"), FAST)))
                .isEqualTo(FailureType.TLS_HANDSHAKE);
        assertThat(failureType(classifier.classifyError(new HttpTimeoutException("x"), FAST)))
                .isEqualTo(FailureType.TIMEOUT);
        assertThat(failureType(classifier.classifyError(new SocketTimeoutException("x"), FAST)))
                .isEqualTo(FailureType.TIMEOUT);
        assertThat(failureType(classifier.classifyError(new ConnectException("x"), FAST)))
                .isEqualTo(FailureType.CONNECTION_RESET);
        assertThat(failureType(classifier.classifyError(new RuntimeException("x"), FAST)))
                .isEqualTo(FailureType.CONNECTION_RESET);
    }

    @Test
    void asyncWrapperExceptionsAreUnwrappedBeforeClassification() {
        // sendAsync-style callers surface the transport cause inside CompletionException /
        // ExecutionException; classification must reach the cause, not stop at the wrapper
        assertThat(failureType(classifier.classifyError(new CompletionException(new SSLHandshakeException("x")), FAST)))
                .isEqualTo(FailureType.TLS_HANDSHAKE);
        assertThat(failureType(classifier.classifyError(new ExecutionException(new HttpTimeoutException("x")), FAST)))
                .isEqualTo(FailureType.TIMEOUT);
        assertThat(failureType(classifier.classifyError(
                        new CompletionException(new ExecutionException(new SSLHandshakeException("x"))), FAST)))
                .isEqualTo(FailureType.TLS_HANDSHAKE);
    }

    @Test
    void aWrapperWithoutACauseFallsBackToConnectionReset() {
        assertThat(failureType(classifier.classifyError(new CompletionException("bare", null), FAST)))
                .isEqualTo(FailureType.CONNECTION_RESET);
    }

    @Test
    void aJvmErrorIsRethrownRatherThanClassified() {
        // a JVM-level Error is not a transport failure; it must propagate unchanged rather than be
        // classified as CONNECTION_RESET and cool the proxy — even when an async boundary wraps it
        var oom = new OutOfMemoryError("heap");
        assertThatThrownBy(() -> classifier.classifyError(oom, FAST)).isSameAs(oom);
        assertThatThrownBy(() -> classifier.classifyError(new CompletionException(oom), FAST))
                .isSameAs(oom);
    }

    @Test
    void rejectsNonPositiveSlowThresholdAndNullArguments() {
        assertThatThrownBy(() -> new HttpProxyOutcomeClassifier(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpProxyOutcomeClassifier(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> classifier.classifyResponse(200, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> classifier.classifyError(null, FAST)).isInstanceOf(NullPointerException.class);
    }
}
