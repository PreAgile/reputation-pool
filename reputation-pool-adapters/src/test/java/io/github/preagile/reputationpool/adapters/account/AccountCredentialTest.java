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

import io.github.preagile.reputationpool.core.domain.ResourceKind;
import org.junit.jupiter.api.Test;

class AccountCredentialTest {

    @Test
    void toResourceIdComposesTheStableTupleAsAnAccountId() {
        var account = new AccountCredential("instagram", "user_kim");
        var id = account.toResourceId();
        assertThat(id.kind()).isEqualTo(ResourceKind.ACCOUNT);
        assertThat(id.value()).isEqualTo("instagram|user_kim");
    }

    @Test
    void theSameHandleOnDifferentProvidersAreDistinctAccounts() {
        var insta = new AccountCredential("instagram", "user_kim");
        var github = new AccountCredential("github", "user_kim");
        assertThat(insta.toResourceId()).isNotEqualTo(github.toResourceId());
    }

    @Test
    void aRotatingSessionTokenIsNotPartOfTheIdentity() {
        // there is deliberately no password/token/cookie field: two logins to the same account map to
        // the same id, so reputation accrues to the account rather than to a one-shot session token
        var a = new AccountCredential("github", "user_kim");
        var b = new AccountCredential("github", "user_kim");
        assertThat(a.toResourceId()).isEqualTo(b.toResourceId());
    }

    @Test
    void rejectsTheDelimiterInEveryTextualField() {
        // '|' is the id separator: letting it into a field would allow two different accounts to
        // encode to the same ResourceId and share one reputation cell
        assertThatThrownBy(() -> new AccountCredential("git|hub", "user_kim"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountCredential("github", "user|kim"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankOrNullComponents() {
        assertThatThrownBy(() -> new AccountCredential(null, "user_kim")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountCredential("github", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountCredential("  ", "user_kim")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AccountCredential("github", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
