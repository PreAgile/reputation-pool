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
 * The I/O boundary: interfaces the outside world must provide, expressed in domain terms only. Core
 * defines the contract here and calls it; the implementation (logging, metrics, storage, probing)
 * lives in an outer module, so the dependency arrow points inward and core stays pure.
 *
 * <p>An ArchUnit rule keeps this package honest — a port may depend only on the JDK and
 * {@code domain}, never on the decision logic in {@code engine} or the {@code pool} layer built on
 * top of it.
 */
package io.github.preagile.reputationpool.core.port;
