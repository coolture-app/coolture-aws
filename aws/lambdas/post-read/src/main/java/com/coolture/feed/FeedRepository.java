package com.coolture.feed;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.ResultSetMappers;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.repository.PostFilterSql;
import com.coolture.repository.PostSql;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FeedRepository {

    /**
     * What the feed may show, narrowed by the person's filters. The ranked feed and its fallback both use exactly this.
     * Ended events only when the filters ask for past events; the fallback, being the upcoming order, never shows them.
     * Bind with bindRules.
     */
    private static String feedRules(boolean showsEnded) {
        return "p.deleted_at IS NULL\n"
            + (showsEnded ? "" : "  AND " + PostSql.NOT_ENDED + "\n")
            + "  AND p.created_at <= ?::timestamptz\n"
            + "  AND " + PostSql.VISIBLE + "\n"
            + "  AND " + PostSql.NOT_BLOCKED + "\n"
            + "  AND " + PostSql.NOT_MINE + "\n"
            + "  AND " + PostFilterSql.MATCHES + "\n";
    }

    private static final String FEATURES = """
        SELECT p.id,
               p.starts_at,
        """ + "       " + PostSql.EVENT_END + " AS ends_at,\n" + """
               COALESCE(p.positive_reaction_count, 0) + COALESCE(p.participant_count, 0) AS engagement,
               CASE WHEN pe.model = ? THEN 1 - ((pe.embedding - ?::vector) <=> ?::vector) END AS similarity,
               ST_Distance(el.coordinates,
                           ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography) AS meters
        FROM posts p
        LEFT JOIN post_embeddings pe ON pe.post_id = p.id
        LEFT JOIN event_locations el ON el.id = p.event_location_id
        WHERE
        """;

    /**
     * The sums UserVector blends, each from one model's vectors. Stances count up to asOf, so every page of a session
     * gets the same vector. Each fades with its age in whole days by asOf, half every STANCE_HALF_LIFE_DAYS: summed per
     * day first, then each day's sum weighed once, so a stance type comes back as one row; past STANCE_MAX_AGE_DAYS
     * they'd weigh next to nothing and are left out. Interests have no time and count as they are. Each row is a part: a stance, with the sum of its fading
     * weights as the amount, the picked interests, one category picked from with all of its interests, or the average of
     * every interest or every post. The post average counts posts created up to asOf, ended and deleted ones too, so
     * nothing done during a session moves it.
     */
    private static final String VECTOR_PARTS = """
        WITH stances AS (
            SELECT post_id, type, created_at FROM post_reactions WHERE user_id = ?::uuid
            UNION ALL
            SELECT post_id, type, created_at FROM post_participations WHERE user_id = ?::uuid
        ), aged AS (
            SELECT s.type, pe.embedding, floor(extract(epoch FROM ?::timestamptz - s.created_at) / 86400) AS age
            FROM stances s
            JOIN post_embeddings pe ON pe.post_id = s.post_id AND pe.model = ?
            WHERE s.type = ANY(?) AND s.created_at <= ?::timestamptz
              AND s.created_at > ?::timestamptz - make_interval(days => ?)
        ), daily AS (
            SELECT type, sum(embedding) AS embedding, count(*) AS stances, power(0.5, age / ?) AS fade
            FROM aged
            GROUP BY type, age
        ), picks AS (
            SELECT i.key, i.category_key, ie.embedding
            FROM user_interests ui
            JOIN interests i ON i.key = ui.interest_key
            JOIN interest_embeddings ie ON ie.interest_key = i.key AND ie.model = ?
            WHERE ui.user_id = ?::uuid
        )
        SELECT type AS part, sum(embedding * array_fill(fade::real, ARRAY[vector_dims(embedding)])::vector)::real[] AS total,
               sum(stances * fade) AS amount
        FROM daily
        GROUP BY type
        UNION ALL
        SELECT 'INTERESTS', sum(embedding)::real[], count(*) FROM picks HAVING count(*) > 0
        UNION ALL
        SELECT 'CATEGORY', sum(ie.embedding)::real[], max(chosen.picks)
        FROM (SELECT category_key, count(*) AS picks FROM picks GROUP BY category_key) chosen
        JOIN interests i ON i.category_key = chosen.category_key
        JOIN interest_embeddings ie ON ie.interest_key = i.key AND ie.model = ?
        GROUP BY chosen.category_key
        UNION ALL
        SELECT 'INTEREST_AVERAGE', avg(embedding)::real[], count(*) FROM interest_embeddings WHERE model = ? HAVING count(*) > 0
        UNION ALL
        SELECT 'POST_AVERAGE', avg(pe.embedding)::real[], count(*)
        FROM post_embeddings pe
        JOIN posts p ON p.id = pe.post_id
        WHERE pe.model = ? AND p.created_at <= ?::timestamptz
        HAVING count(*) > 0
        """;

    private final String model;

    public FeedRepository() {
        this(FeedConfig.EMBEDDING_MODEL);
    }

    public FeedRepository(String model) {
        this.model = model;
    }

    /** Empty for a guest, with no query. */
    public UserVector.Parts findVectorParts(UUID viewer, Instant asOf) throws SQLException {
        List<UserVector.Stance> stances = new ArrayList<>();
        List<UserVector.Category> categories = new ArrayList<>();
        double[] interests = null;
        double[] interestAverage = null;
        double[] postAverage = null;
        if (viewer == null) {
            return new UserVector.Parts(stances, null, categories, null, null);
        }

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(VECTOR_PARTS)) {

            stmt.setQueryTimeout(FeedConfig.QUERY_TIMEOUT_SECONDS);
            new ParamBinder(stmt, conn)
                .bindUUID(viewer)
                .bindUUID(viewer)
                .bindTimestamp(asOf)
                .bindString(model)
                .bindVarcharArray(FeedConfig.STANCE_WEIGHTS.keySet().toArray(new String[0]))
                .bindTimestamp(asOf)
                .bindTimestamp(asOf)
                .bindInt(FeedConfig.STANCE_MAX_AGE_DAYS)
                .bindDouble(FeedConfig.STANCE_HALF_LIFE_DAYS)
                .bindString(model)
                .bindUUID(viewer)
                .bindString(model)
                .bindString(model)
                .bindString(model)
                .bindTimestamp(asOf);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String part = rs.getString("part");
                    double[] total = doubles(rs.getArray("total"));
                    double amount = rs.getDouble("amount");
                    switch (part) {
                        case "INTERESTS" -> interests = total;
                        case "CATEGORY" -> categories.add(new UserVector.Category(total, (int) amount));
                        case "INTEREST_AVERAGE" -> interestAverage = total;
                        case "POST_AVERAGE" -> postAverage = total;
                        default -> stances.add(new UserVector.Stance(part, total, amount));
                    }
                }
            }
        }
        return new UserVector.Parts(stances, interests, categories, interestAverage, postAverage);
    }

    private static double[] doubles(Array array) throws SQLException {
        Float[] values = (Float[]) array.getArray();
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    /**
     * One row per candidate. Has its own timeout, so slow ranking falls back to the upcoming order instead of holding up
     * the page. The position is the session's, for proximity; a radius in the filters uses the filters' own position.
     * Each post loses the post average before it's compared with the viewer's vector, as the vector's parts did.
     */
    public List<FeedScorer.Facts> findCandidates(UUID viewer, double[] viewerVector, double[] postAverage, Instant asOf,
                                                 Double latitude, Double longitude, PostFeedFilters filters) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FEATURES + feedRules(filters.showsAllPastEvents()))) {

            stmt.setQueryTimeout(FeedConfig.QUERY_TIMEOUT_SECONDS);

            ParamBinder b = new ParamBinder(stmt, conn);
            b.bindString(model)
             .bindString(UserVector.toSql(postAverage))
             .bindString(UserVector.toSql(viewerVector))
             .bindDouble(longitude)
             .bindDouble(latitude);
            bindRules(b, viewer, asOf, filters);

            List<FeedScorer.Facts> candidates = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new FeedScorer.Facts(
                        (UUID) rs.getObject("id"),
                        ResultSetMappers.mapInstant(rs, "starts_at"),
                        ResultSetMappers.mapInstant(rs, "ends_at"),
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
    public List<PostCardDto> findUpcoming(UUID viewer, Instant asOf, PostFeedFilters filters,
                                          Instant afterStartsAt, UUID afterId, int limit) throws SQLException {
        boolean continues = afterStartsAt != null;
        String sql = PostSql.CARD_SELECT
            + "WHERE " + feedRules(false)
            + (continues ? "  AND (p.starts_at, p.id) > (?::timestamptz, ?::uuid)\n" : "")
            + "ORDER BY p.starts_at ASC, p.id ASC\n"
            + "LIMIT ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ParamBinder b = new ParamBinder(stmt, conn);
            b.bindNullableUUID(viewer)
             .bindNullableUUID(viewer);
            bindRules(b, viewer, asOf, filters);
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

    private static void bindRules(ParamBinder b, UUID viewer, Instant asOf, PostFeedFilters filters) throws SQLException {
        b.bindTimestamp(asOf);
        // Visible, then not blocked
        b.bindNullableUUID(viewer)
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer);
        PostSql.bindNotMine(b, viewer);
        PostFilterSql.bind(b, filters, viewer);
    }

    private static List<PostCardDto> readCards(PreparedStatement stmt) throws SQLException {
        List<PostCardDto> cards = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                cards.add(ResultSetMappers.mapPostCard(rs));
            }
        }
        return MediaQueries.withPhotos(stmt.getConnection(), cards);
    }
}
