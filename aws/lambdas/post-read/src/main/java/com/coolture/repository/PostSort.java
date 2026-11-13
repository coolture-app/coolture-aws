package com.coolture.repository;

/** The orders of GET /posts. Each order continues from a keyset, so paging can't repeat or lose a post. */
public enum PostSort {

    RECENT("(p.created_at, p.id) < (?::timestamptz, ?::uuid)", "p.created_at DESC, p.id DESC"),
    POPULAR("(COALESCE(p.positive_reaction_count, 0), p.id) < (?::int, ?::uuid)", "COALESCE(p.positive_reaction_count, 0) DESC, p.id DESC"),
    UPCOMING("(p.starts_at, p.id) > (?::timestamptz, ?::uuid)", "p.starts_at ASC, p.id ASC"),
    /**
     * Nearest first, from the request's position, posts with a place only. The cursor holds just the last id: its
     * distance is measured again here, with the same expression, so the comparison is exact.
     */
    NEAREST(
        "(ST_Distance(el.coordinates, " + PostFilterSql.POINT + "), p.id) > ("
            + "(SELECT ST_Distance(el2.coordinates, " + PostFilterSql.POINT + ") FROM posts p2"
            + " JOIN event_locations el2 ON el2.id = p2.event_location_id WHERE p2.id = ?::uuid), ?::uuid)",
        "ST_Distance(el.coordinates, " + PostFilterSql.POINT + ") ASC, p.id ASC"),
    /**
     * The viewer's newest stance first, among the participation types asked for. The cursor holds just the last id, and
     * its stance time is read again here. If that stance was removed in the meantime, the next page is empty.
     */
    STANCE_NEWEST(
        "(pp_my.created_at, p.id) < ("
            + "(SELECT created_at FROM post_participations WHERE post_id = ?::uuid AND user_id = ?::uuid), ?::uuid)",
        "pp_my.created_at DESC, p.id DESC");

    private final String keysetSql;
    private final String orderSql;

    PostSort(String keysetSql, String orderSql) {
        this.keysetSql = keysetSql;
        this.orderSql = orderSql;
    }

    public static PostSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return RECENT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown sortBy");
        }
    }

    public String keysetSql() {
        return keysetSql;
    }

    public String orderSql() {
        return orderSql;
    }

    /** Whether the order needs the request's position. */
    public boolean needsPosition() {
        return this == NEAREST;
    }

    /** Whether the order needs the viewer's participation filter, as it orders by that stance. */
    public boolean needsStances() {
        return this == STANCE_NEWEST;
    }
}
