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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The audit trail's age-based retention configuration: keep {@code maxAge} of history, trim the rest
 * via {@code purger}. Handing one of these to {@link AdvisorServer} is what turns retention on —
 * without it the trail keeps its original unbounded, never-purged behavior, so bounding the trail is
 * strictly opt-in.
 *
 * <p>The purger is a seam rather than a concrete trail on purpose: in production it is
 * {@code PostgresAuditTrail::purgeOlderThan}, while the server's lifecycle tests substitute a
 * recording lambda and stay Docker-free — the same pattern as the checkpoint's fake store. Retention
 * itself stays an operational concern of the composition root; neither the core nor the
 * {@code EventSink} port knows it exists.
 *
 * <p>The bound is honest at the margin, not exact: a row is purged once it is older than the cutoff
 * and no younger-stamped row precedes it in the trail's insertion order, so the effective retention
 * upper bound is {@code maxAge + max emitter timestamp skew + one purge period} — milliseconds of
 * skew and an hour of period against a typically days-long {@code maxAge}.
 *
 * @param maxAge how much history to keep; events older than {@code now - maxAge} are purged; must be
 *     positive
 * @param purger the mechanism that deletes everything older than a cutoff and reports how many rows
 *     went
 */
public record AuditRetention(Duration maxAge, Purger purger) {

    /** The purge mechanism: delete everything strictly older than {@code cutoff}, return the count. */
    @FunctionalInterface
    public interface Purger {
        long purgeOlderThan(Instant cutoff);
    }

    public AuditRetention {
        Objects.requireNonNull(maxAge, "maxAge must not be null");
        Objects.requireNonNull(purger, "purger must not be null");
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must be positive, was " + maxAge);
        }
    }
}
