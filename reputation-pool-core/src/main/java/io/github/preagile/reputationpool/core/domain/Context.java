package io.github.preagile.reputationpool.core.domain;

/**
 * The scope a reputation applies to — typically a platform (e.g. a marketplace).
 *
 * <p>A failure observed in one context only moves that context's cell; another context's reputation
 * for the same resource is untouched. This isolation is what keeps a block on one platform from
 * poisoning the pool everywhere.
 *
 * <p>{@link #GLOBAL} is the reserved base axis of the two-layer score model
 * ({@code effective = globalBase + contextDelta}): it carries the per-resource signal shared across
 * every context, while other {@code Context} values carry the per-context behavioural delta.
 */
public record Context(String value) {

    /** The base axis shared across all contexts — the {@code globalBase} term of the score model. */
    public static final Context GLOBAL = new Context("*");

    public Context {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be null or blank");
        }
    }
}
