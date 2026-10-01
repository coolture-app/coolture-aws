package com.coolture.repository;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.dto.EventLocationDto;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.coolture.common.db.ResultSetMappers.*;

public class PostReadRepository {

    public List<PostCardDto> findRecommendations(UUID userId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.event_url, p.tags, p.type, p.status, p.visibility,
                   p.positive_reaction_count, p.negative_reaction_count, p.participant_count, p.comments_count,
                   p.starts_at, p.ends_at, p.created_at, p.last_modified_at, p.deleted_at,
                   u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
                   avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose, avatar_m.mime_type AS avatar_mime_type,
                   avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
                   avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at,
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude,
                   cover_m.id AS cover_id, cover_m.purpose AS cover_purpose, cover_m.mime_type AS cover_mime_type,
                   cover_m.size_bytes AS cover_size_bytes, cover_m.status AS cover_status,
                   cover_m.created_at AS cover_created_at, cover_m.deleted_at AS cover_deleted_at,
                   NULL::varchar AS my_reaction,
                   NULL::varchar AS my_participation
            FROM posts p
            JOIN post_embeddings pe ON pe.post_id = p.id
            JOIN user_embeddings ue ON ue.user_id = ?
            JOIN users u ON u.id = p.author_id
            LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
            LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            LEFT JOIN LATERAL (
                SELECT m.id, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at
                FROM post_media pm
                JOIN media m ON m.id = pm.media_id
                WHERE pm.post_id = p.id
                ORDER BY pm.is_cover DESC, pm.position ASC
                LIMIT 1
            ) cover_m ON true
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

            new ParamBinder(stmt, conn)
                .bindUUID(userId)
                .bindUUID(userId)
                .bindUUID(userId)
                .bindUUID(userId)
                .bindCursorParams(cursorCreatedAt, cursorId)
                .bindInt(limit);

            try (ResultSet rs = stmt.executeQuery()) {
                return mapPostCardList(rs);
            }
        }
    }

    public List<PostCardDto> findFeed(PostFeedFilters f, UUID callerId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.event_url, p.tags, p.type, p.status, p.visibility,
                   p.positive_reaction_count, p.negative_reaction_count, p.participant_count, p.comments_count,
                   p.starts_at, p.ends_at, p.created_at, p.last_modified_at, p.deleted_at,
                   u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
                   avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose, avatar_m.mime_type AS avatar_mime_type,
                   avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
                   avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at,
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude,
                   cover_m.id AS cover_id, cover_m.purpose AS cover_purpose, cover_m.mime_type AS cover_mime_type,
                   cover_m.size_bytes AS cover_size_bytes, cover_m.status AS cover_status,
                   cover_m.created_at AS cover_created_at, cover_m.deleted_at AS cover_deleted_at,
                   pr_my.type AS my_reaction,
                   pp_my.type AS my_participation
            FROM posts p
            JOIN users u ON u.id = p.author_id
            LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
            LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            LEFT JOIN LATERAL (
                SELECT m.id, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at
                FROM post_media pm
                JOIN media m ON m.id = pm.media_id
                WHERE pm.post_id = p.id
                ORDER BY pm.is_cover DESC, pm.position ASC
                LIMIT 1
            ) cover_m ON true
            LEFT JOIN post_reactions pr_my ON pr_my.post_id = p.id AND pr_my.user_id = ?::uuid
            LEFT JOIN post_participations pp_my ON pp_my.post_id = p.id AND pp_my.user_id = ?::uuid
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

            ParamBinder b = new ParamBinder(stmt, conn);

            // JOIN params
            b.bindUUID(callerId)
             .bindUUID(callerId);

            // Text search filter
            b.bindStringFilterTriple(f.q());

            // Tags filter
            String[] tagsArr = (f.tags() != null && !f.tags().isEmpty()) ? f.tags().toArray(new String[0]) : null;
            b.bindVarcharArrayFilterPair(tagsArr);

            // Author filter
            b.bindUUIDFilterPair(f.authorId());

            // Status filter
            String statusStr = f.status() != null ? f.status().name() : null;
            b.bindStringFilterPair(statusStr);

            // Visibility filter
            String visibilityFilter = f.visibility() != null ? f.visibility().name() : (callerId == null ? PostVisibility.PUBLIC.name() : null);
            b.bindStringFilterPair(visibilityFilter);

            // Type filter
            String typeStr = f.type() != null ? f.type().name() : null;
            b.bindStringFilterPair(typeStr);

            // Date range filters
            b.bindTimestampFilterPair(f.startsFrom());
            b.bindTimestampFilterPair(f.startsTo());

            // Geo filter
            Double radiusMeters = f.radiusKm() != null ? f.radiusKm() * 1000.0 : null;
            boolean hasGeo = f.latitude() != null && f.longitude() != null && radiusMeters != null;
            b.bindDouble(hasGeo ? f.latitude() : null)
             .bindDouble(hasGeo ? f.longitude() : null)
             .bindDouble(hasGeo ? radiusMeters : null)
             .bindDouble(hasGeo ? f.longitude() : null)
             .bindDouble(hasGeo ? f.latitude() : null)
             .bindDouble(hasGeo ? radiusMeters : null);

            // Participation filter
            boolean hasPart = f.participationTypes() != null && !f.participationTypes().isEmpty() && callerId != null;
            String[] partArr = hasPart ? f.participationTypes().toArray(new String[0]) : null;
            b.bindVarcharArray(partArr)
             .bindNullableUUID(hasPart ? callerId : null)
             .bindVarcharArray(partArr);

            // Reaction filter
            boolean hasReaction = f.reactionType() != null && callerId != null;
            b.bindString(hasReaction ? f.reactionType() : null)
             .bindNullableUUID(hasReaction ? callerId : null)
             .bindString(hasReaction ? f.reactionType() : null);

            // Cursor
            b.bindCursorParams(cursorCreatedAt, cursorId);

            // Sort
            String sort = f.sortBy() != null ? f.sortBy().toUpperCase() : "RECENT";
            b.bindString(sort)
             .bindString(sort);

            // Limit
            b.bindInt(limit);

            try (ResultSet rs = stmt.executeQuery()) {
                return mapPostCardList(rs);
            }
        }
    }

    public Optional<PostDetailDto> findById(UUID postId, UUID callerId) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.event_url, p.tags, p.type, p.status, p.visibility,
                   p.positive_reaction_count, p.negative_reaction_count, p.participant_count, p.comments_count,
                   p.starts_at, p.ends_at, p.created_at, p.last_modified_at, p.deleted_at,
                   u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
                   avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose, avatar_m.mime_type AS avatar_mime_type,
                   avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
                   avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at,
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
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            new ParamBinder(stmt, conn)
                .bindUUID(callerId)
                .bindUUID(callerId)
                .bindUUID(postId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    List<PostMediaDto> mediaList = MediaQueries.getPostMediaList(conn, postId);
                    MediaResourceDto coverMedia = mediaList.stream()
                        .filter(PostMediaDto::isCover)
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

    public List<PostMarkDto> findPostMarks(PostFeedFilters f, MapBoundsDto bounds, UUID callerId) throws SQLException {
        String sql = """
            SELECT p.id, p.title, p.description, p.positive_reaction_count,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude,
                   cover_m.object_key AS cover_url
            FROM posts p
            JOIN event_locations el ON el.id = p.event_location_id
            LEFT JOIN LATERAL (
                SELECT m.object_key
                FROM post_media pm
                JOIN media m ON m.id = pm.media_id
                WHERE pm.post_id = p.id
                ORDER BY pm.is_cover DESC, pm.position ASC
                LIMIT 1
            ) cover_m ON true
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

            ParamBinder b = new ParamBinder(stmt, conn);

            // Text search
            b.bindStringFilterTriple(f.q());

            // Tags
            String[] tagsArr = (f.tags() != null && !f.tags().isEmpty()) ? f.tags().toArray(new String[0]) : null;
            b.bindVarcharArrayFilterPair(tagsArr);

            // Author
            b.bindUUIDFilterPair(f.authorId());

            // Status
            b.bindStringFilterPair(f.status() != null ? f.status().name() : null);

            // Visibility
            String visibilityFilter = f.visibility() != null ? f.visibility().name() : (callerId == null ? PostVisibility.PUBLIC.name() : null);
            b.bindStringFilterPair(visibilityFilter);

            // Type
            b.bindStringFilterPair(f.type() != null ? f.type().name() : null);

            // Date range
            b.bindTimestampFilterPair(f.startsFrom());
            b.bindTimestampFilterPair(f.startsTo());

            // Bounds
            b.bindDouble(bounds.leftUpper().longitude())
             .bindDouble(bounds.rightBottom().latitude())
             .bindDouble(bounds.rightBottom().longitude())
             .bindDouble(bounds.leftUpper().latitude());

            // Participation filter
            boolean hasPart = f.participationTypes() != null && !f.participationTypes().isEmpty() && callerId != null;
            String[] partArr = hasPart ? f.participationTypes().toArray(new String[0]) : null;
            b.bindVarcharArray(partArr)
             .bindNullableUUID(hasPart ? callerId : null)
             .bindVarcharArray(partArr);

            // Reaction filter
            boolean hasReaction = f.reactionType() != null && callerId != null;
            b.bindString(hasReaction ? f.reactionType() : null)
             .bindNullableUUID(hasReaction ? callerId : null)
             .bindString(hasReaction ? f.reactionType() : null);

            List<PostMarkDto> list = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new PostMarkDto(
                        (UUID) rs.getObject("id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("cover_url"),
                        rs.getInt("positive_reaction_count"),
                        new GeoPointDto(rs.getDouble("latitude"), rs.getDouble("longitude"))
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

            new ParamBinder(stmt, conn)
                .bindCursorParams(cursorCreatedAt, cursorId)
                .bindInt(limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
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
                        mapInstant(rs, "created_at")
                    ));
                }
            }
        }
        return list;
    }
}
