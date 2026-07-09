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

import io.github.preagile.reputationpool.core.domain.Outcome;
import java.util.Objects;

/**
 * The classified result of one attempt to use an account — what the adapter hands back after a probe.
 *
 * <p>An account can fail in two fundamentally different ways, and the pool must treat them
 * differently. This is the sharpest way an account differs from a proxy, so it is made explicit in
 * the type rather than hidden inside a status-code check:
 *
 * <ul>
 *   <li>{@link Reputational} — a transient signal that feeds the engine's reputation (a rate-limit
 *       or lockout, a timeout, a slow-but-successful call). The caller reports the wrapped
 *       {@link Outcome}; the pool cools the account and later lets it recover on its own.
 *   <li>{@link InvalidCredential} — the credential itself is wrong or dead (bad password, expired
 *       token, a suspended or deleted account). No cooldown can fix this, so it is <b>not</b> a
 *       reputation signal: reporting it would only cool the account and then retry the same dead
 *       login forever. The caller should {@code blockPermanently} the account instead.
 * </ul>
 *
 * <p>Modelled as a {@code sealed} interface so a caller's {@code switch} over the two cases is
 * exhaustive without a {@code default} — the "this is a terminal bad credential" case cannot be
 * silently ignored, and adding a third case later turns every such {@code switch} into a compile
 * error until it is handled.
 */
public sealed interface AccountProbe {

    /** A transient, reputational result to report to the pool; drives cooling and recovery. */
    record Reputational(Outcome outcome) implements AccountProbe {
        /**
         * @throws NullPointerException if {@code outcome} is null
         */
        public Reputational {
            Objects.requireNonNull(outcome, "outcome must not be null");
        }
    }

    /**
     * The credential is permanently invalid; the caller should {@code blockPermanently} the account
     * rather than report it, because no cooldown will make a wrong password right. The
     * {@code statusCode} that triggered the verdict is carried for logging and diagnosis.
     */
    record InvalidCredential(int statusCode) implements AccountProbe {}
}
