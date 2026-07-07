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
package io.github.preagile.reputationpool.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ResourceIdTest {

    @Test
    void holdsKindAndValue() {
        var id = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
        assertThat(id.kind()).isEqualTo(ResourceKind.PROXY);
        assertThat(id.value()).isEqualTo("1.2.3.4:8080");
    }

    @Test
    void sameKindAndValueAreEqual() {
        assertThat(new ResourceId(ResourceKind.ACCOUNT, "acc-1"))
                .isEqualTo(new ResourceId(ResourceKind.ACCOUNT, "acc-1"));
    }

    @Test
    void kindNamespacesValue() {
        // same string, different kind → distinct ids (no key collision across kinds)
        assertThat(new ResourceId(ResourceKind.PROXY, "x")).isNotEqualTo(new ResourceId(ResourceKind.ACCOUNT, "x"));
    }

    @Test
    void rejectsNullKind() {
        assertThatThrownBy(() -> new ResourceId(null, "v")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullOrBlankValue() {
        assertThatThrownBy(() -> new ResourceId(ResourceKind.PROXY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceId(ResourceKind.PROXY, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
