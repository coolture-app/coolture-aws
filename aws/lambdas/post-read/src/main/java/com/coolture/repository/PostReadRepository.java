package com.coolture.repository;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.dto.PostMediaDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.common.dto.enums.ReactionType;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PostReadRepository {

    public List<PostCardDto> findRecommendations(UUID userId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.event_url, p.tags, p.type, p.status, p.visibility,
                   p.positive_reaction_count, p.negative_reaction_count, p.participant_count, p.comments_count,
                   p.starts_at, p.ends_at, p.created_at, p.last_modified_at, p.deleted_at,
                   u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude
            FROM posts p
            JOIN post_embeddings pe ON pe.post_id = p.id
            JOIN user_embeddings ue ON ue.user_id = ?
            JOIN users u ON u.id = p.author_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.deleted_at IS NULL
              AND p.visibility = 'PUBLIC'
              AND p.author_id != ?
              AND NOT EXISTS (SELECT 1 FROM post_reactions pr WHERE pr.post_id = p.id AND pr.user_id = ?)
              AND NOT EXISTS (SELECT 1 FROM post_participations pp WHERE pp.post_id = p.id AND pp.user_id = ?)
              AND (?::timestamptz IS NULL
                   OR p.created_at < ?::timestamptz
                   OR (p.created_at = ?::timestamptz AND p.id < ?::uuid))
            ORDER BY (pe.embedding <=> ue.embedding) ASC, p.created_at DESC, p.id DESC
            LIMIT ?
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, userId);
            stmt.setObject(2, userId);
            stmt.setObject(3, userId);
            stmt.setObject(4, userId);

            Timestamp cursorTs = cursorCreatedAt != null ? Timestamp.from(cursorCreatedAt) : null;
            stmt.setTimestamp(5, cursorTs);
            stmt.setTimestamp(6, cursorTs);
            stmt.setTimestamp(7, cursorTs);
            stmt.setObject(8, cursorId);
            stmt.setInt(9, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                return mapPostCardList(conn, rs);
            }
        }
    }

    public List<PostCardDto> findFeed(PostFeedFilters f, UUID callerId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.event_url, p.tags, p.type, p.status, p.visibility,
                   p.positive_reaction_count, p.negative_reaction_count, p.participant_count, p.comments_count,
                   p.starts_at, p.ends_at, p.created_at, p.last_modified_at, p.deleted_at,
                   u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude
            FROM posts p
            JOIN users u ON u.id = p.author_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.deleted_at IS NULL
              AND (?::varchar IS NULL OR LOWER(p.title) LIKE LOWER('%' || ?::varchar || '%') OR LOWER(p.description) LIKE LOWER('%' || ?::varchar || '%'))
              AND (?::varchar[] IS NULL OR p.tags && ?::varchar[])
              AND (?::uuid IS NULL OR p.author_id = ?::uuid)
              AND (?::varchar IS NULL OR p.status = ?::varchar)
              AND (?::varchar IS NULL OR p.visibility = ?::varchar)
              AND (?::varchar IS NULL OR p.type = ?::varchar)
              AND (?::timestamptz IS NULL OR p.starts_at >= ?::timestamptz)
              AND (?::timestamptz IS NULL OR p.starts_at <= ?::timestamptz)
              AND (?::double precision IS NULL OR ?::double precision IS NULL OR ?::double precision IS NULL
                   OR (el.coordinates IS NOT NULL AND ST_DWithin(el.coordinates, ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography, ?::double precision)))
              AND (?::varchar[] IS NULL
                   OR EXISTS (SELECT 1 FROM post_participations pp WHERE pp.post_id = p.id AND pp.user_id = ?::uuid AND pp.type = ANY(?::varchar[])))
              AND (?::varchar IS NULL
                   OR EXISTS (SELECT 1 FROM post_reactions pr WHERE pr.post_id = p.id AND pr.user_id = ?::uuid AND pr.type::varchar = ?::varchar))
              AND (?::timestamptz IS NULL
                   OR p.created_at < ?::timestamptz
                   OR (p.created_at = ?::timestamptz AND p.id < ?::uuid))
            ORDER BY
              CASE WHEN ? = 'POPULAR' THEN p.positive_reaction_count END DESC,
              CASE WHEN ? = 'UPCOMING' THEN p.starts_at END ASC,
              p.created_at DESC,
              p.id DESC
            LIMIT ?
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, f.q());
            stmt.setString(2, f.q());
            stmt.setString(3, f.q());

            if (f.tags() != null && !f.tags().isEmpty()) {
                Array tagsArr = conn.createArrayOf("varchar", f.tags().toArray());
                stmt.setArray(4, tagsArr);
                stmt.setArray(5, tagsArr);
            } else {
                stmt.setNull(4, Types.ARRAY);
                stmt.setNull(5, Types.ARRAY);
            }

            stmt.setObject(6, f.authorId());
            stmt.setObject(7, f.authorId());
            stmt.setString(8, f.status() != null ? f.status().name() : null);
            stmt.setString(9, f.status() != null ? f.status().name() : null);

            String visibilityFilter = f.visibility() != null ? f.visibility().name() : (callerId == null ? PostVisibility.PUBLIC.name() : null);
            stmt.setString(10, visibilityFilter);
            stmt.setString(11, visibilityFilter);

            stmt.setString(12, f.type() != null ? f.type().name() : null);
            stmt.setString(13, f.type() != null ? f.type().name() : null);

            Timestamp startsFromTs = f.startsFrom() != null ? Timestamp.from(f.startsFrom()) : null;
            stmt.setTimestamp(14, startsFromTs);
            stmt.setTimestamp(15, startsFromTs);

            Timestamp startsToTs = f.startsTo() != null ? Timestamp.from(f.startsTo()) : null;
            stmt.setTimestamp(16, startsToTs);
            stmt.setTimestamp(17, startsToTs);

            Double radiusMeters = f.radiusKm() != null ? f.radiusKm() * 1000.0 : null;
            if (f.latitude() != null && f.longitude() != null && radiusMeters != null) {
                stmt.setDouble(18, f.latitude());
                stmt.setDouble(19, f.longitude());
                stmt.setDouble(20, radiusMeters);
                stmt.setDouble(21, f.longitude());
                stmt.setDouble(22, f.latitude());
                stmt.setDouble(23, radiusMeters);
            } else {
                stmt.setNull(18, Types.DOUBLE);
                stmt.setNull(19, Types.DOUBLE);
                stmt.setNull(20, Types.DOUBLE);
                stmt.setNull(21, Types.DOUBLE);
                stmt.setNull(22, Types.DOUBLE);
                stmt.setNull(23, Types.DOUBLE);
            }

            if (f.participationTypes() != null && !f.participationTypes().isEmpty() && callerId != null) {
                Array partArr = conn.createArrayOf("varchar", f.participationTypes().toArray());
                stmt.setArray(24, partArr);
                stmt.setObject(25, callerId);
                stmt.setArray(26, partArr);
            } else {
                stmt.setNull(24, Types.ARRAY);
                stmt.setNull(25, Types.OTHER);
                stmt.setNull(26, Types.ARRAY);
            }

            if (f.reactionType() != null && callerId != null) {
                stmt.setString(27, f.reactionType());
                stmt.setObject(28, callerId);
                stmt.setString(29, f.reactionType());
            } else {
                stmt.setNull(27, Types.VARCHAR);
                stmt.setNull(28, Types.OTHER);
                stmt.setNull(29, Types.VARCHAR);
            }

            Timestamp cursorTs = cursorCreatedAt != null ? Timestamp.from(cursorCreatedAt) : null;
            stmt.setTimestamp(30, cursorTs);
            stmt.setTimestamp(31, cursorTs);
            stmt.setTimestamp(32, cursorTs);
            stmt.setObject(33, cursorId);

            String sort = f.sortBy() != null ? f.sortBy().toUpperCase() : "RECENT";
            stmt.setString(34, sort);
            stmt.setString(35, sort);

            stmt.setInt(36, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                return mapPostCardList(conn, rs);
            }
        }
    }

    public Optional<PostDetailDto> findById(UUID postId) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.event_url, p.tags, p.type, p.status, p.visibility,
                   p.positive_reaction_count, p.negative_reaction_count, p.participant_count, p.comments_count,
                   p.starts_at, p.ends_at, p.created_at, p.last_modified_at, p.deleted_at,
                   u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude
            FROM posts p
            JOIN users u ON u.id = p.author_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.id = ? AND p.deleted_at IS NULL
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    UUID authorId = (UUID) rs.getObject("author_id");
                    Map<UUID, MediaResourceDto> avatarMap = findAuthorAvatars(conn, List.of(authorId));

                    UserSummaryDto author = new UserSummaryDto(
                        authorId,
                        rs.getString("username"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        avatarMap.get(authorId),
                        rs.getTimestamp("user_created_at") != null ? rs.getTimestamp("user_created_at").toInstant() : null,
                        0,
                        0
                    );

                    EventLocationDto location = null;
                    UUID locId = (UUID) rs.getObject("loc_id");
                    if (locId != null) {
                        location = new EventLocationDto(
                            locId,
                            rs.getString("country_code"),
                            rs.getString("venue_name"),
                            rs.getString("building_num"),
                            rs.getString("street"),
                            rs.getString("postal_code"),
                            rs.getString("city"),
                            new GeoPointDto(
                                rs.getDouble("latitude"),
                                rs.getDouble("longitude")
                            ),
                            null
                        );
                    }

                    Array tagsArray = rs.getArray("tags");
                    List<String> tags = tagsArray != null
                        ? List.of((String[]) tagsArray.getArray())
                        : Collections.emptyList();

                    List<PostMediaDto> mediaList = getPostMediaList(conn, postId);
                    MediaResourceDto coverMedia = mediaList.stream()
                        .filter(PostMediaDto::isCover)
                        .map(PostMediaDto::media)
                        .findFirst()
                        .orElse(null);

                    Timestamp startsAtTs = rs.getTimestamp("starts_at");
                    Timestamp endsAtTs = rs.getTimestamp("ends_at");
                    Timestamp createdAtTs = rs.getTimestamp("created_at");
                    Timestamp modifiedAtTs = rs.getTimestamp("last_modified_at");
                    Timestamp deletedAtTs = rs.getTimestamp("deleted_at");

                    return Optional.of(new PostDetailDto(
                        (UUID) rs.getObject("id"),
                        author,
                        location,
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("event_url"),
                        startsAtTs != null ? startsAtTs.toInstant() : null,
                        endsAtTs != null ? endsAtTs.toInstant() : null,
                        tags,
                        rs.getInt("positive_reaction_count"),
                        rs.getInt("negative_reaction_count"),
                        rs.getInt("participant_count"),
                        rs.getInt("comments_count"),
                        PostType.valueOf(rs.getString("type")),
                        PostStatus.valueOf(rs.getString("status")),
                        PostVisibility.valueOf(rs.getString("visibility")),
                        createdAtTs != null ? createdAtTs.toInstant() : null,
                        modifiedAtTs != null ? modifiedAtTs.toInstant() : null,
                        deletedAtTs != null ? deletedAtTs.toInstant() : null,
                        coverMedia,
                        mediaList,
                        null,
                        null
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public List<PostMarkDto> findPostMarks(PostFeedFilters f, MapBoundsDto bounds, UUID callerId) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.positive_reaction_count,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude
            FROM posts p
            JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.deleted_at IS NULL
              AND el.coordinates IS NOT NULL
              AND (?::varchar IS NULL OR LOWER(p.title) LIKE LOWER('%' || ?::varchar || '%') OR LOWER(p.description) LIKE LOWER('%' || ?::varchar || '%'))
              AND (?::varchar[] IS NULL OR p.tags && ?::varchar[])
              AND (?::uuid IS NULL OR p.author_id = ?::uuid)
              AND (?::varchar IS NULL OR p.status = ?::varchar)
              AND (?::varchar IS NULL OR p.visibility = ?::varchar)
              AND (?::varchar IS NULL OR p.type = ?::varchar)
              AND (?::timestamptz IS NULL OR p.starts_at >= ?::timestamptz)
              AND (?::timestamptz IS NULL OR p.starts_at <= ?::timestamptz)
              AND (el.coordinates::geometry && ST_MakeEnvelope(?, ?, ?, ?, 4326))
              AND (?::varchar[] IS NULL OR EXISTS (SELECT 1 FROM post_participations pp WHERE pp.post_id = p.id AND pp.user_id = ?::uuid AND pp.type = ANY(?::varchar[])))
              AND (?::varchar IS NULL OR EXISTS (SELECT 1 FROM post_reactions pr WHERE pr.post_id = p.id AND pr.user_id = ?::uuid AND pr.type::varchar = ?::varchar))
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, f.q());
            stmt.setString(2, f.q());
            stmt.setString(3, f.q());

            if (f.tags() != null && !f.tags().isEmpty()) {
                Array tagsArr = conn.createArrayOf("varchar", f.tags().toArray());
                stmt.setArray(4, tagsArr);
                stmt.setArray(5, tagsArr);
            } else {
                stmt.setNull(4, Types.ARRAY);
                stmt.setNull(5, Types.ARRAY);
            }

            stmt.setObject(6, f.authorId());
            stmt.setObject(7, f.authorId());
            stmt.setString(8, f.status() != null ? f.status().name() : null);
            stmt.setString(9, f.status() != null ? f.status().name() : null);

            String visibilityFilter = f.visibility() != null ? f.visibility().name() : (callerId == null ? PostVisibility.PUBLIC.name() : null);
            stmt.setString(10, visibilityFilter);
            stmt.setString(11, visibilityFilter);

            stmt.setString(12, f.type() != null ? f.type().name() : null);
            stmt.setString(13, f.type() != null ? f.type().name() : null);

            Timestamp startsFromTs = f.startsFrom() != null ? Timestamp.from(f.startsFrom()) : null;
            stmt.setTimestamp(14, startsFromTs);
            stmt.setTimestamp(15, startsFromTs);

            Timestamp startsToTs = f.startsTo() != null ? Timestamp.from(f.startsTo()) : null;
            stmt.setTimestamp(16, startsToTs);
            stmt.setTimestamp(17, startsToTs);

            Double minLng = bounds.leftUpper().longitude();
            Double minLat = bounds.rightBottom().latitude();
            Double maxLng = bounds.rightBottom().longitude();
            Double maxLat = bounds.leftUpper().latitude();

            stmt.setDouble(18, minLng);
            stmt.setDouble(19, minLat);
            stmt.setDouble(20, maxLng);
            stmt.setDouble(21, maxLat);

            if (f.participationTypes() != null && !f.participationTypes().isEmpty() && callerId != null) {
                Array partArr = conn.createArrayOf("varchar", f.participationTypes().toArray());
                stmt.setArray(22, partArr);
                stmt.setObject(23, callerId);
                stmt.setArray(24, partArr);
            } else {
                stmt.setNull(22, Types.ARRAY);
                stmt.setNull(23, Types.OTHER);
                stmt.setNull(24, Types.ARRAY);
            }

            if (f.reactionType() != null && callerId != null) {
                stmt.setString(25, f.reactionType());
                stmt.setObject(26, callerId);
                stmt.setString(27, f.reactionType());
            } else {
                stmt.setNull(25, Types.VARCHAR);
                stmt.setNull(26, Types.OTHER);
                stmt.setNull(27, Types.VARCHAR);
            }

            List<PostMarkDto> list = new ArrayList<>();
            List<UUID> postIds = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    UUID postId = (UUID) rs.getObject("id");
                    postIds.add(postId);
                    list.add(new PostMarkDto(
                        postId,
                        rs.getString("title"),
                        rs.getString("description"),
                        null,
                        rs.getInt("positive_reaction_count"),
                        new GeoPointDto(rs.getDouble("latitude"), rs.getDouble("longitude"))
                    ));
                }
            }

            if (!postIds.isEmpty()) {
                Map<UUID, String> coverUrlMap = findCoverMediaUrls(conn, postIds);
                list = list.stream()
                    .map(m -> new PostMarkDto(
                        m.id(),
                        m.title(),
                        m.description(),
                        coverUrlMap.get(m.id()),
                        m.positiveReactionCount(),
                        m.coordinates()
                    ))
                    .toList();
            }

            return list;
        }
    }

    public Map<UUID, ReactionType> findReactionsForPosts(UUID callerId, List<UUID> postIds) throws SQLException {
        if (callerId == null || postIds.isEmpty()) return Collections.emptyMap();
        String sql = "SELECT post_id, type FROM post_reactions WHERE user_id = ? AND post_id = ANY(?)";
        Map<UUID, ReactionType> map = new HashMap<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, callerId);
            stmt.setArray(2, conn.createArrayOf("uuid", postIds.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    map.put((UUID) rs.getObject("post_id"), ReactionType.valueOf(rs.getString("type")));
                }
            }
        }
        return map;
    }

    public Map<UUID, ParticipationType> findParticipationsForPosts(UUID callerId, List<UUID> postIds) throws SQLException {
        if (callerId == null || postIds.isEmpty()) return Collections.emptyMap();
        String sql = "SELECT post_id, type FROM post_participations WHERE user_id = ? AND post_id = ANY(?)";
        Map<UUID, ParticipationType> map = new HashMap<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, callerId);
            stmt.setArray(2, conn.createArrayOf("uuid", postIds.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    map.put((UUID) rs.getObject("post_id"), ParticipationType.valueOf(rs.getString("type")));
                }
            }
        }
        return map;
    }

    private List<PostCardDto> mapPostCardList(Connection conn, ResultSet rs) throws SQLException {
        List<PostCardDto> list = new ArrayList<>();
        List<UUID> authorIds = new ArrayList<>();
        List<UUID> postIds = new ArrayList<>();

        while (rs.next()) {
            UUID postId = (UUID) rs.getObject("id");
            UUID authorId = (UUID) rs.getObject("author_id");
            postIds.add(postId);
            authorIds.add(authorId);

            EventLocationDto location = null;
            UUID locId = (UUID) rs.getObject("loc_id");
            if (locId != null) {
                location = new EventLocationDto(
                    locId,
                    rs.getString("country_code"),
                    rs.getString("venue_name"),
                    rs.getString("building_num"),
                    rs.getString("street"),
                    rs.getString("postal_code"),
                    rs.getString("city"),
                    new GeoPointDto(
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude")
                    ),
                    null
                );
            }

            Array tagsArray = rs.getArray("tags");
            List<String> tags = tagsArray != null
                ? List.of((String[]) tagsArray.getArray())
                : Collections.emptyList();

            UserSummaryDto author = new UserSummaryDto(
                authorId,
                rs.getString("username"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                null,
                rs.getTimestamp("user_created_at") != null ? rs.getTimestamp("user_created_at").toInstant() : null,
                0,
                0
            );

            Timestamp startsAtTs = rs.getTimestamp("starts_at");
            Timestamp endsAtTs = rs.getTimestamp("ends_at");
            Timestamp createdAtTs = rs.getTimestamp("created_at");
            Timestamp modifiedAtTs = rs.getTimestamp("last_modified_at");
            Timestamp deletedAtTs = rs.getTimestamp("deleted_at");

            list.add(new PostCardDto(
                postId,
                author,
                location,
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("event_url"),
                startsAtTs != null ? startsAtTs.toInstant() : null,
                endsAtTs != null ? endsAtTs.toInstant() : null,
                tags,
                rs.getInt("positive_reaction_count"),
                rs.getInt("negative_reaction_count"),
                rs.getInt("participant_count"),
                rs.getInt("comments_count"),
                PostType.valueOf(rs.getString("type")),
                PostStatus.valueOf(rs.getString("status")),
                PostVisibility.valueOf(rs.getString("visibility")),
                createdAtTs != null ? createdAtTs.toInstant() : null,
                modifiedAtTs != null ? modifiedAtTs.toInstant() : null,
                deletedAtTs != null ? deletedAtTs.toInstant() : null,
                null,
                null,
                null
            ));
        }

        if (!postIds.isEmpty()) {
            Map<UUID, MediaResourceDto> avatarMap = findAuthorAvatars(conn, authorIds);
            Map<UUID, MediaResourceDto> coverMediaMap = findCoverMedia(conn, postIds);

            list = list.stream().map(dto -> {
                UserSummaryDto userWithAvatar = new UserSummaryDto(
                    dto.author().id(),
                    dto.author().username(),
                    dto.author().firstName(),
                    dto.author().lastName(),
                    avatarMap.get(dto.author().id()),
                    dto.author().createdAt(),
                    0,
                    0
                );
                return new PostCardDto(
                    dto.id(),
                    userWithAvatar,
                    dto.location(),
                    dto.title(),
                    dto.description(),
                    dto.eventUrl(),
                    dto.startsAt(),
                    dto.endsAt(),
                    dto.tags(),
                    dto.positiveReactionCount(),
                    dto.negativeReactionCount(),
                    dto.participantCount(),
                    dto.commentsCount(),
                    dto.type(),
                    dto.status(),
                    dto.visibility(),
                    dto.createdAt(),
                    dto.lastModifiedAt(),
                    dto.deletedAt(),
                    coverMediaMap.get(dto.id()),
                    null,
                    null
                );
            }).toList();
        }

        return list;
    }

    private Map<UUID, MediaResourceDto> findAuthorAvatars(Connection conn, List<UUID> userIds) throws SQLException {
        if (userIds.isEmpty()) return Collections.emptyMap();
        String sql = """
            SELECT pi.user_id, m.id, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at
            FROM profile_images pi
            JOIN media m ON m.id = pi.thumbnail_media_id
            WHERE pi.user_id = ANY(?) AND pi.is_active = true
        """;
        Map<UUID, MediaResourceDto> map = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("uuid", userIds.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp catTs = rs.getTimestamp("created_at");
                    Timestamp datTs = rs.getTimestamp("deleted_at");
                    MediaResourceDto dto = new MediaResourceDto(
                        (UUID) rs.getObject("id"),
                        rs.getString("purpose") != null ? MediaPurpose.valueOf(rs.getString("purpose")) : null,
                        rs.getString("mime_type"),
                        rs.getObject("size_bytes") != null ? rs.getLong("size_bytes") : null,
                        rs.getString("status") != null ? MediaStatus.valueOf(rs.getString("status")) : null,
                        null,
                        catTs != null ? catTs.toInstant() : null,
                        datTs != null ? datTs.toInstant() : null
                    );
                    map.put((UUID) rs.getObject("user_id"), dto);
                }
            }
        }
        return map;
    }

    private Map<UUID, MediaResourceDto> findCoverMedia(Connection conn, List<UUID> postIds) throws SQLException {
        if (postIds.isEmpty()) return Collections.emptyMap();
        String sql = """
            SELECT DISTINCT ON (pm.post_id) pm.post_id, m.id, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at
            FROM post_media pm
            JOIN media m ON m.id = pm.media_id
            WHERE pm.post_id = ANY(?)
            ORDER BY pm.post_id, pm.is_cover DESC, pm.position ASC
        """;
        Map<UUID, MediaResourceDto> map = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("uuid", postIds.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp catTs = rs.getTimestamp("created_at");
                    Timestamp datTs = rs.getTimestamp("deleted_at");
                    MediaResourceDto dto = new MediaResourceDto(
                        (UUID) rs.getObject("id"),
                        rs.getString("purpose") != null ? MediaPurpose.valueOf(rs.getString("purpose")) : null,
                        rs.getString("mime_type"),
                        rs.getObject("size_bytes") != null ? rs.getLong("size_bytes") : null,
                        rs.getString("status") != null ? MediaStatus.valueOf(rs.getString("status")) : null,
                        null,
                        catTs != null ? catTs.toInstant() : null,
                        datTs != null ? datTs.toInstant() : null
                    );
                    map.put((UUID) rs.getObject("post_id"), dto);
                }
            }
        }
        return map;
    }

    private Map<UUID, String> findCoverMediaUrls(Connection conn, List<UUID> postIds) throws SQLException {
        if (postIds.isEmpty()) return Collections.emptyMap();
        String sql = """
            SELECT DISTINCT ON (pm.post_id) pm.post_id, m.object_key
            FROM post_media pm
            JOIN media m ON m.id = pm.media_id
            WHERE pm.post_id = ANY(?)
            ORDER BY pm.post_id, pm.is_cover DESC, pm.position ASC
        """;
        Map<UUID, String> map = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("uuid", postIds.toArray()));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    map.put((UUID) rs.getObject("post_id"), rs.getString("object_key"));
                }
            }
        }
        return map;
    }

    private List<PostMediaDto> getPostMediaList(Connection conn, UUID postId) throws SQLException {
        String sql = """
            SELECT pm.position, pm.is_cover,
                   m.id AS media_id, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at
            FROM post_media pm
            JOIN media m ON m.id = pm.media_id
            WHERE pm.post_id = ?
            ORDER BY pm.position ASC
        """;

        List<PostMediaDto> list = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp catTs = rs.getTimestamp("created_at");
                    Timestamp datTs = rs.getTimestamp("deleted_at");
                    MediaResourceDto mDto = new MediaResourceDto(
                        (UUID) rs.getObject("media_id"),
                        rs.getString("purpose") != null ? MediaPurpose.valueOf(rs.getString("purpose")) : null,
                        rs.getString("mime_type"),
                        rs.getObject("size_bytes") != null ? rs.getLong("size_bytes") : null,
                        rs.getString("status") != null ? MediaStatus.valueOf(rs.getString("status")) : null,
                        null,
                        catTs != null ? catTs.toInstant() : null,
                        datTs != null ? datTs.toInstant() : null
                    );
                    list.add(new PostMediaDto(mDto, rs.getInt("position"), rs.getBoolean("is_cover")));
                }
            }
        }
        return list;
    }

    public List<EventLocationDto> findEventLocations(Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT id, country_code, venue_name, building_num, street, postal_code, city,
                   ST_Y(coordinates::geometry) AS latitude,
                   ST_X(coordinates::geometry) AS longitude,
                   created_at
            FROM event_locations
            WHERE (?::timestamptz IS NULL
                   OR created_at < ?::timestamptz
                   OR (created_at = ?::timestamptz AND id < ?::uuid))
            ORDER BY created_at DESC, id DESC
            LIMIT ?
        """;

        List<EventLocationDto> list = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            Timestamp cursorTs = cursorCreatedAt != null ? Timestamp.from(cursorCreatedAt) : null;
            stmt.setTimestamp(1, cursorTs);
            stmt.setTimestamp(2, cursorTs);
            stmt.setTimestamp(3, cursorTs);
            stmt.setObject(4, cursorId);
            stmt.setInt(5, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp catTs = rs.getTimestamp("created_at");
                    list.add(new EventLocationDto(
                        (UUID) rs.getObject("id"),
                        rs.getString("country_code"),
                        rs.getString("venue_name"),
                        rs.getString("building_num"),
                        rs.getString("street"),
                        rs.getString("postal_code"),
                        rs.getString("city"),
                        new GeoPointDto(
                            rs.getDouble("latitude"),
                            rs.getDouble("longitude")
                        ),
                        catTs != null ? catTs.toInstant() : null
                    ));
                }
            }
        }
        return list;
    }
}
