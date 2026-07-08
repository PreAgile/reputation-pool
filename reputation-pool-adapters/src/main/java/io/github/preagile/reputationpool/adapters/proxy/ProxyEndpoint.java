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

import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.util.Objects;

/**
 * A proxy the adapter can lease and evaluate, and the translation of it into the core's opaque
 * {@link ResourceId}.
 *
 * <p>The identity that reputation accrues to is composed only of the <em>stable</em> parts of a proxy —
 * vendor, {@link ProxyType type}, the gateway {@code host:port}, and an optional sticky
 * {@code session}. A rotating egress IP is deliberately <b>not</b> a field: keying on an IP that
 * changes per request would spawn a new single-use cell every time and no reputation would ever
 * accumulate. The gateway (and sticky session, when pinned) is the thing that actually stays put.
 *
 * <p>The core never parses this value — it only uses it as a map key — so the encoding is the
 * adapter's private concern.
 */
public record ProxyEndpoint(String vendor, ProxyType type, String host, int port, String session) {

    /**
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code vendor} or {@code host} is blank, or {@code port} is
     *     outside {@code 1..65535}
     */
    public ProxyEndpoint {
        Objects.requireNonNull(type, "type must not be null");
        if (vendor == null || vendor.isBlank()) {
            throw new IllegalArgumentException("vendor must not be null or blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in 1..65535");
        }
        // session is optional: null or blank means "no sticky session"
    }

    /**
     * This endpoint's identity as the core sees it: {@code PROXY} kind with a composite value over the
     * stable parts. The value is opaque to the core.
     *
     * @return the resource id for this endpoint
     */
    public ResourceId toResourceId() {
        String value = vendor + "|" + type + "|" + host + ":" + port;
        if (session != null && !session.isBlank()) {
            value += "|" + session;
        }
        return new ResourceId(ResourceKind.PROXY, value);
    }
}
