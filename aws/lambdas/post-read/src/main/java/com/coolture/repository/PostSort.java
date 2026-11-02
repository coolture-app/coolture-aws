package com.coolture.repository;

/** The orders of GET /posts. Each order continues from a keyset, so paging can't repeat or lose a post. */
public enum PostSort {

    RECENT("(p.created_at, p.id) < (?::timestamptz, ?::uuid)", "p.created_at DESC, p.id DESC"),
    POPULAR("(COALESCE(p.positive_reaction_count, 0), p.id) < (?::int, ?::uuid)", "COALESCE(p.positive_reaction_count, 0) DESC, p.id DESC"),
    UPCOMING("(p.starts_at, p.id) > (?::timestamptz, ?::uuid)", "p.starts_at ASC, p.id ASC");

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
}
