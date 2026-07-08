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
/**
 * The pool layer (M2): the pieces the pool composes to lease resources safely under contention —
 * the blocklist, selection strategy, lease registry, and the pool facade over them.
 *
 * <p>State here stays immutable, as in {@code domain}: each value returns a new instance on change.
 * Concurrency will be confined to atomic operations at the boundary — an {@code AtomicReference} swap
 * for a whole-snapshot value, or a concurrent-map compute for per-key state — never to locks scattered
 * through the logic, so the immutable value carries the correctness and the boundary carries the
 * atomicity. The pool facade that establishes this boundary lands later in M2.
 */
package io.github.preagile.reputationpool.core.pool;
