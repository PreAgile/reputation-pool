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
package io.github.preagile.reputationpool.core.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A test {@link Clock} whose instant can be set or advanced, driving time-dependent behavior
 * (cooldown expiry, lease TTL) deterministically — the shared fixture behind the "time is injected"
 * rule. Prefer {@link Clock#fixed} when a test never moves time; use this when it must.
 *
 * <p>The instant is {@code volatile} so a time step made on the test thread is visible to worker
 * threads in concurrency tests.
 */
public final class SettableClock extends Clock {

    private volatile Instant now;

    public SettableClock(Instant now) {
        this.now = Objects.requireNonNull(now, "now must not be null");
    }

    /** Moves the clock to {@code now}. */
    public void set(Instant now) {
        this.now = Objects.requireNonNull(now, "now must not be null");
    }

    /** Moves the clock forward by {@code amount}. */
    public void advance(Duration amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        this.now = now.plus(amount);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
