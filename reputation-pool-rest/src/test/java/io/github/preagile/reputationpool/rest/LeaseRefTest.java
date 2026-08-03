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
package io.github.preagile.reputationpool.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.pool.Lease;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * The lease id as a client and an attacker each see it: opaque, path-safe, and unforgiving of anything it
 * did not produce.
 */
class LeaseRefTest {

    private static final Instant AT = Instant.parse("2026-07-08T00:00:00Z");
    private static final ResourceId PROXY = new ResourceId(ResourceKind.PROXY, "10.0.0.7:8080");
    private static final Context NAVER = new Context("naver");

    @Test
    void aLeaseReducesToTheThreeThingsNeededToActOnIt() {
        Lease lease = new Lease(PROXY, NAVER, 42L, AT, AT.plusSeconds(30));

        assertThat(LeaseRef.of(lease)).isEqualTo(new LeaseRef(PROXY, NAVER, 42L));
    }

    /**
     * A resource value containing the characters that would break a delimiter-joined id — a slash and a
     * colon are the everyday case for a proxy URL — comes back intact and needs no percent-encoding.
     */
    @Test
    void anIdSurvivesAResourceValueFullOfUrlSyntax() {
        LeaseRef ref = new LeaseRef(new ResourceId(ResourceKind.SESSION, "https://host/a:b?c=d"), NAVER, 1L);

        String encoded = ref.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(LeaseRef.decode(encoded)).isEqualTo(ref);
    }

    @Test
    void theExtremeFencingTokensRoundTrip() {
        for (long token : new long[] {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE}) {
            LeaseRef ref = new LeaseRef(PROXY, NAVER, token);

            assertThat(LeaseRef.decode(ref.encode())).isEqualTo(ref);
        }
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> LeaseRef.decode("not a lease id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed lease id");
    }

    @Test
    void anEmptyIdIsRejected() {
        assertThatThrownBy(() -> LeaseRef.decode("")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The version byte earns its place here: an id from a future encoding is refused outright rather than
     * misread into a plausible-looking reference to the wrong lease.
     */
    @Test
    void anIdFromAnUnknownFormatVersionIsRefused() {
        String encoded = encodeWithVersion((byte) 99, "PROXY", "10.0.0.7:8080", "naver", 1L);

        assertThatThrownBy(() -> LeaseRef.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported lease id format version: 99");
    }

    /** Extra bytes mean the id is not ours; accepting its prefix would turn a forgery into a valid lease. */
    @Test
    void anIdWithTrailingBytesIsRefused() {
        byte[] valid = Base64.getUrlDecoder().decode(new LeaseRef(PROXY, NAVER, 1L).encode());
        byte[] padded = new byte[valid.length + 1];
        System.arraycopy(valid, 0, padded, 0, valid.length);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(padded);

        assertThatThrownBy(() -> LeaseRef.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing bytes");
    }

    /**
     * The JDK's URL decoder accepts padding our encoder never writes, so {@code "…Q"} and {@code "…Q="}
     * would otherwise both name one lease. One lease, one id: the non-canonical spelling is refused so the
     * identity in the string is as strong as the identity in the fields.
     */
    @Test
    void aPaddedIdIsRefusedEvenThoughItDecodesToTheSameFields() {
        String canonical = new LeaseRef(PROXY, NAVER, 1L).encode();
        byte[] bytes = Base64.getUrlDecoder().decode(canonical);
        String padded = Base64.getUrlEncoder().encodeToString(bytes); // with padding

        // only meaningful when the payload length actually produces padding
        assertThat(padded).isNotEqualTo(canonical).endsWith("=");
        assertThatThrownBy(() -> LeaseRef.decode(padded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a canonical encoding");
    }

    @Test
    void anIdNamingAResourceKindThatDoesNotExistIsRefused() {
        String encoded = encodeWithVersion((byte) 1, "TELEPORTER", "somewhere", "naver", 1L);

        assertThatThrownBy(() -> LeaseRef.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown resource kind");
    }

    /** The domain constructors are the last gate, so an id built around a blank value cannot get through. */
    @Test
    void anIdCarryingABlankResourceValueIsRefused() {
        String encoded = encodeWithVersion((byte) 1, "PROXY", "   ", "naver", 1L);

        assertThatThrownBy(() -> LeaseRef.decode(encoded)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anIdCarryingABlankContextIsRefused() {
        String encoded = encodeWithVersion((byte) 1, "PROXY", "p1", " ", 1L);

        assertThatThrownBy(() -> LeaseRef.decode(encoded)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The resource behind an id is the kind of detail that must not turn up in a log line or a stack trace
     * a caller might see, so the record's fields stay out of {@code toString}.
     */
    @Test
    void aReferenceDoesNotPrintWhatItPointsAt() {
        // Asserted as an exact value, not merely "does not contain the resource": an empty or accidentally
        // blanked toString would satisfy the weaker check while making a log line useless.
        assertThat(new LeaseRef(PROXY, NAVER, 1L)).hasToString("LeaseRef[opaque]");
    }

    /** Builds an id by hand so the decoder can be tested against bytes it would never produce itself. */
    private static String encodeWithVersion(byte version, String kind, String value, String context, long token) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(version);
            out.writeUTF(kind);
            out.writeUTF(value);
            out.writeUTF(context);
            out.writeLong(token);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }
}
