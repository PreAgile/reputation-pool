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

import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;

/**
 * An external account the adapter can lease and evaluate, and the translation of it into the core's
 * opaque {@link ResourceId}.
 *
 * <p>An account is a leasable resource in its own right — a <em>sibling</em> of a proxy, not a part
 * of one. The same engine that cools and recovers a proxy endpoint cools and recovers an account:
 * lease one, use it (log in, call an API), and if the platform rate-limits or locks it, report the
 * failure so the pool rests it and hands out another.
 *
 * <p>The identity that reputation accrues to is composed only of the <em>stable</em> parts of an
 * account — its {@code provider} (the platform the account lives on, e.g. {@code "instagram"}) and
 * its {@code handle} (the stable account identifier on that platform, e.g. a username or account
 * id). A rotating session token, cookie, or password is deliberately <b>not</b> a field: keying on
 * a secret that changes every login would spawn a new single-use cell each time and no reputation
 * would ever accumulate. Reputation belongs to the account, not to the token used to authenticate
 * it once.
 *
 * <p>Both parts are required: the same {@code handle} on two providers ({@code instagram|user_kim}
 * vs {@code github|user_kim}) is two distinct accounts, so an account without a provider is
 * ambiguous by construction.
 *
 * <p>The core never parses this value — it only uses it as a map key — so the encoding is the
 * adapter's private concern.
 */
public record AccountCredential(String provider, String handle) {

    /**
     * @throws IllegalArgumentException if {@code provider} or {@code handle} is null, blank, or
     *     contains {@code '|'} (the id separator — letting it into a field would let two different
     *     accounts encode to the same {@link ResourceId} and share one reputation cell)
     */
    public AccountCredential {
        provider = requireStable(provider, "provider");
        handle = requireStable(handle, "handle");
    }

    private static String requireStable(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        if (value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(name + " must not contain '|'");
        }
        return value;
    }

    /**
     * This account's identity as the core sees it: {@code ACCOUNT} kind with a composite value over
     * the stable parts. The value is opaque to the core.
     *
     * @return the resource id for this account
     */
    public ResourceId toResourceId() {
        return new ResourceId(ResourceKind.ACCOUNT, provider + "|" + handle);
    }
}
