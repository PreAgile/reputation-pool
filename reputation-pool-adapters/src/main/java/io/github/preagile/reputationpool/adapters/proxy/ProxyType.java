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

/**
 * The kind of proxy, by how its egress IP is sourced. This is adapter-specific vocabulary and lives
 * here, not in the core: the core is resource-kind-agnostic and treats a resource's identity as an
 * opaque value, so proxy-specific concepts belong to the proxy adapter.
 */
public enum ProxyType {
    /** A datacenter IP: stable, cheap, easiest to detect and block. */
    DATACENTER,
    /** An ISP-assigned (static residential) IP: stable and far less suspicious than datacenter. */
    ISP,
    /** A residential IP from a peer network: usually rotating, so identity keys on the gateway/session. */
    RESIDENTIAL,
    /** A mobile carrier IP: shared behind CGNAT, rotating, the least likely to be blocked. */
    MOBILE
}
