package com.coolture.feed;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.ResultSetMappers;
import com.coolture.common.dto.PostCardDto;
import com.coolture.repository.PostSql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FeedRepository {

    /** What the feed may show. The ranked feed and its fallback both use exactly this. */
    private static final String FEED_RULES = "p.deleted_at IS NULL\n"
        + "  AND " + PostSql.NOT_ENDED + "\n"
        + "  AND p.created_at <= ?::timestamptz\n"
        + "  AND " + PostSql.VISIBLE + "\n"
        + "  AND " + PostSql.NOT_BLOCKED + "\n"
        + """
          AND p.author_id IS DISTINCT FROM ?::uuid
          AND NOT EXISTS (SELECT 1 FROM post_reactions r WHERE r.post_id = p.id AND r.user_id = ?::uuid)
          AND NOT EXISTS (SELECT 1 FROM post_participations r WHERE r.post_id = p.id AND r.user_id = ?::uuid)
        """;

    private static final String FEATURES = """
        SELECT p.id,
               p.starts_at,
               COALESCE(p.positive_reaction_count, 0) + COALESCE(p.participant_count, 0) AS engagement,
               1 - (pe.embedding <=> (SELECT embedding FROM user_embeddings WHERE user_id = ?::uuid)) AS similarity,
               ST_Distance(el.coordinates,
                           ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography) AS meters
        FROM posts p
        LEFT JOIN post_embeddings pe ON pe.post_id = p.id
        LEFT JOIN event_locations el ON el.id = p.event_location_id
        WHERE
        """ + FEED_RULES;

    /** One row per candidate. Has its own timeout, so slow ranking falls back to the upcoming order instead of holding up the page. */
    public List<FeedScorer.Facts> findCandidates(UUID viewer, Instant asOf, Double latitude, Double longitude) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FEATURES)) {

            stmt.setQueryTimeout(FeedConfig.QUERY_TIMEOUT_SECONDS);

            ParamBinder b = new ParamBinder(stmt, conn);
            b.bindNullableUUID(viewer)
             .bindDouble(longitude)
             .bindDouble(latitude);
            bindRules(b, viewer, asOf);

            List<FeedScorer.Facts> candidates = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new FeedScorer.Facts(
                        (UUID) rs.getObject("id"),
                        ResultSetMappers.mapInstant(rs, "starts_at"),
                        rs.getInt("engagement"),
                        (Double) rs.getObject("similarity"),
                        (Double) rs.getObject("meters")
                    ));
                }
            }
            return candidates;
        }
    }

    /** The fallback order: soonest first, continuing after the given start time and id when they're set. */
    public List<PostCardDto> findUpcoming(UUID viewer, Instant asOf, Instant afterStartsAt, UUID afterId, int limit) throws SQLException {
        boolean continues = afterStartsAt != null;
        String sql = PostSql.CARD_SELECT
            + "WHERE " + FEED_RULES
            + (continues ? "  AND (p.starts_at, p.id) > (?::timestamptz, ?::uuid)\n" : "")
            + "ORDER BY p.starts_at ASC, p.id ASC\n"
            + "LIMIT ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ParamBinder b = new ParamBinder(stmt, conn);
            b.bindNullableUUID(viewer)
             .bindNullableUUID(viewer);
            bindRules(b, viewer, asOf);
            if (continues) {
                b.bindTimestamp(afterStartsAt)
                 .bindUUID(afterId);
            }
            b.bindInt(limit);

            return readCards(stmt);
        }
    }

    /** In no particular order. A post deleted since it was ranked just isn't returned. */
    public List<PostCardDto> findCards(UUID viewer, List<UUID> ids) throws SQLException {
        String sql = PostSql.CARD_SELECT + "WHERE p.id = ANY(?::uuid[])";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            new ParamBinder(stmt, conn)
                .bindNullableUUID(viewer)
                .bindNullableUUID(viewer)
                .bindUUIDArray(ids.toArray(new UUID[0]));

            return readCards(stmt);
        }
    }

    private static void bindRules(ParamBinder b, UUID viewer, Instant asOf) throws SQLException {
        b.bindTimestamp(asOf);
        // Visible, then not blocked
        b.bindNullableUUID(viewer)
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer);
        // Not their own, not reacted to, not participating
        b.bindNullableUUID(viewer)
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer);
    }

    private static List<PostCardDto> readCards(PreparedStatement stmt) throws SQLException {
        List<PostCardDto> cards = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                cards.add(ResultSetMappers.mapPostCard(rs));
            }
        }
        return cards;
    }
}
