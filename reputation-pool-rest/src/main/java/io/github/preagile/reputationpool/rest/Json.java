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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The only place in this module that knows JSON exists. Everything else moves DTO records and
 * {@code String} bodies around, so the choice of library is one file wide — an ArchUnit rule pins it here.
 *
 * <p>The mapper is configured to be strict in both directions, because a lenient parser at a public
 * boundary trades a loud {@code 400} for a silent wrong answer:
 *
 * <ul>
 *   <li><b>Unknown properties are rejected.</b> A typo'd or misspelled field name is a {@code 400}, not a
 *       value that quietly stays at its default. This is the difference between a client learning that
 *       {@code "latencly"} is wrong and a client believing it reported a latency it never sent.
 *   <li><b>Trailing content is rejected.</b> {@code {"context":"naver"} {"context":"other"}} is one
 *       malformed request, not the first object with the rest ignored.
 *   <li><b>Nulls are omitted on the way out.</b> The event DTO is a flat union whose inapplicable fields
 *       are null; writing them out as explicit {@code null}s would triple the size of every SSE frame and
 *       invite clients to distinguish "absent" from "null" when there is no such distinction here.
 * </ul>
 *
 * <p>Records need no annotations or extra module: Jackson binds them through their canonical constructor
 * by component name, which is what keeps {@code ...rest.dto} free of library types.
 */
final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            // NON_NULL for values, ALWAYS for content: a null field is dropped, but a null *inside* a
            // collection would be kept — there are none in these DTOs, and silently dropping elements is
            // never what a caller wants.
            .defaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
            .build();

    private Json() {}

    /**
     * Serializes a DTO. Not expected to fail: every DTO in this module is a record of strings and other
     * such records, so a failure here means a programming error (a new, unserializable field), not bad
     * input — hence {@link IllegalStateException} rather than the {@code IllegalArgumentException} the
     * read path uses.
     */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize a response body", e);
        }
    }

    /**
     * Parses a request body into {@code type}.
     *
     * <p>Total on its input: any unparseable, empty, or structurally wrong body leaves as
     * {@link IllegalArgumentException}, which the handler maps to {@code 400}. Nothing about a client's
     * bad JSON should be able to surface as a {@code 500}.
     *
     * @throws IllegalArgumentException if the body is blank or is not valid JSON for {@code type}
     */
    static <T> T read(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("a request body is required");
        }
        try {
            T value = MAPPER.readValue(body, type);
            // A body of the literal `null` parses successfully to null. It is valid JSON and not a valid
            // request, so it is caught here rather than becoming an NPE further in.
            if (value == null) {
                throw new IllegalArgumentException("a request body is required");
            }
            return value;
        } catch (JsonProcessingException e) {
            // Jackson's message names the offending field and position, which is exactly what a client
            // needs; it carries no server internals, so it is safe to pass through to the problem detail.
            throw new IllegalArgumentException("malformed request body: " + e.getOriginalMessage(), e);
        }
    }
}
