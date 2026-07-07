package io.github.preagile.reputationpool.core.domain;

/**
 * The lifecycle state of a resource within a single context.
 *
 * <p>These are pure labels with no attached data, so this is an enum rather than a sealed type.
 * State-specific data — such as when a cooldown ends — is not modelled here but carried on the
 * reputation cell, keeping the state a small comparable vocabulary and the data in one place.
 *
 * <p>The normal cycle is {@code HEALTHY -> COOLING -> RECOVERING -> HEALTHY}. {@link #BLOCKLISTED}
 * is reachable from any state on a hard-block signal and is left only by release (a TTL or a manual
 * action).
 */
public enum ResourceState {

    /** Selectable and trusted. */
    HEALTHY,

    /** Excluded from selection until its cooldown expires. */
    COOLING,

    /** On probation after cooldown — re-validated with limited traffic before being promoted back. */
    RECOVERING,

    /** Isolated and never selectable until released. */
    BLOCKLISTED
}
