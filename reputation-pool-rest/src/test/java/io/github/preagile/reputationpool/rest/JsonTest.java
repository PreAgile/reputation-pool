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

import io.github.preagile.reputationpool.rest.dto.AcquireLeaseRequest;
import io.github.preagile.reputationpool.rest.dto.ResourceIdDto;
import org.junit.jupiter.api.Test;

/**
 * The codec's strictness, case by case. Each of these is a place where a lenient parser would trade a loud
 * {@code 400} for a silent wrong answer, which is the failure mode that costs a caller hours: the request
 * was accepted, so nothing looks broken, and the value it thought it sent was never recorded.
 */
class JsonTest {

    @Test
    void aRecordIsBoundByComponentNameWithNoAnnotations() {
        AcquireLeaseRequest request = Json.read("{\"context\":\"naver\"}", AcquireLeaseRequest.class);

        assertThat(request.context()).isEqualTo("naver");
    }

    /**
     * A misspelled field is the client's bug, and the client can only learn that from a rejection. Accepting
     * the body would leave the field at its default and report success.
     */
    @Test
    void anUnknownFieldIsRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> Json.read("{\"context\":\"naver\",\"contxet\":\"typo\"}", AcquireLeaseRequest.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed request body");
    }

    /** Two objects in one body is one malformed request, not the first object with the rest discarded. */
    @Test
    void contentAfterTheJsonDocumentIsRejected() {
        assertThatThrownBy(
                        () -> Json.read("{\"context\":\"naver\"} {\"context\":\"other\"}", AcquireLeaseRequest.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syntacticallyBrokenJsonIsRejected() {
        assertThatThrownBy(() -> Json.read("{\"context\":", AcquireLeaseRequest.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWrongJsonTypeForAFieldIsRejected() {
        assertThatThrownBy(() -> Json.read("{\"context\":{\"value\":\"naver\"}}", AcquireLeaseRequest.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAbsentBodyIsRejected() {
        assertThatThrownBy(() -> Json.read(null, AcquireLeaseRequest.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a request body is required");
    }

    @Test
    void aBlankBodyIsRejected() {
        assertThatThrownBy(() -> Json.read("   \n", AcquireLeaseRequest.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The literal {@code null} is valid JSON and not a valid request. Caught here so it cannot travel on as
     * a null DTO and surface later as a {@code NullPointerException} — that is, as a {@code 500}.
     */
    @Test
    void aBodyOfTheLiteralNullIsRejected() {
        assertThatThrownBy(() -> Json.read("null", AcquireLeaseRequest.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a request body is required");
    }

    @Test
    void nullFieldsAreOmittedFromTheOutput() {
        assertThat(Json.write(new ResourceIdDto("PROXY", null))).isEqualTo("{\"kind\":\"PROXY\"}");
    }

    /**
     * Jackson's own message names the offending field and position — precisely what a client needs to fix
     * its request — and carries no server internals, so it is safe to forward as the problem detail.
     */
    @Test
    void theRejectionExplainsWhatWasWrongWithTheBody() {
        assertThatThrownBy(() -> Json.read("{\"unexpected\":1}", AcquireLeaseRequest.class))
                .hasMessageContaining("unexpected");
    }
}
