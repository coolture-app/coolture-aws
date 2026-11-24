package com.coolture.feed;

import com.coolture.common.db.Blocks;
import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.EventTime;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.PostSelect;
import com.coolture.common.db.ResultSetMappers;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.repository.PostFilterSql;
import com.coolture.repository.PostSql;
import com.coolture.repository.Upcoming;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FeedRepository {

    /**
     * What the feed may show, narrowed by the person's filters. Ended events only when the filters ask for past events,
     * and the fallback never shows them. Bind with bindRules.
     */
    private static String feedRules(boolean showsEnded) {
        return "p.deleted_at IS NULL\n"
            + (showsEnded ? "" : "  AND " + EventTime.NOT_ENDED + "\n")
            + "  AND p.created_at <= ?::timestamptz\n"
            + "  AND " + PostSql.NOT_BLOCKED + "\n"
            + "  AND " + PostSql.NOT_MINE + "\n"
            + "  AND " + PostFilterSql.MATCHES + "\n";
    }

    /**
     * Each post loses the post average once (centred), and is compared with the viewer's vector and with each of the
     * viewer's "Nie dla mnie" marks, centred the same way. Marks count up to asOf, fade as stances do, and only the
     * newest NOT_FOR_ME_MARKS. MATERIALIZED, because an inlined CTE would run again for every candidate, and OFFSET 0,
     * because a flattened lateral would read and centre the post's vector again for every mark. The PageRank score
     * comes from the latest run finished PPR_RUN_MARGIN_SECONDS before asOf, so a run landing mid-session doesn't move
     * its pages, and less than PPR_RUN_MAX_AGE_HOURS before now too, since a cursor's asOf comes from the client.
     * A zero centred vector gives NaN from pgvector, which Postgres sorts above every number, so the clamp would turn
     * it into the full penalty. Those pairs are left out of near_mark.
     */
    private static final String FEATURES = """
        WITH marks AS MATERIALIZED (
            SELECT pe.embedding - ?::vector AS centred,
                   power(0.5, floor(extract(epoch FROM ?::timestamptz - pp.created_at) / 86400) / ?) AS fade
            FROM post_participations pp
            JOIN post_embeddings pe ON pe.post_id = pp.post_id AND pe.model = ?
            WHERE pp.user_id = ?::uuid AND pp.type = 'NOT_INTERESTED'
              AND pp.created_at <= ?::timestamptz
              AND pp.created_at > ?::timestamptz - make_interval(days => ?)
            ORDER BY pp.created_at DESC
            LIMIT ?
        )
        SELECT p.id,
               p.starts_at,
               p.ends_at,
               p.open_ended,
               %s AS engagement,
               1 - (c.centred <=> ?::vector) AS similarity,
               (SELECT max(m.fade * LEAST(GREATEST((1 - x.d - ?) / (1 - ?), 0), 1))
                FROM marks m, LATERAL (SELECT c.centred <=> m.centred AS d) x
                WHERE x.d <> 'NaN'::float8) AS near_mark,
               ST_Distance(el.coordinates,
                           ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography)
                   AS meters,
               pr.score::float8 AS ppr
        FROM posts p
        LEFT JOIN post_embeddings pe ON pe.post_id = p.id
        LEFT JOIN event_locations el ON el.id = p.event_location_id
        CROSS JOIN LATERAL (SELECT CASE WHEN pe.model = ? THEN pe.embedding - ?::vector END AS centred OFFSET 0) c
        LEFT JOIN rec_ppr_scores pr ON pr.post_id = p.id AND pr.user_id = ?::uuid
            AND pr.run_number = (SELECT run_number FROM rec_ppr_runs
                                 WHERE finished_at BETWEEN GREATEST(?::timestamptz, now()) - make_interval(hours => ?)
                                                       AND ?::timestamptz - make_interval(secs => ?)
                                 ORDER BY finished_at DESC LIMIT 1)
        WHERE
        """.formatted(PostSql.ENGAGEMENT);

    /**
     * The sums UserVector blends, each from one model's vectors. Stances count up to asOf, so every page of a session
     * gets the same vector; their fade is UserVector's. Each row is a part: a stance type with the sum of its fading
     * weights, the picked interests, the picked kinds, one category picked from with all of its interests, or the
     * average of every interest or every post. The post average counts posts created up to asOf, ended and deleted ones
     * too, so nothing done during a session moves it.
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
        SELECT type AS part,
               sum(embedding * array_fill(fade::real, ARRAY[vector_dims(embedding)])::vector)::real[] AS total,
               sum(stances * fade) AS amount
        FROM daily
        GROUP BY type
        UNION ALL
        SELECT 'INTERESTS', sum(embedding)::real[], count(*) FROM picks HAVING count(*) > 0
        UNION ALL
        SELECT 'KINDS', sum(ke.embedding)::real[], count(*)
        FROM user_kinds uk
        JOIN event_kind_embeddings ke ON ke.kind_key = uk.kind_key AND ke.model = ?
        WHERE uk.user_id = ?::uuid
        HAVING count(*) > 0
        UNION ALL
        SELECT 'CATEGORY', sum(ie.embedding)::real[], max(chosen.picks)
        FROM (SELECT category_key, count(*) AS picks FROM picks GROUP BY category_key) chosen
        JOIN interests i ON i.category_key = chosen.category_key
        JOIN interest_embeddings ie ON ie.interest_key = i.key AND ie.model = ?
        GROUP BY chosen.category_key
        UNION ALL
        SELECT 'INTEREST_AVERAGE', avg(embedding)::real[], count(*)
        FROM interest_embeddings WHERE model = ? HAVING count(*) > 0
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

    /** Where the viewer lives, if they said. Empty for a guest, with no query. */
    public Optional<GeoPointDto> findHome(UUID viewer) throws SQLException {
        if (viewer == null) {
            return Optional.empty();
        }
        String sql = """
            SELECT ST_Y(home_location::geometry) AS latitude, ST_X(home_location::geometry) AS longitude
            FROM users
            WHERE id = ? AND home_location IS NOT NULL
            """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, viewer);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next()
                    ? Optional.of(new GeoPointDto(rs.getDouble("latitude"), rs.getDouble("longitude")))
                    : Optional.empty();
            }
        }
    }

    /** Empty for a guest, with no query. */
    public UserVector.Parts findVectorParts(UUID viewer, Instant asOf) throws SQLException {
        if (viewer == null) {
            return new UserVector.Parts(List.of(), null, null, List.of(), null, null);
        }
        List<UserVector.Stance> stances = new ArrayList<>();
        List<UserVector.Category> categories = new ArrayList<>();
        double[] interests = null;
        double[] kinds = null;
        double[] interestAverage = null;
        double[] postAverage = null;

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
                        case "KINDS" -> kinds = total;
                        case "CATEGORY" -> categories.add(new UserVector.Category(total, (int) amount));
                        case "INTEREST_AVERAGE" -> interestAverage = total;
                        case "POST_AVERAGE" -> postAverage = total;
                        default -> stances.add(new UserVector.Stance(part, total, amount));
                    }
                }
            }
        }
        return new UserVector.Parts(stances, interests, kinds, categories, interestAverage, postAverage);
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
     * One row per candidate. Has its own timeout, so slow ranking falls back to the upcoming order instead of holding
     * up the page. The position is the session's, for proximity; a radius in the filters uses the filters' own
     * position.
     */
    public List<FeedScorer.Facts> findCandidates(UUID viewer, double[] viewerVector, double[] postAverage, Instant asOf,
                                                 Double latitude, Double longitude, PostFeedFilters filters)
                                                         throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FEATURES + feedRules(filters.showsAllPastEvents()))) {

            stmt.setQueryTimeout(FeedConfig.QUERY_TIMEOUT_SECONDS);

            String average = UserVector.toSql(postAverage);
            ParamBinder b = new ParamBinder(stmt, conn);
            b.bindString(average)
             .bindTimestamp(asOf)
             .bindDouble(FeedConfig.STANCE_HALF_LIFE_DAYS)
             .bindString(model)
             .bindNullableUUID(viewer)
             .bindTimestamp(asOf)
             .bindTimestamp(asOf)
             .bindInt(FeedConfig.STANCE_MAX_AGE_DAYS)
             .bindInt(FeedConfig.NOT_FOR_ME_MARKS)
             .bindString(UserVector.toSql(viewerVector))
             .bindDouble(FeedConfig.NOT_FOR_ME_FROM)
             .bindDouble(FeedConfig.NOT_FOR_ME_FROM)
             .bindDouble(longitude)
             .bindDouble(latitude)
             .bindString(model)
             .bindString(average)
             .bindNullableUUID(viewer)
             .bindTimestamp(asOf)
             .bindInt(FeedConfig.PPR_RUN_MAX_AGE_HOURS)
             .bindTimestamp(asOf)
             .bindInt(FeedConfig.PPR_RUN_MARGIN_SECONDS);
            bindRules(b, viewer, asOf, filters);

            List<FeedScorer.Facts> candidates = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Instant startsAt = ResultSetMappers.mapInstant(rs, "starts_at");
                    Instant endsAt = ResultSetMappers.mapInstant(rs, "ends_at");
                    boolean openEnded = rs.getBoolean("open_ended");
                    candidates.add(new FeedScorer.Facts(
                        (UUID) rs.getObject("id"),
                        startsAt,
                        EventTime.end(startsAt, endsAt, openEnded),
                        rs.getInt("engagement"),
                        (Double) rs.getObject("similarity"),
                        (Double) rs.getObject("meters"),
                        nearMark((Double) rs.getObject("near_mark")),
                        EventTime.isStanding(startsAt, endsAt, openEnded),
                        (Double) rs.getObject("ppr")
                    ));
                }
            }
            return candidates;
        }
    }

    /** No marks, or none to compare with, push nothing down. */
    private static double nearMark(Double value) {
        return value == null ? 0 : value;
    }

    /** The fallback order, Nadchodzące's (Upcoming), from the given position. */
    public Upcoming.Page findUpcoming(UUID viewer, PostFeedFilters filters, Upcoming.Position at, int limit)
            throws SQLException {
        Upcoming.Query query = new Upcoming.Query(PostSelect.CARD + "WHERE " + feedRules(false), b -> {
            PostSelect.bindViewer(b, viewer);
            bindRules(b, viewer, at.asOf(), filters);
        });
        try (Connection conn = DatabaseConfig.getConnection()) {
            return Upcoming.find(conn, query, null, viewer, filters.from(), at, limit);
        }
    }

    /** In no particular order. A post deleted since it was ranked just isn't returned. */
    public List<PostCardDto> findCards(UUID viewer, List<UUID> ids) throws SQLException {
        String sql = PostSelect.CARD + "WHERE p.id = ANY(?::uuid[]) AND p.deleted_at IS NULL AND " + PostSql.NOT_BLOCKED;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ParamBinder b = new ParamBinder(stmt, conn);
            PostSelect.bindViewer(b, viewer);
            b.bindUUIDArray(ids.toArray(new UUID[0]));
            Blocks.bindViewer(b, viewer);

            try (ResultSet rs = stmt.executeQuery()) {
                return PostSql.withPhotosAndFriends(conn, viewer, ResultSetMappers.mapPostCards(rs));
            }
        }
    }

    private static void bindRules(ParamBinder b, UUID viewer, Instant asOf, PostFeedFilters filters)
            throws SQLException {
        b.bindTimestamp(asOf);
        Blocks.bindViewer(b, viewer);
        PostSql.bindNotMine(b, viewer);
        PostFilterSql.bind(b, filters, viewer);
    }
}
