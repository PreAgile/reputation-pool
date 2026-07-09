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

import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLException;

/**
 * The default account classifier: maps HTTP statuses and common transport errors onto an
 * {@link AccountProbe}.
 *
 * <ul>
 *   <li>2xx faster than the slow threshold is a {@code Success}; a 2xx slower than it is a
 *       {@code SLOW} reputational failure — the call worked but was too sluggish to trust.
 *   <li><b>401 / 403</b> read as a bad or forbidden credential and map to
 *       {@link AccountProbe.InvalidCredential}: no cooldown fixes a wrong password, so this is a
 *       block-permanently signal, not a reputation one.
 *   <li><b>429</b> is a rate-limit or lockout — a {@code BLOCKED} reputational failure that cools the
 *       account the longest, then lets it recover.
 *   <li>408 / 504 are a {@code TIMEOUT}; any other non-2xx falls back to {@code CONNECTION_RESET} as
 *       a generic "this account misbehaved".
 *   <li>TLS errors are {@code TLS_HANDSHAKE}; timeouts are {@code TIMEOUT}; connection/socket errors
 *       are {@code CONNECTION_RESET} — all reputational.
 * </ul>
 *
 * <p>This mapping is a demo default, not gospel — a platform whose {@code 403} is a temporary
 * geo-block rather than a ban can plug in another {@link AccountOutcomeClassifier}.
 */
public final class HttpAccountOutcomeClassifier implements AccountOutcomeClassifier {

    /** Default slow threshold: a success slower than this is treated as {@code SLOW}. */
    public static final Duration DEFAULT_SLOW_THRESHOLD = Duration.ofSeconds(2);

    private final Duration slowThreshold;

    /** Creates a classifier with the {@link #DEFAULT_SLOW_THRESHOLD}. */
    public HttpAccountOutcomeClassifier() {
        this(DEFAULT_SLOW_THRESHOLD);
    }

    /**
     * Creates a classifier with a configurable slow threshold.
     *
     * @param slowThreshold a successful response slower than this is classified {@code SLOW}
     * @throws NullPointerException if {@code slowThreshold} is null
     * @throws IllegalArgumentException if {@code slowThreshold} is zero or negative
     */
    public HttpAccountOutcomeClassifier(Duration slowThreshold) {
        Objects.requireNonNull(slowThreshold, "slowThreshold must not be null");
        if (slowThreshold.isZero() || slowThreshold.isNegative()) {
            throw new IllegalArgumentException("slowThreshold must be positive");
        }
        this.slowThreshold = slowThreshold;
    }

    public Duration slowThreshold() {
        return slowThreshold;
    }

    @Override
    public AccountProbe classifyResponse(int statusCode, Duration latency) {
        requireNonNegativeLatency(latency);
        if (statusCode >= 200 && statusCode < 300) {
            Outcome outcome = latency.compareTo(slowThreshold) > 0
                    ? new Outcome.Failure(FailureType.SLOW, latency)
                    : new Outcome.Success(latency);
            return new AccountProbe.Reputational(outcome);
        }
        // a bad or forbidden credential is terminal: no cooldown makes a wrong password right
        if (statusCode == 401 || statusCode == 403) {
            return new AccountProbe.InvalidCredential(statusCode);
        }
        FailureType type =
                switch (statusCode) {
                    case 429 -> FailureType.BLOCKED;
                    case 408, 504 -> FailureType.TIMEOUT;
                    default -> FailureType.CONNECTION_RESET;
                };
        return new AccountProbe.Reputational(new Outcome.Failure(type, latency));
    }

    @Override
    public AccountProbe classifyError(Throwable error, Duration latency) {
        Objects.requireNonNull(error, "error must not be null");
        requireNonNegativeLatency(latency);
        Throwable cause = unwrapAsync(error);
        FailureType type;
        if (cause instanceof SSLException) {
            type = FailureType.TLS_HANDSHAKE;
        } else if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            type = FailureType.TIMEOUT;
        } else if (cause instanceof ConnectException || cause instanceof SocketException) {
            type = FailureType.CONNECTION_RESET;
        } else {
            type = FailureType.CONNECTION_RESET; // generic transport failure
        }
        return new AccountProbe.Reputational(new Outcome.Failure(type, latency));
    }

    private static void requireNonNegativeLatency(Duration latency) {
        Objects.requireNonNull(latency, "latency must not be null");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
    }

    /**
     * Async callers ({@code sendAsync}, {@code Future.get}) surface the transport cause wrapped in
     * {@link CompletionException} / {@link ExecutionException}; classification must reach the cause,
     * not stop at the wrapper. Depth-capped so a pathological cause cycle still terminates.
     */
    private static Throwable unwrapAsync(Throwable error) {
        Throwable cause = error;
        for (int depth = 0;
                depth < 8
                        && (cause instanceof CompletionException || cause instanceof ExecutionException)
                        && cause.getCause() != null;
                depth++) {
            cause = cause.getCause();
        }
        return cause;
    }
}
