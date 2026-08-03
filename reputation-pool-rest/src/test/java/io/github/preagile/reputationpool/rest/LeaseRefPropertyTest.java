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
import java.util.Base64;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * The lease id's invariants. Three of them make it safe to put in a URL and to trust on the way back:
 *
 * <ol>
 *   <li><b>Round-trip.</b> Whatever went in comes back out, including resource values and contexts full of
 *       the characters that break naive delimiter-joined encodings — {@code /}, {@code :}, {@code %},
 *       whitespace, and non-ASCII text. The generators here are deliberately nastier than
 *       {@code DomainArbitraries}: those model realistic identifiers, and this is the one place where an
 *       unrealistic one must still be handled rather than mangled.
 *   <li><b>Injectivity.</b> Distinct references never collide on one id. A collision would let a caller
 *       address a lease it does not hold, which is exactly what the fencing token exists to prevent.
 *   <li><b>Totality and canonicity of decoding.</b> Arbitrary text either decodes — canonically, so
 *       {@code encode(decode(id))} is {@code id} — or raises {@link IllegalArgumentException}, never
 *       anything else. Lease ids arrive from URLs, so hostile and truncated input is the normal case, and a
 *       second exception type would leak out as a {@code 500}.
 * </ol>
 */
class LeaseRefPropertyTest {

    @Property
    void everyReferenceRoundTrips(@ForAll("references") LeaseRef ref) {
        assertThat(LeaseRef.decode(ref.encode())).isEqualTo(ref);
    }

    /** The whole point of base64url: the id needs no escaping to sit in a path segment. */
    @Property
    void everyEncodingIsASafePathSegment(@ForAll("references") LeaseRef ref) {
        assertThat(ref.encode()).matches("[A-Za-z0-9_-]+");
    }

    @Property
    void distinctReferencesNeverShareAnId(@ForAll("references") LeaseRef first, @ForAll("references") LeaseRef second) {
        Assume.that(!first.equals(second));

        assertThat(first.encode()).isNotEqualTo(second.encode());
    }

    /**
     * Decoding is total, and whatever it accepts it accepts canonically: {@code encode(decode(id))} is
     * {@code id}, or the id was rejected. Nothing but {@link IllegalArgumentException} may escape — a
     * {@code NullPointerException}, {@code EOFException}, or {@code StringIndexOutOfBoundsException} from a
     * hand-crafted URL would reach the client as a {@code 500}, telling it the server is broken when its
     * own id was.
     *
     * <p>Fed base64url text built from arbitrary bytes rather than arbitrary characters: an arbitrary
     * {@code String} is rejected by the base64 decoder almost every time, so it never exercises the version
     * check, the length-prefixed reads, or the trailing-byte check. This generator reaches all of them.
     */
    @Property
    void base64TextEitherDecodesCanonicallyOrIsRejectedAsBadInput(@ForAll("encodedBytes") String text) {
        try {
            LeaseRef decoded = LeaseRef.decode(text);
            assertThat(decoded.encode()).isEqualTo(text);
        } catch (IllegalArgumentException expected) {
            // the only permitted failure
        }
    }

    /** The same totality over text that is mostly not base64 at all — the hostile-URL case. */
    @Property
    void arbitraryTextIsNeverRejectedWithSomeOtherExceptionType(@ForAll String text) {
        try {
            LeaseRef.decode(text);
        } catch (IllegalArgumentException expected) {
            // the only permitted failure
        }
    }

    /**
     * A truncated id is rejected, not read as a shorter lease. The cut is derived from the encoded length so
     * the whole range is covered — including the case that actually matters, an id missing only its last
     * character or two, where the fencing token is partially present and a weaker decoder could accept a
     * structurally plausible different lease.
     */
    @Property
    void aTruncatedIdIsRejected(@ForAll("references") LeaseRef ref, @ForAll("fractions") double fraction) {
        String encoded = ref.encode();
        int cut = (int) (encoded.length() * fraction);
        Assume.that(cut < encoded.length());

        assertThatThrownBy(() -> LeaseRef.decode(encoded.substring(0, cut)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Every id missing exactly its last character, and its last two — the near-complete truncations. */
    @Property
    void anIdMissingItsLastCharactersIsRejected(@ForAll("references") LeaseRef ref) {
        String encoded = ref.encode();

        assertThatThrownBy(() -> LeaseRef.decode(encoded.substring(0, encoded.length() - 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LeaseRef.decode(encoded.substring(0, encoded.length() - 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Cut points spread across the whole id, with the near-complete end weighted in. */
    @Provide
    Arbitrary<Double> fractions() {
        return Arbitraries.doubles().between(0.0, 1.0);
    }

    /** Base64url text over arbitrary bytes, so the decoder's later stages are actually reached. */
    @Provide
    Arbitrary<String> encodedBytes() {
        return Arbitraries.bytes()
                .array(byte[].class)
                .ofMinSize(0)
                .ofMaxSize(48)
                .map(bytes -> Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /**
     * Values and contexts drawn from a hostile alphabet: path separators, percent signs, quotes, spaces,
     * and non-ASCII. All are legal domain values — the domain only forbids blank — so the encoding has to
     * survive them.
     */
    @Provide
    Arbitrary<LeaseRef> references() {
        Arbitrary<String> awkward = Arbitraries.strings()
                .withChars('/', ':', '%', '?', '#', '&', '=', '+', ' ', '"', '\\', '\n')
                .withCharRange('a', 'z')
                .withChars('가', '나', 'é', 'ß', '漢')
                .ofMinLength(1)
                .ofMaxLength(40)
                .filter(value -> !value.isBlank());
        return Combinators.combine(Arbitraries.of(ResourceKind.values()), awkward, awkward, Arbitraries.longs())
                .as((kind, value, context, token) ->
                        new LeaseRef(new ResourceId(kind, value), new Context(context), token));
    }
}
