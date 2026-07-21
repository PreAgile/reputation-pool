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
package io.github.preagile.reputationpool.adapters.event;

import io.github.preagile.reputationpool.core.domain.PoolEvent;
import io.github.preagile.reputationpool.core.port.EventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link EventSink} that logs each pool event via SLF4J — the simplest possible adapter for the
 * observability port, and a demonstration that the core stays free of any logging framework: the
 * dependency on SLF4J lives here, outside the core.
 *
 * <p>Formatting switches over the sealed {@link PoolEvent} exhaustively, so when the core gains a new
 * event kind this class stops compiling until the new case is handled — the log can't silently drop
 * an event it never learned about.
 */
public final class Slf4jEventSink implements EventSink {

    private static final Logger LOG = LoggerFactory.getLogger(Slf4jEventSink.class);

    @Override
    public void emit(PoolEvent event) {
        if (isPerRequest(event)) {
            // per-request events at INFO would flood production logs; the guard also skips the
            // format cost when debug logging is off
            if (LOG.isDebugEnabled()) {
                LOG.debug("{}", format(event));
            }
        } else {
            LOG.info("{}", format(event));
        }
    }

    /**
     * Whether this event fires once per request (lease churn) rather than on a state transition. An
     * exhaustive switch — like {@link #format}, a new event kind stops compiling until its log level
     * is decided here, instead of silently defaulting to INFO.
     */
    static boolean isPerRequest(PoolEvent event) {
        return switch (event) {
            case PoolEvent.ResourceLeased e -> true;
            case PoolEvent.LeaseReleased e -> true;
            case PoolEvent.AcquisitionRejected e -> true;
            case PoolEvent.ResourceCooled e -> false;
            case PoolEvent.ResourceRecovered e -> false;
            case PoolEvent.ResourceBlocklisted e -> false;
            case PoolEvent.ResourceUnblocked e -> false;
        };
    }

    /** Renders an event as a human-readable line. Package-private so it can be asserted without a log capture. */
    static String format(PoolEvent event) {
        return switch (event) {
            case PoolEvent.ResourceCooled e ->
                "cooled " + e.resource() + " in " + e.context() + " until " + e.until() + " (" + e.cause() + ")";
            case PoolEvent.ResourceRecovered e -> "recovered " + e.resource() + " in " + e.context();
            case PoolEvent.ResourceBlocklisted e -> "blocklisted " + e.resource() + " until " + e.until();
            case PoolEvent.ResourceUnblocked e -> "unblocked " + e.resource();
            case PoolEvent.ResourceLeased e -> "leased " + e.resource() + " for " + e.context() + " until " + e.until();
            case PoolEvent.LeaseReleased e -> "released lease on " + e.resource() + " for " + e.context();
            case PoolEvent.AcquisitionRejected e -> "rejected acquire for " + e.context();
        };
    }
}
