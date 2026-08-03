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
 * What happened when a resource was used: {@code {"result": "SUCCESS", "latency": "PT0.12S"}} or
 * {@code {"result": "FAILURE", "failureType": "TIMEOUT", "latency": "PT5S"}}.
 *
 * <p>The domain models this as a sealed {@code Outcome} and the proto contract as a {@code oneof}; JSON
 * gets a flat shape with a discriminator instead of a nested wrapper object, because that is what a REST
 * client reads and writes idiomatically — and because a wrapper would put the same information one level
 * deeper for no gain.
 *
 * @param result {@code "SUCCESS"} or {@code "FAILURE"}, case-sensitive
 * @param latency how long the attempt took, as an ISO-8601 duration; never negative
 * @param failureType the {@code FailureType} constant name; required for {@code FAILURE}, and must be
 *     absent for {@code SUCCESS} — a success with a failure type is a contradiction, not a value to
 *     silently drop
 */
public record OutcomeDto(String result, String latency, String failureType) {}
