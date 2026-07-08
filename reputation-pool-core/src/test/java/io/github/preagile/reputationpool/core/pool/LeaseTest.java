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
package io.github.preagile.reputationpool.core.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LeaseTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final Context CTX = new Context("cpeats");
    private static final Instant NOW = Instant.parse("2026-07-08T00:00:00Z");

    @Test
    void isNotExpiredBeforeItsExpiryAndExpiredAtOrAfter() {
        var lease = new Lease(RID, CTX, 1L, NOW, NOW.plusSeconds(60));
        assertThat(lease.isExpired(NOW)).isFalse();
        assertThat(lease.isExpired(NOW.plusSeconds(59))).isFalse();
        assertThat(lease.isExpired(NOW.plusSeconds(60))).isTrue(); // exclusive boundary
        assertThat(lease.isExpired(NOW.plusSeconds(61))).isTrue();
    }

    @Test
    void rejectsExpiresAtBeforeLeasedAt() {
        assertThatThrownBy(() -> new Lease(RID, CTX, 1L, NOW, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullComponents() {
        assertThatThrownBy(() -> new Lease(null, CTX, 1L, NOW, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Lease(RID, null, 1L, NOW, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Lease(RID, CTX, 1L, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Lease(RID, CTX, 1L, NOW, null)).isInstanceOf(NullPointerException.class);
    }
}
