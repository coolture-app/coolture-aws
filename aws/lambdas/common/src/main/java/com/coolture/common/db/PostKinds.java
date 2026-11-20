package com.coolture.common.db;

/**
 * A post's kinds of event, best first, as the post-embedding worker derived them from its vector; null until it has.
 * Every read of a post's kinds goes through {@link #OF_POST}, so an owner's correction can take precedence here alone.
 * Needs the post as {@code p}.
 */
public final class PostKinds {
    public static final String OF_POST = "(SELECT rk.kinds FROM rec_post_kinds rk WHERE rk.post_id = p.id)";

    /** Dla dzieci: derived only when precise enough to narrow any other kind, as a switch in Filtry. */
    public static final String KIDS = "kids";

    private PostKinds() {}
}
