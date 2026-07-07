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
package io.github.preagile.reputationpool.core.domain;

/**
 * The kind of failure observed when using a resource.
 *
 * <p>Unlike {@link ResourceKind}, the engine <b>does</b> branch on this type: the cooldown policy
 * keys its base duration on the failure kind, because different failures warrant different
 * treatment (an active block should cool for far longer than a transient timeout). Adding a case
 * here therefore surfaces every unhandled {@code switch} at compile time.
 *
 * <p>Classification is the <b>caller's</b> responsibility. The core does not inspect traffic; the
 * client SDK maps its own errors (socket codes, TLS errors, HTTP status, block-page signatures,
 * latency thresholds) onto these types and reports them. In particular {@code SLOW} — a successful
 * response that was too slow — is a client-side judgement, not something the engine re-derives.
 */
public enum FailureType {

    /** Connection forcibly closed (e.g. {@code ECONNRESET}); often transient, possibly a block. */
    CONNECTION_RESET,

    /** TLS negotiation failed or was refused; a stronger hint of blocking than a plain reset. */
    TLS_HANDSHAKE,

    /** No timely response ({@code ETIMEDOUT} and similar); usually transient congestion. */
    TIMEOUT,

    /** The platform actively rejected the request (403/429, block page, CAPTCHA); cool the longest. */
    BLOCKED,

    /** The call succeeded but was slower than the caller's threshold; the lightest signal. */
    SLOW
}
