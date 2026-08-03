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
 * Body of {@code POST /v1/outcomes} — feeds the engine what happened, which is what moves reputation.
 * Reporting is decoupled from leasing on purpose: a caller may report on a resource it no longer holds
 * (the lease lapsed mid-request), and the engine still wants to know.
 *
 * @param resource the resource that was used
 * @param context the context it was used in
 * @param outcome what happened
 */
public record ReportOutcomeRequest(ResourceIdDto resource, String context, OutcomeDto outcome) {}
