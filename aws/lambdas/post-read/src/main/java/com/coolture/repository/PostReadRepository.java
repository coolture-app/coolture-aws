package com.coolture.repository;

import com.coolture.common.db.Blocks;
import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.EventTime;
import com.coolture.common.db.Friends;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.PostKinds;
import com.coolture.common.db.PostQueries;
import com.coolture.common.db.StanceCounts;
import com.coolture.common.dto.FriendStancesDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostFriendsDto;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.dto.PostMediaDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.storage.MediaUrls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.coolture.common.db.ResultSetMappers.*;

public class PostReadRepository {

    /** Enough for three lines of the map's preview, so a pin's preview needs no request of its own. */
    static final int EXCERPT_CHARS = 200;

    /** Every sort but UPCOMING, which is {@link #findUpcoming}. These hide ended events unless asked; PAST shows only them. */
    public List<PostCardDto> findFeed(PostFeedFilters f, PostSort sort, UUID callerId, PostsCursor cursor, int limit) throws SQLException {
        String sql = PostSql.CARD_SELECT
            + "WHERE p.deleted_at IS NULL\n"
            + "  AND " + PostFilterSql.MATCHES + "\n"
            + "  AND " + PostSql.NOT_BLOCKED + "\n"
            + "  AND " + (sort.endedOnly() ? EventTime.ENDED : PostSql.NOT_ENDED_UNLESS_PAST) + "\n"
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

            // Blocks
            b.bindNullableUUID(callerId)
                .bindNullableUUID(callerId);

            if (!sort.endedOnly()) {
                b.bindBoolean(f.showsAllPastEvents());
            }

            bindParticipationAndReaction(b, f, callerId);

            if (cursor != null) {
                cursor.bindKeyset(b, f.latitude(), f.longitude(), callerId);
            }
            if (sort.needsPosition()) {
                b.bindDouble(f.longitude())
                 .bindDouble(f.latitude());
            }

            b.bindInt(limit);

            try (ResultSet rs = stmt.executeQuery()) {
                return Friends.withFriendStances(conn, callerId, MediaQueries.withPhotos(conn, mapPostCardList(rs)));
            }
        }
    }

    /** Nadchodzące. Never an ended event, even with includePast or a date. */
    public Upcoming.Page findUpcoming(PostFeedFilters f, UUID callerId, Upcoming.Position at, int limit) throws SQLException {
        String sql = PostSql.CARD_SELECT
            + "WHERE p.deleted_at IS NULL\n"
            + "  AND " + PostFilterSql.MATCHES + "\n"
            + "  AND " + PostSql.NOT_BLOCKED + "\n"
            + "  AND " + PostSql.NOT_ENDED + "\n"
            + "  AND " + PARTICIPATION_AND_REACTION + "\n";
        Upcoming.Query query = new Upcoming.Query(sql, b -> {
            b.bindUUID(callerId)
             .bindUUID(callerId);
            PostFilterSql.bind(b, f, callerId);
            b.bindNullableUUID(callerId)
                .bindNullableUUID(callerId);
            bindParticipationAndReaction(b, f, callerId);
        });
        try (Connection conn = DatabaseConfig.getConnection()) {
            return Upcoming.find(conn, query, callerId, f.from(), at, limit);
        }
    }

    /**
     * My Events, or a friend's: the participations of the given types of the viewer or filters.participantId, or the
     * viewer's reactions. Bind with bindParticipationAndReaction.
     */
    private static final String PARTICIPATION_AND_REACTION = """
        (?::varchar[] IS NULL
               OR EXISTS (SELECT 1 FROM post_participations pp WHERE pp.post_id = p.id AND pp.user_id = ?::uuid AND pp.type = ANY(?::varchar[])))
          AND (?::varchar IS NULL
               OR EXISTS (SELECT 1 FROM post_reactions pr WHERE pr.post_id = p.id AND pr.user_id = ?::uuid AND pr.type::varchar = ?::varchar))""";

    private static void bindParticipationAndReaction(ParamBinder b, PostFeedFilters f, UUID callerId) throws SQLException {
        boolean hasPart = f.participationTypes() != null && !f.participationTypes().isEmpty() && callerId != null;
        String[] partArr = hasPart ? f.participationTypes().toArray(new String[0]) : null;
        UUID participant = f.participantId() != null ? f.participantId() : callerId;
        b.bindVarcharArray(partArr)
         .bindNullableUUID(hasPart ? participant : null)
         .bindVarcharArray(partArr);

        boolean hasReaction = f.reactionType() != null && callerId != null;
        b.bindString(hasReaction ? f.reactionType() : null)
         .bindNullableUUID(hasReaction ? callerId : null)
         .bindString(hasReaction ? f.reactionType() : null);
    }

    public Optional<PostDetailDto> findById(UUID postId, UUID callerId) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.event_url, p.links, p.type, p.status,
                   p.positive_reaction_count, p.comments_count,
                   stances.participant_count, stances.interested_count, stances.going_count,
                   p.starts_at, p.ends_at, p.open_ended, p.created_at, p.last_modified_at, p.deleted_at,
                   u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
                   avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose, avatar_m.mime_type AS avatar_mime_type,
                   avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
                   avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at, avatar_m.object_key AS avatar_object_key,
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude,
                   pr_my.type AS my_reaction,
                   pp_my.type AS my_participation,
                   """ + PostKinds.OF_POST + """
                   AS kinds
            FROM posts p
            JOIN users u ON u.id = p.author_id
            """ + StanceCounts.JOIN + """
            LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
            LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            LEFT JOIN post_reactions pr_my ON pr_my.post_id = p.id AND pr_my.user_id = ?::uuid
            LEFT JOIN post_participations pp_my ON pp_my.post_id = p.id AND pp_my.user_id = ?::uuid
            WHERE p.id = ? AND p.deleted_at IS NULL
            """ + "  AND " + PostSql.NOT_BLOCKED;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ParamBinder b = new ParamBinder(stmt, conn)
                .bindUUID(callerId)
                .bindUUID(callerId)
                .bindUUID(postId);
            Blocks.bindViewer(b, callerId);

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
                        mapLinks(rs),
                        mapInstant(rs, "starts_at"),
                        mapInstant(rs, "ends_at"),
                        rs.getBoolean("open_ended"),
                        rs.getInt("positive_reaction_count"),
                        rs.getInt("participant_count"),
                        rs.getInt("interested_count"),
                        rs.getInt("going_count"),
                        rs.getInt("comments_count"),
                        mapEnum(rs, "type", PostType.class),
                        mapEnum(rs, "status", PostStatus.class),
                        mapStringList(rs, "kinds"),
                        mapInstant(rs, "created_at"),
                        mapInstant(rs, "last_modified_at"),
                        mapInstant(rs, "deleted_at"),
                        coverMedia,
                        mediaList,
                        mapMyReaction(rs),
                        mapMyParticipation(rs),
                        Friends.forPosts(conn, callerId, List.of(postId)).get(postId)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    /** Empty when the post's page would be a 404. */
    public boolean sharesWith(UUID viewer, UUID user) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return Friends.sharesWith(conn, viewer, user);
        }
    }

    public Optional<PostFriendsDto> findFriends(UUID postId, UUID callerId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            if (!PostQueries.isPostVisible(conn, postId, callerId)) {
                return Optional.empty();
            }
            return Optional.of(Friends.forPost(conn, callerId, postId));
        }
    }

    /** With excludeMine, the same posts the feed would show this viewer: none of their own, reacted to or attended. */
    public List<PostMarkDto> findPostMarks(PostFeedFilters f, MapBoundsDto bounds, UUID callerId, boolean excludeMine) throws SQLException {
        boolean hidesMine = excludeMine && callerId != null;
        String sql = """
            SELECT p.id, p.title, p.starts_at, p.ends_at, p.open_ended, p.positive_reaction_count,
                   stances.interested_count, stances.going_count,
                   NULLIF(left(regexp_replace(btrim(p.description), '\\s+', ' ', 'g'), """ + EXCERPT_CHARS + """
                   ), '') AS excerpt,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude,
                   cover_m.object_key AS cover_url,
                   el.venue_name, el.city,
                   """ + PostKinds.OF_POST + """
                   AS kinds,
                   pr_my.type AS my_reaction,
                   pp_my.type AS my_participation
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
            """ + StanceCounts.JOIN + """
            LEFT JOIN post_reactions pr_my ON pr_my.post_id = p.id AND pr_my.user_id = ?::uuid
            LEFT JOIN post_participations pp_my ON pp_my.post_id = p.id AND pp_my.user_id = ?::uuid
            WHERE p.deleted_at IS NULL
              AND el.coordinates IS NOT NULL
            """
            + "  AND " + PostFilterSql.MATCHES + "\n"
            + "  AND " + PostSql.NOT_BLOCKED + "\n"
            + "  AND " + PostSql.NOT_ENDED_UNLESS_PAST + "\n"
            + (hidesMine ? "  AND " + PostSql.NOT_MINE + "\n" : "")
            + "  AND (el.coordinates::geometry && ST_MakeEnvelope(?, ?, ?, ?, 4326))\n"
            + "  AND " + PARTICIPATION_AND_REACTION;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // The viewer's own reaction and stance
            ParamBinder b = new ParamBinder(stmt, conn)
                .bindNullableUUID(callerId)
                .bindNullableUUID(callerId);

            PostFilterSql.bind(b, f, callerId);

            // Blocks
            b.bindNullableUUID(callerId)
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
                    List<String> kinds = mapStringList(rs, "kinds");
                    list.add(new PostMarkDto(
                        (UUID) rs.getObject("id"),
                        rs.getString("title"),
                        mapInstant(rs, "starts_at"),
                        mapInstant(rs, "ends_at"),
                        rs.getBoolean("open_ended"),
                        MediaUrls.presign(rs.getString("cover_url")),
                        rs.getString("excerpt"),
                        rs.getInt("positive_reaction_count"),
                        rs.getInt("interested_count"),
                        rs.getInt("going_count"),
                        new GeoPointDto(rs.getDouble("latitude"), rs.getDouble("longitude")),
                        rs.getString("venue_name"),
                        rs.getString("city"),
                        PostKinds.shown(kinds, f.kinds()),
                        kinds,
                        mapMyReaction(rs),
                        mapMyParticipation(rs),
                        null
                    ));
                }
            }

            Map<UUID, FriendStancesDto> friends = Friends.forPosts(conn, callerId, list.stream().map(PostMarkDto::id).toList());
            return friends.isEmpty() ? list : list.stream().map(mark -> mark.withFriendStances(friends.get(mark.id()))).toList();
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
