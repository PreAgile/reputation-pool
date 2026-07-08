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

import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import java.time.Instant;
import java.util.HashMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class BlocklistTest {

    private static final ResourceId RID = new ResourceId(ResourceKind.PROXY, "1.2.3.4:8080");
    private static final ResourceId OTHER = new ResourceId(ResourceKind.ACCOUNT, "acct-42");
    private static final Instant NOW = Instant.parse("2026-07-07T00:00:00Z");

    @Test
    void freshBlocklistBlocksNothing() {
        assertThat(Blocklist.empty().isBlocked(RID, NOW)).isFalse();
    }

    @Test
    void blockedResourceIsReportedAsBlocked() {
        var blocklist = Blocklist.empty().block(RID, NOW.plusSeconds(60));
        assertThat(blocklist.isBlocked(RID, NOW)).isTrue();
    }

    @Test
    void blockExpiresExactlyAtUntilExclusive() {
        var until = NOW.plusSeconds(60);
        var blocklist = Blocklist.empty().block(RID, until);
        assertThat(blocklist.isBlocked(RID, until.minusSeconds(1))).isTrue(); // still blocked
        assertThat(blocklist.isBlocked(RID, until)).isFalse(); // exclusive boundary
        assertThat(blocklist.isBlocked(RID, until.plusSeconds(1))).isFalse();
    }

    @Test
    void expiredEntryNoLongerBlocks() {
        var blocklist = Blocklist.empty().block(RID, NOW.plusSeconds(10));
        assertThat(blocklist.isBlocked(RID, NOW.plusSeconds(20))).isFalse();
    }

    @Test
    void releaseClearsABlock() {
        var blocklist = Blocklist.empty().block(RID, NOW.plusSeconds(60)).release(RID);
        assertThat(blocklist.isBlocked(RID, NOW)).isFalse();
    }

    @Test
    void releasingAnUnblockedResourceIsANoOp() {
        var empty = Blocklist.empty();
        assertThat(empty.release(RID)).isEqualTo(empty);
    }

    @Test
    void permanentBlockNeverExpires() {
        var blocklist = Blocklist.empty().blockPermanently(RID);
        // even at the far edge of representable time, a permanent block still holds
        assertThat(blocklist.isBlocked(RID, Instant.parse("9999-12-31T23:59:59Z")))
                .isTrue();
    }

    @Test
    void blockingSameResourceTwiceIsIdempotent() {
        var until = NOW.plusSeconds(60);
        var once = Blocklist.empty().block(RID, until);
        var twice = once.block(RID, until);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void reblockingWithALaterUntilExtendsTheBlock() {
        var early = NOW.plusSeconds(60);
        var late = NOW.plusSeconds(3600);
        var blocklist = Blocklist.empty().block(RID, early).block(RID, late);
        // between the old and new expiry the resource is still blocked because the later expiry won
        assertThat(blocklist.isBlocked(RID, NOW.plusSeconds(600))).isTrue();
    }

    @Test
    void blockingOneResourceDoesNotAffectAnother() {
        var blocklist = Blocklist.empty().block(RID, NOW.plusSeconds(60));
        assertThat(blocklist.isBlocked(OTHER, NOW)).isFalse();
    }

    @Test
    void sweepExpiredRemovesOnlyExpiredEntriesAndPreservesIsBlocked() {
        var blocklist = Blocklist.empty()
                .block(RID, NOW.plusSeconds(10)) // expires before the sweep instant
                .block(OTHER, NOW.plusSeconds(600)); // still active at the sweep instant
        var sweepAt = NOW.plusSeconds(60);

        var swept = blocklist.sweepExpired(sweepAt);

        assertThat(swept.entries()).containsOnlyKeys(OTHER);
        // the sweep changes nothing about what is blocked at that instant
        assertThat(swept.isBlocked(RID, sweepAt)).isEqualTo(blocklist.isBlocked(RID, sweepAt));
        assertThat(swept.isBlocked(OTHER, sweepAt)).isEqualTo(blocklist.isBlocked(OTHER, sweepAt));
    }

    @Test
    void entriesSnapshotIsImmutable() {
        var blocklist = Blocklist.empty().block(RID, NOW.plusSeconds(60));
        assertThatThrownBy(() -> blocklist.entries().put(OTHER, NOW)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructorDefensivelyCopiesEntries() {
        var mutable = new HashMap<ResourceId, Instant>();
        mutable.put(RID, NOW.plusSeconds(60));
        var blocklist = new Blocklist(mutable);

        // mutating the source map must not leak into the blocklist
        mutable.put(OTHER, NOW.plusSeconds(60));
        assertThat(blocklist.entries()).containsOnlyKeys(RID);
    }

    @Test
    void rejectsNullArguments() {
        var blocklist = Blocklist.empty();
        assertThatThrownBy(() -> new Blocklist(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entries");
        assertThatThrownBy(() -> blocklist.isBlocked(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> blocklist.isBlocked(RID, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> blocklist.block(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> blocklist.block(RID, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> blocklist.release(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> blocklist.sweepExpired(null)).isInstanceOf(NullPointerException.class);
    }

    // --- invariants, attacked over many generated inputs (jqwik) ---

    @Property
    void aBlockHoldsUntilItsExclusiveExpiry(
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String value,
            @ForAll @LongRange(min = 0, max = 4_000_000_000L) long baseEpoch,
            @ForAll @LongRange(min = 1, max = 1_000_000L) long durationSeconds) {
        var id = new ResourceId(ResourceKind.PROXY, value);
        var base = Instant.ofEpochSecond(baseEpoch);
        var until = base.plusSeconds(durationSeconds);
        var blocklist = Blocklist.empty().block(id, until);

        assertThat(blocklist.isBlocked(id, base)).isTrue();
        assertThat(blocklist.isBlocked(id, until)).isFalse(); // exclusive
        assertThat(blocklist.isBlocked(id, until.plusSeconds(1))).isFalse();
    }

    @Property
    void releaseAlwaysClearsRegardlessOfExpiry(
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String value,
            @ForAll @LongRange(min = 0, max = 4_000_000_000L) long baseEpoch,
            @ForAll @LongRange(min = 1, max = 1_000_000L) long durationSeconds) {
        var id = new ResourceId(ResourceKind.PROXY, value);
        var base = Instant.ofEpochSecond(baseEpoch);
        var blocklist =
                Blocklist.empty().block(id, base.plusSeconds(durationSeconds)).release(id);
        assertThat(blocklist.isBlocked(id, base)).isFalse();
    }

    @Property
    void blockingOneResourceNeverPollutesAnother(
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String value,
            @ForAll @LongRange(min = 0, max = 4_000_000_000L) long baseEpoch,
            @ForAll @LongRange(min = 1, max = 1_000_000L) long durationSeconds) {
        var blocked = new ResourceId(ResourceKind.PROXY, value);
        var untouched = new ResourceId(ResourceKind.PROXY, value + "-other"); // guaranteed distinct
        var base = Instant.ofEpochSecond(baseEpoch);
        var blocklist = Blocklist.empty().block(blocked, base.plusSeconds(durationSeconds));

        assertThat(blocklist.isBlocked(untouched, base)).isFalse();
    }

    @Property
    void sweepNeverChangesWhatIsBlockedAtThatInstant(
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String value,
            @ForAll @LongRange(min = 0, max = 4_000_000_000L) long baseEpoch,
            @ForAll @LongRange(min = 1, max = 1_000_000L) long durationSeconds,
            @ForAll @LongRange(min = 0, max = 2_000_000L) long sweepOffset) {
        var id = new ResourceId(ResourceKind.PROXY, value);
        var base = Instant.ofEpochSecond(baseEpoch);
        var blocklist = Blocklist.empty().block(id, base.plusSeconds(durationSeconds));
        var sweepAt = base.plusSeconds(sweepOffset);

        assertThat(blocklist.sweepExpired(sweepAt).isBlocked(id, sweepAt)).isEqualTo(blocklist.isBlocked(id, sweepAt));
    }
}
