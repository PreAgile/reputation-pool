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
 * reputation-pool-core — a pure-Java reputation decision engine.
 *
 * <p>This package depends on no framework, network, or storage (JDK only). Decisions are pure
 * functions, state is expressed as immutable records, and the only contact with the outside world
 * (time, storage, probing, observability) is through interfaces in the {@code port} subpackage. This
 * purity is enforced at build time by an ArchUnit rule.
 */
package io.github.preagile.reputationpool.core;
