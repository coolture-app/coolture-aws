package com.coolture.common.db;

import java.util.List;

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
     */
    public static String shown(List<String> kinds, List<String> asked) {
        if (kinds == null || kinds.isEmpty()) {
            return null;
        }
        if (asked != null) {
            for (String kind : kinds) {
                if (asked.contains(kind)) {
                    return kind;
                }
            }
        }
        return kinds.get(0);
    }

    /** Dla dzieci: derived only when precise enough to narrow any other kind, as a switch in Filtry. */
    public static final String KIDS = "kids";

    private PostKinds() {}
}
