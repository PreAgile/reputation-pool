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

/**
 * The wire shapes of the REST surface, one record per JSON body in {@code openapi.yaml}.
 *
 * <p>Three rules make these records what they are:
 *
 * <ul>
 *   <li><b>They carry no annotations and no library types.</b> Jackson binds records by component name
 *       out of the box, so nothing here mentions Jackson — the JSON library stays confined to {@code
 *       Json}, and swapping it would leave this package untouched. An ArchUnit rule enforces it.
 *   <li><b>Every field is a {@code String} or another DTO.</b> Instants and durations travel as
 *       ISO-8601 text ({@code "2026-08-03T10:15:30.123456789Z"}, {@code "PT0.12S"}), which keeps
 *       nanosecond precision, needs no Jackson date module, and is what a non-JVM client expects to
 *       read. Enums travel as their domain constant names.
 *   <li><b>They validate nothing.</b> A DTO is allowed to hold a nonsense combination — that is what
 *       makes it a faithful picture of whatever a client sent. Validation happens in {@code
 *       RestMapping} on the way to the domain, where the domain constructors reject it.
 * </ul>
 */
package io.github.preagile.reputationpool.rest.dto;
