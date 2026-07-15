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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.PoolSnapshot;
import io.github.preagile.reputationpool.core.port.ResourceStore;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the audit-retention wiring the composition root owns in persistent mode: with an
 * {@link AuditRetention} configured, the server periodically asks the purger to trim everything older
 * than {@code clock.instant() - maxAge}; without one, the audit trail keeps today's unbounded,
 * never-purged behavior.
 *
 * <p>Docker-free by design, mirroring {@link AdvisorServerPersistenceTest}: the purger is a recording
 * lambda and assertions ride the {@code purgeExpiredAuditEvents()} hook directly, never the scheduler,
 * so the tests are deterministic and carry no timing. The cutoff must come from the injected
 * {@link Clock}, which {@code Clock.fixed} makes assertable to the nanosecond.
 */
class AdvisorServerAuditRetentionTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration TTL = Duration.ofSeconds(30);
    private static final Duration THIRTY_DAYS = Duration.ofDays(30);

    @Test
    @DisplayName("the purge hook trims everything older than now minus the retention, with 'now' read"
            + " from the injected clock")
    void purgeHook_trimsEverythingOlderThanNowMinusRetention() {
        List<Instant> askedCutoffs = new ArrayList<>();
        AdvisorServer server = serverWithRetention(new AuditRetention(THIRTY_DAYS, cutoff -> {
            askedCutoffs.add(cutoff);
            return 0;
        }));

        server.purgeExpiredAuditEvents();

        assertThat(askedCutoffs)
                .as("cutoff = clock.instant() - retention, from the injected clock, not the wall clock")
                .containsExactly(NOW.minus(THIRTY_DAYS));
    }

    @Test
    @DisplayName("a failing purge is isolated like a failing checkpoint: logged, swallowed, and retried"
            + " on the next interval instead of killing the schedule")
    void failingPurge_isIsolatedAndRetriedNextInterval() {
        List<Instant> attempts = new ArrayList<>();
        AdvisorServer server = serverWithRetention(new AuditRetention(THIRTY_DAYS, cutoff -> {
            attempts.add(cutoff);
            throw new RuntimeException("database went away");
        }));

        // scheduleAtFixedRate cancels all future runs the first time a task throws, so the hook must
        // never let the failure escape — same contract as checkpoint().
        assertThatCode(server::purgeExpiredAuditEvents).doesNotThrowAnyException();
        assertThatCode(server::purgeExpiredAuditEvents).doesNotThrowAnyException();

        assertThat(attempts)
                .as("each interval retries; a bad purge is skipped, not fatal")
                .hasSize(2);
    }

    @Test
    @DisplayName("with no retention configured nothing is ever purged — the unbounded audit trail"
            + " behavior stays exactly as before (opt-in knob)")
    void noRetention_purgeHookIsANoOp() throws IOException, InterruptedException {
        List<Object> audited = new ArrayList<>();
        AdvisorServer server =
                AdvisorServer.create(0, CLOCK, new Random(42), TTL, new FakeResourceStore(), audited::add);

        // No retention: the hook is a no-op and the full lifecycle runs without a purge task.
        assertThatCode(() -> {
                    server.purgeExpiredAuditEvents();
                    server.start();
                    server.shutdown(Duration.ofSeconds(10));
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with retention configured the full lifecycle runs cleanly — the purge task rides the"
            + " checkpointer scheduler from start() to shutdown()")
    void retentionConfigured_lifecycleSchedulesAndStopsThePurgeTask() throws IOException, InterruptedException {
        AdvisorServer server = serverWithRetention(new AuditRetention(THIRTY_DAYS, cutoff -> 0));

        assertThatCode(() -> {
                    server.start();
                    server.shutdown(Duration.ofSeconds(10));
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AuditRetention rejects a zero or negative max age in the constructor — invalid"
            + " configuration cannot exist")
    void auditRetention_rejectsNonPositiveMaxAge() {
        assertThatThrownBy(() -> new AuditRetention(Duration.ZERO, cutoff -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditRetention(Duration.ofDays(-1), cutoff -> 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AuditRetention rejects null components")
    void auditRetention_rejectsNulls() {
        assertThatThrownBy(() -> new AuditRetention(null, cutoff -> 0)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuditRetention(THIRTY_DAYS, null)).isInstanceOf(NullPointerException.class);
    }

    private static AdvisorServer serverWithRetention(AuditRetention retention) {
        return AdvisorServer.create(0, CLOCK, new Random(42), TTL, new FakeResourceStore(), event -> {}, retention);
    }

    /** The same one-field fake as {@link AdvisorServerPersistenceTest}: enough to enter persistent mode. */
    private static final class FakeResourceStore implements ResourceStore {

        private PoolSnapshot saved;

        @Override
        public void save(PoolSnapshot snapshot) {
            this.saved = snapshot;
        }

        @Override
        public Optional<PoolSnapshot> load() {
            return Optional.ofNullable(saved);
        }
    }
}
