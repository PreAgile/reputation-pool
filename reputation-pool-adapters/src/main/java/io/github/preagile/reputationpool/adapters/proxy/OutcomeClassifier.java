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

import io.github.preagile.reputationpool.core.domain.Outcome;
import java.time.Duration;

/**
 * Turns the raw result of using a proxy — an HTTP response or a transport error — into the core's
 * {@link Outcome}. The core deliberately does not inspect traffic; {@code FailureType} documents that
 * classification is the caller's responsibility, and this is that caller.
 *
 * <p>An open contract: what counts as blocked, slow, or transient is a per-site policy, so different
 * classifiers can be plugged in. The default is {@link HttpProxyOutcomeClassifier}.
 */
public interface OutcomeClassifier {

    /**
     * Classifies a completed HTTP response.
     *
     * @param statusCode the HTTP status code received
     * @param latency how long the request took; never null, never negative
     * @return a {@code Success} for a healthy response, otherwise a classified {@code Failure}
     * @throws NullPointerException if {@code latency} is null
     * @throws IllegalArgumentException if {@code latency} is negative
     */
    Outcome classifyResponse(int statusCode, Duration latency);

    /**
     * Classifies a transport-level failure (no HTTP response was received).
     *
     * @param error the error thrown while attempting the request
     * @param latency how long elapsed before the failure; never null, never negative
     * @return a classified {@code Failure}
     * @throws NullPointerException if {@code error} or {@code latency} is null
     * @throws IllegalArgumentException if {@code latency} is negative
     */
    Outcome classifyError(Throwable error, Duration latency);
}
