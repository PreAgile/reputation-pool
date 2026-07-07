/**
 * reputation-pool-core — a pure-Java reputation decision engine.
 *
 * <p>This package depends on no framework, network, or storage (JDK only). Decisions are pure
 * functions, state is expressed as immutable records, and the only contact with the outside world
 * (time, storage, probing, observability) is through interfaces in the {@code port} subpackage. This
 * purity is enforced at build time by an ArchUnit rule.
 */
package io.github.preagile.reputationpool.core;
