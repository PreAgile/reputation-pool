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
package io.github.preagile.reputationpool.adapters.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class HttpAccountOutcomeClassifierTest {

    private final HttpAccountOutcomeClassifier classifier = new HttpAccountOutcomeClassifier();

    private static FailureType failureTypeOf(AccountProbe probe) {
        assertThat(probe).isInstanceOf(AccountProbe.Reputational.class);
        Outcome outcome = ((AccountProbe.Reputational) probe).outcome();
        assertThat(outcome).isInstanceOf(Outcome.Failure.class);
        return ((Outcome.Failure) outcome).type();
    }

    @Test
    void aFastTwoXxIsAReputationalSuccess() {
        var probe = classifier.classifyResponse(200, Duration.ofMillis(50));
        assertThat(probe).isInstanceOf(AccountProbe.Reputational.class);
        assertThat(((AccountProbe.Reputational) probe).outcome()).isInstanceOf(Outcome.Success.class);
    }

    @Test
    void aTwoXxSlowerThanTheThresholdIsASlowFailure() {
        var probe = classifier.classifyResponse(200, Duration.ofSeconds(5));
        assertThat(failureTypeOf(probe)).isEqualTo(FailureType.SLOW);
    }

    @Test
    void aRateLimitIsAReputationalBlockedFailure() {
        var probe = classifier.classifyResponse(429, Duration.ofMillis(10));
        assertThat(failureTypeOf(probe)).isEqualTo(FailureType.BLOCKED);
    }

    @Test
    void anUnauthorizedIsAnInvalidCredential() {
        var probe = classifier.classifyResponse(401, Duration.ofMillis(10));
        assertThat(probe).isInstanceOf(AccountProbe.InvalidCredential.class);
        assertThat(((AccountProbe.InvalidCredential) probe).statusCode()).isEqualTo(401);
    }

    @Test
    void aForbiddenIsAnInvalidCredential() {
        assertThat(classifier.classifyResponse(403, Duration.ofMillis(10)))
                .isInstanceOf(AccountProbe.InvalidCredential.class);
    }

    @Test
    void aTimeoutStatusIsAReputationalTimeout() {
        assertThat(failureTypeOf(classifier.classifyResponse(504, Duration.ofMillis(10))))
                .isEqualTo(FailureType.TIMEOUT);
    }

    @Test
    void anotherNonTwoXxFallsBackToConnectionReset() {
        assertThat(failureTypeOf(classifier.classifyResponse(500, Duration.ofMillis(10))))
                .isEqualTo(FailureType.CONNECTION_RESET);
    }

    @Test
    void aTransportErrorIsAlwaysReputational() {
        var probe = classifier.classifyError(new ConnectException("refused"), Duration.ofMillis(10));
        assertThat(failureTypeOf(probe)).isEqualTo(FailureType.CONNECTION_RESET);
    }

    @Test
    void aTlsErrorClassifiesAsTlsHandshake() {
        var probe = classifier.classifyError(new SSLHandshakeException("bad cert"), Duration.ofMillis(10));
        assertThat(failureTypeOf(probe)).isEqualTo(FailureType.TLS_HANDSHAKE);
    }

    @Test
    void anAsyncWrappedTimeoutIsUnwrappedToTimeout() {
        var wrapped = new CompletionException(new HttpTimeoutException("timed out"));
        assertThat(failureTypeOf(classifier.classifyError(wrapped, Duration.ofMillis(10))))
                .isEqualTo(FailureType.TIMEOUT);
    }

    @Test
    void rejectsNegativeLatencyEvenForAnInvalidCredentialStatus() {
        // the negative-latency guard runs before the status is inspected, so the contract holds on
        // every path — including 401, where the latency is otherwise unused
        assertThatThrownBy(() -> classifier.classifyResponse(401, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveSlowThreshold() {
        assertThatThrownBy(() -> new HttpAccountOutcomeClassifier(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new HttpAccountOutcomeClassifier(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
