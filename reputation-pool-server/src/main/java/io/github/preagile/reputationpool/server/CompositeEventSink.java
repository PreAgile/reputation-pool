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
package io.github.preagile.reputationpool.server;

import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.port.EventSink;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * An {@link EventSink} that fans each event out to several sinks — the live gRPC stream and the
 * durable audit trail side by side. The pool takes exactly one sink, deliberately: composing multiple
 * consumers is an adapter concern, solved here outside the core rather than by widening the pool to a
 * list of sinks.
 *
 * <p>Delegates are isolated from each other: one sink throwing must not starve the ones after it, so a
 * {@link RuntimeException} is logged and swallowed per delegate. Each delegate keeps its own
 * non-blocking discipline (bounded queues, background writers); this class only sequences the calls on
 * the emitting thread.
 */
final class CompositeEventSink implements EventSink {

    private static final Logger LOG = System.getLogger(CompositeEventSink.class.getName());

    private final List<EventSink> delegates;

    /**
     * @param delegates the sinks to fan out to, called in list order; never null or empty
     * @throws IllegalArgumentException if {@code delegates} is empty
     */
    CompositeEventSink(List<EventSink> delegates) {
        this.delegates = List.copyOf(delegates);
        if (this.delegates.isEmpty()) {
            throw new IllegalArgumentException("delegates must not be empty");
        }
    }

    @Override
    public void emit(PoolEvent event) {
        for (EventSink delegate : delegates) {
            try {
                delegate.emit(event);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "event sink " + delegate.getClass().getName() + " failed to accept an event", e);
            }
        }
    }
}
