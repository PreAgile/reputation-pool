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

import io.github.preagile.reputationpool.core.domain.ResourceKind;
import org.junit.jupiter.api.Test;

class ProxyEndpointTest {

    @Test
    void toResourceIdComposesTheStableTupleAsAProxyId() {
        var endpoint = new ProxyEndpoint("brightdata", ProxyType.RESIDENTIAL, "gw.kr.example.com", 22225, null);
        var id = endpoint.toResourceId();
        assertThat(id.kind()).isEqualTo(ResourceKind.PROXY);
        assertThat(id.value()).isEqualTo("brightdata|RESIDENTIAL|gw.kr.example.com:22225");
    }

    @Test
    void aStickySessionIsPartOfTheIdentity() {
        var without = new ProxyEndpoint("v", ProxyType.MOBILE, "gw", 8080, null);
        var with = new ProxyEndpoint("v", ProxyType.MOBILE, "gw", 8080, "s-abc");
        assertThat(with.toResourceId().value()).isEqualTo("v|MOBILE|gw:8080|s-abc");
        assertThat(with.toResourceId()).isNotEqualTo(without.toResourceId());
    }

    @Test
    void identityIsTheStableTupleOnly() {
        // there is deliberately no rotating-egress-IP field: two endpoints with the same stable tuple
        // map to the same id, so reputation accrues to the gateway rather than to a one-shot exit IP
        var a = new ProxyEndpoint("v", ProxyType.RESIDENTIAL, "gw", 8080, null);
        var b = new ProxyEndpoint("v", ProxyType.RESIDENTIAL, "gw", 8080, null);
        assertThat(a.toResourceId()).isEqualTo(b.toResourceId());
    }

    @Test
    void aBlankSessionIsTreatedAsNoSession() {
        var blank = new ProxyEndpoint("v", ProxyType.ISP, "gw", 8080, "  ");
        assertThat(blank.toResourceId().value()).isEqualTo("v|ISP|gw:8080");
    }

    @Test
    void aSelfHostedProxyHasNoVendorAndKeepsAnEmptyVendorSlot() {
        // the leading slot stays in the encoding so a vendor-less id can never collide with a
        // vendored one whose vendor happens to equal the type string
        var selfHosted = new ProxyEndpoint(null, ProxyType.DATACENTER, "squid.internal", 3128, null);
        assertThat(selfHosted.toResourceId().value()).isEqualTo("|DATACENTER|squid.internal:3128");
    }

    @Test
    void aBlankVendorNormalizesToNullSoRecordEqualityMatchesIdentity() {
        var blank = new ProxyEndpoint("  ", ProxyType.DATACENTER, "squid.internal", 3128, null);
        var absent = new ProxyEndpoint(null, ProxyType.DATACENTER, "squid.internal", 3128, null);
        assertThat(blank).isEqualTo(absent);
        assertThat(blank.toResourceId()).isEqualTo(absent.toResourceId());
    }

    @Test
    void aBlankSessionNormalizesToNullSoRecordEqualityMatchesIdentity() {
        var blank = new ProxyEndpoint("v", ProxyType.ISP, "gw", 8080, "  ");
        var absent = new ProxyEndpoint("v", ProxyType.ISP, "gw", 8080, null);
        assertThat(blank).isEqualTo(absent);
        assertThat(blank.hashCode()).isEqualTo(absent.hashCode());
    }

    @Test
    void rejectsTheDelimiterInEveryTextualField() {
        // '|' is the id separator: letting it into a field would allow two different endpoints
        // to encode to the same ResourceId and share one reputation cell
        assertThatThrownBy(() -> new ProxyEndpoint("v|MOBILE|h:80", ProxyType.MOBILE, "h", 80, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProxyEndpoint("v", ProxyType.MOBILE, "h|x", 80, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProxyEndpoint("v", ProxyType.MOBILE, "h", 80, "MOBILE|h:80"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidComponents() {
        assertThatThrownBy(() -> new ProxyEndpoint("v", null, "gw", 8080, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProxyEndpoint("v", ProxyType.ISP, "", 8080, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProxyEndpoint("v", ProxyType.ISP, "gw", 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProxyEndpoint("v", ProxyType.ISP, "gw", 70000, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
