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

/**
 * The kind of a leasable resource.
 *
 * <p>This is a <b>closed vocabulary</b>: the engine never branches on the kind (it is metadata that
 * namespaces a {@link ResourceId}, not a decision input), so it is modelled as an enum for a fixed,
 * documented set rather than for switch-exhaustiveness. Adding a kind is therefore a deliberate
 * library change and a release concern — callers extend the pool by implementing a resource for an
 * existing kind, not by inventing new kinds at the call site.
 */
public enum ResourceKind {
    /** A proxy endpoint (identified by {@code host:port}). */
    PROXY,

    /** An external account (identified by an account id). */
    ACCOUNT,

    /** A browser or client session. */
    SESSION
}
