package io.github.preagile.reputationpool.core.domain;

/**
 * The kind of a leasable resource.
 *
 * <p>This is a <b>closed vocabulary</b>: the engine never branches on the kind (it is metadata that
 * namespaces a {@link ResourceId}, not a decision input), so it is modelled as an enum for a fixed,
 * documented set rather than for switch-exhaustiveness. Adding a kind is therefore a deliberate
 * library change and a release concern — callers extend the pool by implementing a resource for an
 * existing kind, not by inventing new kinds at the call site.
 */
public enum ResourceKind {
    /** A proxy endpoint (identified by {@code host:port}). */
    PROXY,

    /** An external account (identified by an account id). */
    ACCOUNT,

    /** A browser or client session. */
    SESSION
}
