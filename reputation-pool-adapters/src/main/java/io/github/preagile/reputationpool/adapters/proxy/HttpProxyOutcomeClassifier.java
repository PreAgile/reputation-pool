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

import io.github.preagile.reputationpool.core.domain.FailureType;
import io.github.preagile.reputationpool.core.domain.Outcome;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLException;

/**
 * The default proxy classifier: maps HTTP statuses and common transport errors onto {@link Outcome}.
 *
 * <ul>
 *   <li>2xx faster than the slow threshold is a {@code Success}; a 2xx slower than it is a {@code SLOW}
 *       failure — a working response that was too sluggish to trust.
 *   <li>401/403/407/429 read as an active block ({@code BLOCKED}); 408/504 as a {@code TIMEOUT}; any
 *       other non-2xx falls back to {@code CONNECTION_RESET} as a generic "this endpoint misbehaved".
 *   <li>TLS errors are {@code TLS_HANDSHAKE}; timeouts are {@code TIMEOUT}; connection/socket errors are
 *       {@code CONNECTION_RESET}.
 * </ul>
 *
 * <p>This mapping is a demo default, not gospel — plug in another {@link OutcomeClassifier} for a
 * site whose signals differ.
 */
public final class HttpProxyOutcomeClassifier implements OutcomeClassifier {

    /** Default slow threshold: a success slower than this is treated as {@code SLOW}. */
    public static final Duration DEFAULT_SLOW_THRESHOLD = Duration.ofSeconds(2);

    private final Duration slowThreshold;

    /** Creates a classifier with the {@link #DEFAULT_SLOW_THRESHOLD}. */
    public HttpProxyOutcomeClassifier() {
        this(DEFAULT_SLOW_THRESHOLD);
    }

    /**
     * Creates a classifier with a configurable slow threshold.
     *
     * @param slowThreshold a successful response slower than this is classified {@code SLOW}
     * @throws NullPointerException if {@code slowThreshold} is null
     * @throws IllegalArgumentException if {@code slowThreshold} is zero or negative
     */
    public HttpProxyOutcomeClassifier(Duration slowThreshold) {
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
    public Outcome classifyResponse(int statusCode, Duration latency) {
        Objects.requireNonNull(latency, "latency must not be null");
        if (statusCode >= 200 && statusCode < 300) {
            return latency.compareTo(slowThreshold) > 0
                    ? new Outcome.Failure(FailureType.SLOW, latency)
                    : new Outcome.Success(latency);
        }
        FailureType type =
                switch (statusCode) {
                    case 401, 403, 407, 429 -> FailureType.BLOCKED;
                    case 408, 504 -> FailureType.TIMEOUT;
                    default -> FailureType.CONNECTION_RESET;
                };
        return new Outcome.Failure(type, latency);
    }

    @Override
    public Outcome classifyError(Throwable error, Duration latency) {
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(latency, "latency must not be null");
        Throwable cause = unwrapAsync(error);
        if (cause instanceof Error jvmError) {
            // a JVM-level Error (OOM, stack overflow) is not a transport failure; never cool a
            // proxy's reputation for it — let it propagate to the caller unchanged
            throw jvmError;
        }
        FailureType type;
        if (cause instanceof SSLException) {
            type = FailureType.TLS_HANDSHAKE;
        } else if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
            type = FailureType.TIMEOUT;
        } else {
            // any other transport failure (connection reset, socket error, unknown) is generic
            type = FailureType.CONNECTION_RESET;
        }
        return new Outcome.Failure(type, latency);
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
