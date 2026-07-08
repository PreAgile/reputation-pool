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
 * an optional vendor, {@link ProxyType type}, the gateway {@code host:port}, and an optional sticky
 * {@code session}. A rotating egress IP is deliberately <b>not</b> a field: keying on an IP that
 * changes per request would spawn a new single-use cell every time and no reputation would ever
 * accumulate. The gateway (and sticky session, when pinned) is the thing that actually stays put.
 * Vendor is absent for self-hosted proxies (an in-house Squid, an internal gateway), where the
 * concept does not exist; {@code host:port} alone already makes the identity unique.
 *
 * <p>The core never parses this value — it only uses it as a map key — so the encoding is the
 * adapter's private concern.
 */
public record ProxyEndpoint(String vendor, ProxyType type, String host, int port, String session) {

    /**
     * Optional fields normalize on construction: a blank {@code vendor} or {@code session} collapses
     * to {@code null}, so record equality always agrees with {@link #toResourceId()} equality.
     *
     * @throws NullPointerException if {@code type} is null
     * @throws IllegalArgumentException if {@code host} is blank, {@code port} is outside
     *     {@code 1..65535}, or any textual field contains {@code '|'} (the id separator — letting it
     *     into a field would let two different endpoints encode to the same {@link ResourceId})
     */
    public ProxyEndpoint {
        Objects.requireNonNull(type, "type must not be null");
        vendor = normalizeOptional(vendor, "vendor");
        session = normalizeOptional(session, "session");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be null or blank");
        }
        if (host.indexOf('|') >= 0) {
            throw new IllegalArgumentException("host must not contain '|'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in 1..65535");
        }
    }

    private static String normalizeOptional(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.indexOf('|') >= 0) {
            throw new IllegalArgumentException(name + " must not contain '|'");
        }
        return value;
    }

    /**
     * This endpoint's identity as the core sees it: {@code PROXY} kind with a composite value over the
     * stable parts. The value is opaque to the core.
     *
     * <p>A missing vendor keeps its empty leading slot ({@code |TYPE|host:port}) so a vendor-less id
     * can never collide with a vendored one.
     *
     * @return the resource id for this endpoint
     */
    public ResourceId toResourceId() {
        String value = (vendor != null ? vendor : "") + "|" + type + "|" + host + ":" + port;
        if (session != null) {
            value += "|" + session;
        }
        return new ResourceId(ResourceKind.PROXY, value);
    }
}
