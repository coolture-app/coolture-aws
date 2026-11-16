package com.coolture.common.db;

/**
 * How many marked a post Interesuje mnie and Biorę udział, live from {@code post_participations}, selected as
 * {@code stances.interested_count}, {@code stances.going_count} and their sum {@code stances.participant_count}. The sum
 * is counted here too rather than read from {@code posts.participant_count}, so a card's total always matches its two
 * badges; the stored column stays for the feed's engagement score. Needs the post as {@code p}.
 */
public final class StanceCounts {
    public static final String JOIN = """
        LEFT JOIN LATERAL (
            SELECT count(*) FILTER (WHERE sp.type = 'INTERESTED') AS interested_count,
                   count(*) FILTER (WHERE sp.type = 'TAKES_PART') AS going_count,
                   count(*) FILTER (WHERE sp.type IN ('INTERESTED', 'TAKES_PART')) AS participant_count
            FROM post_participations sp
            WHERE sp.post_id = p.id
        ) stances ON true
        """;

    private StanceCounts() {}
}
