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

import io.github.preagile.reputationpool.core.domain.Context;
import io.github.preagile.reputationpool.core.domain.ResourceId;
import io.github.preagile.reputationpool.core.domain.ResourceKind;
import io.github.preagile.reputationpool.core.pool.Lease;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * The three things needed to act on a lease — which resource, in which context, under which fencing
 * token — and their encoding as one opaque, URL-safe {@code leaseId}.
 *
 * <p>This exists because REST needs a name for a lease and gRPC does not. Over gRPC the client echoes a
 * whole {@code LeaseHandle} message back on renew and release; over HTTP the lease has to be addressable
 * as {@code /v1/leases/{leaseId}}, which means one path segment.
 *
 * <p>Encoding is length-prefixed binary (via {@link DataOutputStream#writeUTF}) wrapped in unpadded
 * base64url. Two properties follow, and both matter:
 *
 * <ul>
 *   <li><b>No delimiter to escape.</b> A resource value or context may legally contain {@code /},
 *       {@code :}, whitespace, or non-ASCII text. A {@code kind:value:context:token} string would need
 *       escaping rules that are easy to get subtly wrong; length prefixes make the question disappear.
 *   <li><b>No percent-encoding to get wrong.</b> base64url output is already a valid path segment, so
 *       the id survives proxies, redirects, and logs unchanged.
 * </ul>
 *
 * <p>A leading format version byte means the encoding can change later without a client having to care —
 * an id from an older server is recognized and rejected precisely, rather than misparsed into a
 * plausible-looking wrong lease.
 *
 * <p><strong>Opaque, not secret.</strong> Clients must treat the id as a token and never parse it. It is
 * not a capability: the fencing token is a small monotonic counter, so ids are guessable, and knowing one
 * is enough to release someone else's lease. That is equally true of the gRPC {@code LeaseHandle} — the
 * pool has no notion of caller identity, so authentication and authorization belong to the host in front
 * of it. Signing the id would only move that responsibility, not discharge it.
 */
record LeaseRef(ResourceId resource, Context context, long token) {

    /**
     * The current encoding version, written as the first byte. Bump it when the layout below changes;
     * a decoder that meets an unknown version rejects the id instead of guessing at its fields.
     */
    private static final byte FORMAT_VERSION = 1;

    LeaseRef {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(context, "context must not be null");
    }

    /** The reference to the lease {@code lease} grants — everything but its timestamps. */
    static LeaseRef of(Lease lease) {
        Objects.requireNonNull(lease, "lease must not be null");
        return new LeaseRef(lease.resource(), lease.context(), lease.token());
    }

    /**
     * This reference as one URL-safe path segment.
     *
     * @return the opaque lease id
     * @throws IllegalArgumentException if the resource value or context is too long to encode (the
     *     modified-UTF-8 limit is 65535 bytes each) — far beyond any real identifier, but a limit that
     *     must fail loudly rather than truncate
     */
    String encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(FORMAT_VERSION);
            out.writeUTF(resource.kind().name());
            out.writeUTF(resource.value());
            out.writeUTF(context.value());
            out.writeLong(token);
        } catch (IOException e) {
            // ByteArrayOutputStream cannot fail on write, so the only reachable cause is writeUTF
            // rejecting an over-long string: a malformed input, not a broken stream.
            throw new IllegalArgumentException("lease reference is too long to encode", e);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }

    /**
     * Reads back an id produced by {@link #encode()}.
     *
     * <p>Total on its input: every malformed id — bad base64, an unknown version, a truncated body,
     * trailing bytes, an unknown kind, a blank value — leaves as one
     * {@link IllegalArgumentException}. That is what lets the handler map "unparseable lease id" to a
     * {@code 404} without a second kind of failure leaking through as a {@code 500}.
     *
     * @param leaseId the opaque id from a previous {@code acquire}
     * @return the decoded reference
     * @throws IllegalArgumentException if {@code leaseId} is not a well-formed lease id
     * @throws NullPointerException if {@code leaseId} is null
     */
    static LeaseRef decode(String leaseId) {
        Objects.requireNonNull(leaseId, "leaseId must not be null");
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(leaseId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed lease id", e);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported lease id format version: " + version);
            }
            ResourceKind kind = kindOf(in.readUTF());
            String value = in.readUTF();
            String context = in.readUTF();
            long token = in.readLong();
            if (in.read() != -1) {
                // Extra bytes mean this is not an id we produced; accepting the prefix would turn a
                // corrupted or hand-crafted id into a valid reference to a real lease.
                throw new IllegalArgumentException("malformed lease id: trailing bytes");
            }
            // The domain constructors are the last gate: a blank value or context is rejected here.
            return new LeaseRef(new ResourceId(kind, value), new Context(context), token);
        } catch (IOException e) {
            // A truncated id: the stream ran out mid-field. ByteArrayInputStream has no other failure.
            throw new IllegalArgumentException("malformed lease id: truncated", e);
        }
    }

    private static ResourceKind kindOf(String name) {
        try {
            return ResourceKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed lease id: unknown resource kind " + name, e);
        }
    }

    /**
     * Rebuilds the full {@link Lease} this reference points at, given the timestamps the client sent
     * back. Used to hand a domain value to {@code ResourcePool.renew} / {@code release}, both of which
     * authorize on the fencing token alone — so the timestamps are carried for completeness, and a
     * client cannot extend its own lease by inventing an {@code expiresAt}.
     *
     * @throws IllegalArgumentException if {@code expiresAt} is before {@code leasedAt}
     * @throws NullPointerException if either timestamp is null
     */
    Lease toLease(Instant leasedAt, Instant expiresAt) {
        return new Lease(resource, context, token, leasedAt, expiresAt);
    }

    /**
     * Deliberately field-free. A resource value is exactly the kind of detail — a proxy endpoint, an
     * account name — that should not land in a log line or an error message a caller might see, and the
     * default record {@code toString} would print all of it. Tests assert on the components, not on this.
     */
    @Override
    public String toString() {
        return "LeaseRef[opaque]";
    }
}
