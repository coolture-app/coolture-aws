package com.coolture.common.db;

/**
 * A post's kinds of event, best first, as the post-embedding worker derived them from its vector; null until it has.
 * Every read of a post's kinds goes through {@link #OF_POST}, so an owner's correction can take precedence here alone.
 * Needs the post as {@code p}.
 */
public final class PostKinds {
    public static final String OF_POST = "(SELECT rk.kinds FROM rec_post_kinds rk WHERE rk.post_id = p.id)";

    /**
     * The one kind a map pin names: the first of the post's kinds among those the filter asks for, so a filtered pin
     * says why the post is there, and the first otherwise. A post found as its second kind would else show the other.
     * Binds the asked kinds as one {@code varchar[]}, null for none.
     */
    public static final String SHOWN = "COALESCE((SELECT k FROM unnest(" + OF_POST
        + ") WITH ORDINALITY AS u(k, n) WHERE k = ANY(?::varchar[]) ORDER BY n LIMIT 1), " + OF_POST + "[1])";

    /** Dla dzieci: derived only when precise enough to narrow any other kind, as a switch in Filtry. */
    public static final String KIDS = "kids";

    private PostKinds() {}
}
