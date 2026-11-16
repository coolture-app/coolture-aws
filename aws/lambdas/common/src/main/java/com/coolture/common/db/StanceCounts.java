package com.coolture.common.db;

/**
 * How many marked a post Interesuje mnie and Wezmę udział, selected as {@code stances.interested_count} and
 * {@code stances.going_count}. {@code posts.participant_count} adds the two together, and cards show them apart.
 * Needs the post as {@code p}.
 */
public final class StanceCounts {
    public static final String JOIN = """
        LEFT JOIN LATERAL (
            SELECT count(*) FILTER (WHERE sp.type = 'INTERESTED') AS interested_count,
                   count(*) FILTER (WHERE sp.type = 'TAKES_PART') AS going_count
            FROM post_participations sp
            WHERE sp.post_id = p.id
        ) stances ON true
        """;

    private StanceCounts() {}
}
