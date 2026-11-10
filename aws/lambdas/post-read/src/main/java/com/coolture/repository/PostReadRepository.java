package com.coolture.repository;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.dto.PostMediaDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.common.storage.MediaUrls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.coolture.common.db.ResultSetMappers.*;

public class PostReadRepository {

    public List<PostCardDto> findFeed(PostFeedFilters f, PostSort sort, UUID callerId, PostsCursor cursor, int limit) throws SQLException {
        // UPCOMING never shows an ended event. The other sorts hide only long-ended ones, and can show those too.
        boolean upcoming = sort == PostSort.UPCOMING;
        String sql = PostSql.CARD_SELECT
            + "WHERE p.deleted_at IS NULL\n"
            + "  AND " + PostFilterSql.MATCHES + "\n"
            + "  AND " + PostSql.VISIBLE + "\n"
            + "  AND " + PostSql.NOT_BLOCKED + "\n"
            + "  AND " + (upcoming ? PostSql.NOT_ENDED : PostSql.NOT_LONG_ENDED) + "\n"
            + (sort.needsPosition() ? "  AND el.coordinates IS NOT NULL\n" : "")
            + "  AND " + PARTICIPATION_AND_REACTION + "\n"
            + (cursor != null ? "  AND " + sort.keysetSql() + "\n" : "")
            + "ORDER BY " + sort.orderSql() + "\n"
            + "LIMIT ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ParamBinder b = new ParamBinder(stmt, conn);

            // JOIN params
            b.bindUUID(callerId)
             .bindUUID(callerId);

            PostFilterSql.bind(b, f, callerId);

            // Visible, then blocks
            b.bindNullableUUID(callerId)
             .bindNullableUUID(callerId)
             .bindNullableUUID(callerId)
             .bindNullableUUID(callerId);

            // Past events
            if (!upcoming) {
                b.bindBoolean(f.showsAllPastEvents());
            }

            bindParticipationAndReaction(b, f, callerId);

            if (cursor != null) {
                cursor.bindKeyset(b, f.latitude(), f.longitude());
            }
            if (sort.needsPosition()) {
                b.bindDouble(f.longitude())
                 .bindDouble(f.latitude());
            }

            b.bindInt(limit);

            try (ResultSet rs = stmt.executeQuery()) {
                return MediaQueries.withPhotos(conn, mapPostCardList(rs));
            }
        }
    }

    /** My Events: the viewer's participations or reactions of the given types. Bind with bindParticipationAndReaction. */
    private static final String PARTICIPATION_AND_REACTION = """
        (?::varchar[] IS NULL
               OR EXISTS (SELECT 1 FROM post_participations pp WHERE pp.post_id = p.id AND pp.user_id = ?::uuid AND pp.type = ANY(?::varchar[])))
          AND (?::varchar IS NULL
               OR EXISTS (SELECT 1 FROM post_reactions pr WHERE pr.post_id = p.id AND pr.user_id = ?::uuid AND pr.type::varchar = ?::varchar))""";

    private static void bindParticipationAndReaction(ParamBinder b, PostFeedFilters f, UUID callerId) throws SQLException {
        boolean hasPart = f.participationTypes() != null && !f.participationTypes().isEmpty() && callerId != null;
        String[] partArr = hasPart ? f.participationTypes().toArray(new String[0]) : null;
        b.bindVarcharArray(partArr)
         .bindNullableUUID(hasPart ? callerId : null)
         .bindVarcharArray(partArr);

        boolean hasReaction = f.reactionType() != null && callerId != null;
        b.bindString(hasReaction ? f.reactionType() : null)
         .bindNullableUUID(hasReaction ? callerId : null)
         .bindString(hasReaction ? f.reactionType() : null);
    }

    public Optional<PostDetailDto> findById(UUID postId, UUID callerId) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.event_url, p.tags, p.type, p.status, p.visibility,
                   p.positive_reaction_count, p.negative_reaction_count, p.participant_count, p.comments_count,
                   p.starts_at, p.ends_at, p.created_at, p.last_modified_at, p.deleted_at,
                   u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
                   avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose, avatar_m.mime_type AS avatar_mime_type,
                   avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
                   avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at, avatar_m.object_key AS avatar_object_key,
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude,
                   pr_my.type AS my_reaction,
                   pp_my.type AS my_participation
            FROM posts p
            JOIN users u ON u.id = p.author_id
            LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
            LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            LEFT JOIN post_reactions pr_my ON pr_my.post_id = p.id AND pr_my.user_id = ?::uuid
            LEFT JOIN post_participations pp_my ON pp_my.post_id = p.id AND pp_my.user_id = ?::uuid
            WHERE p.id = ? AND p.deleted_at IS NULL
              AND (p.visibility = 'PUBLIC'
                   OR p.author_id = ?::uuid
                   OR (p.visibility = 'FRIENDS' AND EXISTS (SELECT 1 FROM user_relations ur WHERE ur.source_user_id = ?::uuid AND ur.target_user_id = p.author_id AND ur.type = 'FOLLOW')))
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            new ParamBinder(stmt, conn)
                .bindUUID(callerId)
                .bindUUID(callerId)
                .bindUUID(postId)
                .bindNullableUUID(callerId)
                .bindNullableUUID(callerId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    List<PostMediaDto> mediaList = MediaQueries.getPostMediaList(conn, postId);
                    MediaResourceDto coverMedia = mediaList.stream()
                        .map(PostMediaDto::media)
                        .findFirst()
                        .orElse(null);

                    return Optional.of(new PostDetailDto(
                        (UUID) rs.getObject("id"),
                        mapUserSummary(rs),
                        mapEventLocation(rs),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("event_url"),
                        mapInstant(rs, "starts_at"),
                        mapInstant(rs, "ends_at"),
                        mapTags(rs),
                        rs.getInt("positive_reaction_count"),
                        rs.getInt("negative_reaction_count"),
                        rs.getInt("participant_count"),
                        rs.getInt("comments_count"),
                        mapEnum(rs, "type", PostType.class),
                        mapEnum(rs, "status", PostStatus.class),
                        mapEnum(rs, "visibility", PostVisibility.class),
                        mapInstant(rs, "created_at"),
                        mapInstant(rs, "last_modified_at"),
                        mapInstant(rs, "deleted_at"),
                        coverMedia,
                        mediaList,
                        mapMyReaction(rs),
                        mapMyParticipation(rs)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    /** With excludeMine, the same posts the feed would show this viewer: none of their own, reacted to or attended. */
    public List<PostMarkDto> findPostMarks(PostFeedFilters f, MapBoundsDto bounds, UUID callerId, boolean excludeMine) throws SQLException {
        boolean hidesMine = excludeMine && callerId != null;
        String sql = """
            SELECT p.id, p.title, p.description, p.positive_reaction_count,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude,
                   cover_m.object_key AS cover_url,
                   el.venue_name, el.city
            FROM posts p
            JOIN event_locations el ON el.id = p.event_location_id
            LEFT JOIN LATERAL (
                SELECT m.object_key
                FROM post_media pm
                JOIN media m ON m.id = pm.media_id
                WHERE pm.post_id = p.id
                ORDER BY pm.position ASC
                LIMIT 1
            ) cover_m ON true
            WHERE p.deleted_at IS NULL
              AND el.coordinates IS NOT NULL
            """
            + "  AND " + PostFilterSql.MATCHES + "\n"
            + "  AND " + PostSql.VISIBLE + "\n"
            + "  AND " + PostSql.NOT_BLOCKED + "\n"
            + "  AND " + PostSql.NOT_LONG_ENDED + "\n"
            + (hidesMine ? "  AND " + PostSql.NOT_MINE + "\n" : "")
            + "  AND (el.coordinates::geometry && ST_MakeEnvelope(?, ?, ?, ?, 4326))\n"
            + "  AND " + PARTICIPATION_AND_REACTION;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ParamBinder b = new ParamBinder(stmt, conn);

            PostFilterSql.bind(b, f, callerId);

            // Visible, then blocks
            b.bindNullableUUID(callerId)
             .bindNullableUUID(callerId)
             .bindNullableUUID(callerId)
             .bindNullableUUID(callerId);

            // Past events
            b.bindBoolean(f.showsAllPastEvents());

            if (hidesMine) {
                PostSql.bindNotMine(b, callerId);
            }

            // Bounds
            b.bindDouble(bounds.leftUpper().longitude())
             .bindDouble(bounds.rightBottom().latitude())
             .bindDouble(bounds.rightBottom().longitude())
             .bindDouble(bounds.leftUpper().latitude());

            bindParticipationAndReaction(b, f, callerId);

            List<PostMarkDto> list = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new PostMarkDto(
                        (UUID) rs.getObject("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        MediaUrls.presign(rs.getString("cover_url")),
                        rs.getInt("positive_reaction_count"),
                        new GeoPointDto(rs.getDouble("latitude"), rs.getDouble("longitude")),
                        rs.getString("venue_name"),
                        rs.getString("city")
                    ));
                }
            }

            return list;
        }
    }

    private List<PostCardDto> mapPostCardList(ResultSet rs) throws SQLException {
        List<PostCardDto> list = new ArrayList<>();
        while (rs.next()) {
            list.add(mapPostCard(rs));
        }
        return list;
    }

}
