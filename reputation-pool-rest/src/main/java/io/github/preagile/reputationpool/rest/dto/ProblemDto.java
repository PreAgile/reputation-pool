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
package io.github.preagile.reputationpool.rest.dto;

/**
 * The one error shape of this surface, per <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>,
 * served as {@code application/problem+json}. Every non-2xx response uses it, so a client writes one
 * error parser rather than one per endpoint.
 *
 * @param type a stable URI identifying the problem kind — the field a client should branch on, since it
 *     survives rewording of the human-readable fields
 * @param title a short, human-readable summary of the problem kind
 * @param status the HTTP status code, repeated in the body so a logged or forwarded payload is
 *     self-contained
 * @param detail what went wrong with <em>this</em> request; safe to show a developer, and deliberately
 *     free of internals a caller could not act on
 */
public record ProblemDto(String type, String title, int status, String detail) {}
