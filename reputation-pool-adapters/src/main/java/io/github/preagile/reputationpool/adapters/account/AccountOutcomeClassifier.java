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

import java.time.Duration;

/**
 * Turns the raw result of using an account — an HTTP response or a transport error — into an
 * {@link AccountProbe}. The core deliberately does not inspect traffic; classification is the
 * caller's responsibility, and this is that caller.
 *
 * <p>Unlike a proxy, an account has a failure mode that no cooldown can cure — an invalid or dead
 * credential — so this classifier returns an {@link AccountProbe} (reputational vs. terminal)
 * rather than a bare {@code Outcome}. This is a deliberate divergence from the proxy adapter's
 * classifier, not an oversight: the two adapters are kept independent because a lockout and a dead
 * password call for different handling.
 *
 * <p>An open contract: what counts as a lockout, a slow call, or a dead credential is a per-platform
 * policy, so different classifiers can be plugged in. The default is
 * {@link HttpAccountOutcomeClassifier}.
 */
public interface AccountOutcomeClassifier {

    /**
     * Classifies a completed HTTP response from using the account.
     *
     * @param statusCode the HTTP status code received
     * @param latency how long the request took; never null, never negative
     * @return a reputational probe for a healthy or transiently-failing response, or
     *     {@link AccountProbe.InvalidCredential} when the status says the credential itself is bad
     * @throws NullPointerException if {@code latency} is null
     * @throws IllegalArgumentException if {@code latency} is negative
     */
    AccountProbe classifyResponse(int statusCode, Duration latency);

    /**
     * Classifies a transport-level failure (no HTTP response was received). A transport failure is
     * always reputational — it says nothing about whether the credential is valid.
     *
     * @param error the error thrown while attempting the request
     * @param latency how long elapsed before the failure; never null, never negative
     * @return a reputational probe wrapping a classified {@code Failure}
     * @throws NullPointerException if {@code error} or {@code latency} is null
     * @throws IllegalArgumentException if {@code latency} is negative
     */
    AccountProbe classifyError(Throwable error, Duration latency);
}
