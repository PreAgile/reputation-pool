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
package io.github.preagile.reputationpool.server;

import io.github.preagile.reputationpool.core.engine.AdaptiveCooldownPolicy;
import io.github.preagile.reputationpool.core.engine.ReputationEngine;
import io.github.preagile.reputationpool.core.pool.ResourcePool;
import io.github.preagile.reputationpool.core.pool.WeightedRandomSelectionStrategy;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

/**
 * The composition root: the one place that assembles real parts — engine, strategy, broadcaster,
 * clock, randomness — into a {@link ResourcePool} and puts the {@link ReputationAdvisorService} on
 * a port. Everything else in this module takes its collaborators through the constructor, so this
 * is also the only place a production default (system clock, seeded-by-entropy random) is chosen;
 * tests assemble the same graph with {@code Clock.fixed(...)} and a seeded generator instead.
 */
public final class AdvisorServer {

    /** Engine tuning mirrors the L1 adapter demos: window 10, cool after 2, recover after 2. */
    private static final int WINDOW_SIZE = 10;

    private static final int COOL_AFTER = 2;
    private static final int RECOVER_AFTER = 2;
    private static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(30);

    private final Server server;
    private final EventBroadcaster broadcaster;

    private AdvisorServer(Server server, EventBroadcaster broadcaster) {
        this.server = server;
        this.broadcaster = broadcaster;
    }

    /** Production assembly: system clock, default randomness, the default lease TTL. */
    public static AdvisorServer create(int port) {
        return create(port, Clock.systemUTC(), RandomGenerator.getDefault(), DEFAULT_LEASE_TTL);
    }

    /** Test-friendly assembly: every source of nondeterminism is handed in by the caller. */
    public static AdvisorServer create(int port, Clock clock, RandomGenerator random, Duration leaseTtl) {
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(random, "random must not be null");
        Objects.requireNonNull(leaseTtl, "leaseTtl must not be null");
        EventBroadcaster broadcaster = new EventBroadcaster();
        ResourcePool pool = new ResourcePool(
                new ReputationEngine(new AdaptiveCooldownPolicy(), WINDOW_SIZE, COOL_AFTER, RECOVER_AFTER),
                new WeightedRandomSelectionStrategy(),
                broadcaster,
                clock,
                random,
                leaseTtl);
        Server server = ServerBuilder.forPort(port)
                .addService(new ReputationAdvisorService(pool, broadcaster))
                .build();
        return new AdvisorServer(server, broadcaster);
    }

    public AdvisorServer start() throws IOException {
        server.start();
        return this;
    }

    /** The bound port; useful when created with port 0 (pick any free port). */
    public int port() {
        return server.getPort();
    }

    /**
     * Orderly shutdown: event streams are completed first so subscribers see a clean end instead of
     * a transport reset, then in-flight RPCs drain within the grace period.
     */
    public void shutdown(Duration grace) throws InterruptedException {
        broadcaster.close();
        server.shutdown();
        if (!server.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
            server.shutdownNow();
            server.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        AdvisorServer advisor = AdvisorServer.create(port).start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                advisor.shutdown(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        advisor.awaitTermination();
    }
}
