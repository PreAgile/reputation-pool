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
package io.github.preagile.reputationpool.core.port;

import io.github.preagile.reputationpool.core.domain.PoolEvent;

/**
 * Where the pool's {@link PoolEvent}s flow out of the core. The core emits facts and nothing more;
 * how they are logged, counted, or streamed is the concern of the implementation, which lives in an
 * outer module. This inverts the dependency — core declares the contract, the adapter fulfils it —
 * so core never takes on a logging or metrics framework.
 *
 * <p>Implementations receive events on the thread that produced them and should not block; buffering
 * or fan-out is their responsibility, not the pool's.
 */
public interface EventSink {

    /**
     * Emits one event.
     *
     * @param event the fact that just occurred; never null
     */
    void emit(PoolEvent event);
}
