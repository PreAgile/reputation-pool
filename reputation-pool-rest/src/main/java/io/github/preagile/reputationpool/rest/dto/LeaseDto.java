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
 * A granted lease as the client sees it. {@code leaseId} is the only part the client sends back (as the
 * path segment of {@code /v1/leases/{leaseId}}); the rest is what the client needs in order to actually
 * use the resource and to know when the hold lapses.
 *
 * <p>{@code resource} and {@code context} are redundant with the information encoded inside
 * {@code leaseId} — deliberately. The client must be told which resource it just leased, and requiring
 * it to decode an id we declare opaque would defeat the point of it being opaque. On the way back in,
 * {@code leaseId} is the authority: if the two disagree, the request is rejected rather than one of the
 * two silently winning.
 *
 * @param leaseId the opaque, path-safe lease reference; treat as a token, never parse it
 * @param resource the resource that was leased
 * @param context the context the lease was granted for
 * @param leasedAt when the lease was granted, as an ISO-8601 instant
 * @param expiresAt when the lease lapses unless renewed, as an ISO-8601 instant; expiry is exclusive,
 *     so the lease is still held at any instant strictly before this
 */
public record LeaseDto(String leaseId, ResourceIdDto resource, String context, String leasedAt, String expiresAt) {}
