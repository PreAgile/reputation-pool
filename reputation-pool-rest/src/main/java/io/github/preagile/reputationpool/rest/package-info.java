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
 * The REST/JSON face of the pool — a second surface over the same engine, for the callers gRPC cannot
 * reach: a script with no generated stub, a browser dashboard, an operator with {@code curl}.
 *
 * <p>The contract lives in {@code openapi.yaml} on this module's classpath, the way {@code advisor.proto}
 * is the contract for the gRPC surface: hand-written, reviewed, and pinned by consumers — not generated
 * from whatever the code happens to do. This first phase ships the contract and the boundary translation
 * ({@code RestMapping}, {@code LeaseRef}, {@code Json}); the request handler and the HTTP binding follow
 * in their own changes, so the contract is locked before anything is built against it.
 *
 * <p><strong>This module never depends on {@code ...grpc}.</strong> Driving REST off the generated proto
 * messages would force protobuf-JSON on the wire ({@code "latency": "3s"}) and weld the two contracts
 * together, so each surface owns its DTOs and its mapping. An ArchUnit rule fails the build on the import.
 *
 * <h2>Where REST deliberately differs from gRPC</h2>
 *
 * <p>The gRPC surface follows one rule: what the domain expresses as a value, the wire expresses as a
 * value — an empty pool is a successful call returning {@code granted: false}. REST does not inherit that
 * rule, because HTTP has a second channel the domain does not: the status code. Retry middleware,
 * gateways, and circuit breakers branch on it and never read the body, so "nothing to lend" reported as
 * {@code 200} is invisible to every layer between client and server.
 *
 * <table border="1">
 *   <caption>The two surfaces on the same domain outcome</caption>
 *   <tr><th>Domain outcome</th><th>gRPC</th><th>REST</th></tr>
 *   <tr><td>acquire granted</td><td>{@code granted: true}</td><td>{@code 201} + {@code Location}</td></tr>
 *   <tr><td>acquire found nothing</td><td>{@code granted: false}</td><td>{@code 409} + problem+json</td></tr>
 *   <tr><td>renew refused</td><td>{@code renewed: false}</td><td>{@code 409}</td></tr>
 *   <tr><td>release of a lost lease</td><td>{@code released: false}</td><td>{@code 404}</td></tr>
 *   <tr><td>malformed input</td><td>{@code INVALID_ARGUMENT}</td><td>{@code 400}</td></tr>
 * </table>
 *
 * <p>Acquire answers {@code 409} rather than {@code 503}: finding nothing to lend is a normal, expected
 * outcome of a healthy pool, and counting it as a server error would pollute the error-rate SLO and can
 * get a perfectly healthy node ejected by a load balancer.
 */
package io.github.preagile.reputationpool.rest;
