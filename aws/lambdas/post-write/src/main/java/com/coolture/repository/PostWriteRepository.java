package com.coolture.repository;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostMediaDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.UpdatePostRequest;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.coolture.common.db.ResultSetMappers.*;

public class PostWriteRepository {

    public record ExistingPost(
        UUID id,
        UUID authorId,
        UUID eventLocationId,
        String title,
        String description,
        String eventUrl,
        Instant startsAt,
        Instant endsAt,
        List<String> tags,
        PostType type,
        PostStatus status,
        PostVisibility visibility,
        Instant createdAt,
        Instant lastModifiedAt,
        Instant deletedAt,
        EventLocationDto location
    ) {}

    public Optional<ExistingPost> findById(UUID postId) throws SQLException {
        String sql = """
            SELECT p.id, p.author_id, p.event_location_id, p.title, p.description, p.event_url,
                   p.starts_at, p.ends_at, p.tags, p.type, p.status, p.visibility,
                   p.created_at, p.last_modified_at, p.deleted_at,
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude
            FROM posts p
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.id = ?
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ExistingPost(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("author_id"),
                        (UUID) rs.getObject("event_location_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("event_url"),
                        mapInstant(rs, "starts_at"),
                        mapInstant(rs, "ends_at"),
                        mapTags(rs),
                        mapEnum(rs, "type", PostType.class),
                        mapEnum(rs, "status", PostStatus.class),
                        mapEnum(rs, "visibility", PostVisibility.class),
                        mapInstant(rs, "created_at"),
                        mapInstant(rs, "last_modified_at"),
                        mapInstant(rs, "deleted_at"),
                        mapEventLocation(rs)
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public void validateMediaOwnership(UUID callerId, List<UUID> mediaIds) throws SQLException {
        if (mediaIds == null || mediaIds.isEmpty()) return;

        String sql = "SELECT id, owner_id, status FROM media WHERE id = ANY(?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            Array array = conn.createArrayOf("uuid", mediaIds.toArray());
            stmt.setArray(1, array);

            try (ResultSet rs = stmt.executeQuery()) {
                List<UUID> foundIds = new java.util.ArrayList<>();
                while (rs.next()) {
                    UUID id = (UUID) rs.getObject("id");
                    UUID ownerId = (UUID) rs.getObject("owner_id");
                    String status = rs.getString("status");

                    if ("DELETED".equalsIgnoreCase(status)) {
                        throw new IllegalArgumentException("Media " + id + " has been deleted");
                    }
                    if (!callerId.equals(ownerId)) {
                        throw new SecurityException("You do not own media " + id);
                    }
                    foundIds.add(id);
                }

                for (UUID requestedId : mediaIds) {
                    if (!foundIds.contains(requestedId)) {
                        throw new IllegalArgumentException("Media not found: " + requestedId);
                    }
                }
            }
        }
    }

    public PostDetailDto createPost(UUID authorId, CreatePostRequest req) throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID locationId = null;
        Instant now = Instant.now();

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Insert EventLocation if OFFLINE
                if (req.type() == PostType.OFFLINE && req.location() != null) {
                    locationId = insertLocation(conn, req.location(), now);
                }

                // 2. Insert Post
                String postSql = """
                    INSERT INTO posts (
                        id, author_id, event_location_id, title, description, event_url,
                        tags, type, status, visibility,
                        positive_reaction_count, negative_reaction_count, participant_count, comments_count,
                        starts_at, ends_at, created_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?,
                        0, 0, 0, 0,
                        ?, ?, ?
                    )
                """;
                try (PreparedStatement stmt = conn.prepareStatement(postSql)) {
                    ParamBinder b = new ParamBinder(stmt, conn);
                    b.bindUUID(postId)
                     .bindUUID(authorId)
                     .bindObject(locationId)
                     .bindString(req.title())
                     .bindString(req.description())
                     .bindString(req.eventUrl());

                    if (req.tags() != null && !req.tags().isEmpty()) {
                        b.bindVarcharArray(req.tags().toArray(new String[0]));
                    } else {
                        b.bindVarcharArray(null);
                    }

                    b.bindString(req.type().name())
                     .bindString(PostStatus.ACTIVE.name())
                     .bindString((req.visibility() != null ? req.visibility() : PostVisibility.PUBLIC).name())
                     .bindTimestamp(req.startsAt())
                     .bindTimestamp(req.endsAt())
                     .bindTimestamp(now);
                    stmt.executeUpdate();
                }

                // 3. Attach Media
                attachMedia(conn, postId, req.mediaIds(), req.coverMediaId());

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        return getPostDetail(postId);
    }

    public PostDetailDto updatePost(UUID postId, UUID callerId, ExistingPost existing, UpdatePostRequest req) throws SQLException {
        Instant now = Instant.now();
        PostType newType = req.type() != null ? req.type() : existing.type();
        UUID effectiveLocationId = existing.eventLocationId();

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Handle Location updates
                if (newType == PostType.ONLINE) {
                    effectiveLocationId = null;
                } else if (req.location() != null) {
                    if (existing.eventLocationId() == null) {
                        effectiveLocationId = insertLocation(conn, req.location(), now);
                    } else {
                        updateLocationInline(conn, existing.eventLocationId(), req.location());
                    }
                }

                // 2. Handle Media updates
                if (req.mediaIds() != null) {
                    try (PreparedStatement delStmt = conn.prepareStatement("DELETE FROM post_media WHERE post_id = ?")) {
                        delStmt.setObject(1, postId);
                        delStmt.executeUpdate();
                    }
                    attachMedia(conn, postId, req.mediaIds(), req.coverMediaId());
                } else if (req.coverMediaId() != null) {
                    String updateCoverSql = "UPDATE post_media SET is_cover = (media_id = ?) WHERE post_id = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(updateCoverSql)) {
                        stmt.setObject(1, req.coverMediaId());
                        stmt.setObject(2, postId);
                        int affected = stmt.executeUpdate();
                        if (affected == 0) {
                            throw new IllegalArgumentException("coverMediaId is not attached to this post");
                        }
                    }
                }

                // 3. Update Post row
                String updatePostSql = """
                    UPDATE posts SET
                        title = ?,
                        description = ?,
                        event_url = ?,
                        tags = ?,
                        type = ?,
                        visibility = ?,
                        event_location_id = ?,
                        starts_at = ?,
                        ends_at = ?,
                        status = 'EDITED',
                        last_modified_at = ?
                    WHERE id = ?
                """;
                try (PreparedStatement stmt = conn.prepareStatement(updatePostSql)) {
                    ParamBinder b = new ParamBinder(stmt, conn);
                    b.bindString(req.title() != null ? req.title() : existing.title())
                     .bindString(req.description() != null ? req.description() : existing.description())
                     .bindString(req.eventUrl() != null ? req.eventUrl() : existing.eventUrl());

                    List<String> effectiveTags = req.tags() != null ? req.tags() : existing.tags();
                    if (effectiveTags != null && !effectiveTags.isEmpty()) {
                        b.bindVarcharArray(effectiveTags.toArray(new String[0]));
                    } else {
                        b.bindVarcharArray(null);
                    }

                    b.bindString(newType.name())
                     .bindString((req.visibility() != null ? req.visibility() : existing.visibility()).name())
                     .bindObject(effectiveLocationId)
                     .bindTimestamp(req.startsAt() != null ? req.startsAt() : existing.startsAt())
                     .bindTimestamp(req.endsAt() != null ? req.endsAt() : existing.endsAt())
                     .bindTimestamp(now)
                     .bindUUID(postId);
                    stmt.executeUpdate();
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }

        return getPostDetail(postId);
    }

    public void softDeletePost(UUID postId) throws SQLException {
        Instant now = Instant.now();
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM comments WHERE post_id = ?")) {
                    stmt.setObject(1, postId);
                    stmt.executeUpdate();
                }

                String sql = "UPDATE posts SET status = 'DELETED', deleted_at = ? WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setTimestamp(1, Timestamp.from(now));
                    stmt.setObject(2, postId);
                    stmt.executeUpdate();
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public PostDetailDto getPostDetail(UUID postId) throws SQLException {
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
                   ST_X(el.coordinates::geometry) AS longitude
            FROM posts p
            JOIN users u ON u.id = p.author_id
            LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
            LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.id = ?
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Post not found: " + postId);
                }

                List<PostMediaDto> mediaList = MediaQueries.getPostMediaList(conn, postId);
                MediaResourceDto coverMedia = mediaList.stream()
                    .filter(PostMediaDto::isCover)
                    .map(PostMediaDto::media)
                    .findFirst()
                    .orElse(null);

                return new PostDetailDto(
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
                    null,
                    null
                );
            }
        }
    }

    public EventLocationDto createLocation(EventLocationDto req) throws SQLException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String sql = """
            INSERT INTO event_locations (
                id, country_code, venue_name, building_num, street, postal_code, city,
                coordinates, created_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                ?
            )
            RETURNING id, country_code, venue_name, building_num, street, postal_code, city,
                      ST_Y(coordinates::geometry) AS latitude,
                      ST_X(coordinates::geometry) AS longitude,
                      created_at
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            bindLocationParams(new ParamBinder(stmt, conn), id, req, now);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapLocationFromReturning(rs);
                }
            }
        }
        throw new SQLException("Failed to create event location");
    }

    public EventLocationDto updateLocation(UUID locationId, EventLocationDto req) throws SQLException {
        String sql = """
            UPDATE event_locations SET
                country_code = COALESCE(?, country_code),
                venue_name = COALESCE(?, venue_name),
                building_num = COALESCE(?, building_num),
                street = COALESCE(?, street),
                postal_code = COALESCE(?, postal_code),
                city = COALESCE(?, city),
                coordinates = CASE WHEN ? IS NOT NULL AND ? IS NOT NULL THEN ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography ELSE coordinates END
            WHERE id = ?
            RETURNING id, country_code, venue_name, building_num, street, postal_code, city,
                      ST_Y(coordinates::geometry) AS latitude,
                      ST_X(coordinates::geometry) AS longitude,
                      created_at
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ParamBinder b = new ParamBinder(stmt, conn);
            b.bindString(req.countryCode())
             .bindString(req.venueName())
             .bindString(req.buildingNum())
             .bindString(req.street())
             .bindString(req.postalCode())
             .bindString(req.city());

            if (req.coordinates() != null) {
                b.bindDouble(req.coordinates().longitude())
                 .bindDouble(req.coordinates().latitude())
                 .bindDouble(req.coordinates().longitude())
                 .bindDouble(req.coordinates().latitude());
            } else {
                b.bindDouble(null).bindDouble(null)
                 .bindDouble(null).bindDouble(null);
            }
            b.bindUUID(locationId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapLocationFromReturning(rs);
                }
            }
        }
        throw new IllegalArgumentException("EventLocation not found: " + locationId);
    }

    public void deleteLocation(UUID locationId) throws SQLException {
        String checkSql = "SELECT 1 FROM posts WHERE event_location_id = ? AND deleted_at IS NULL LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection()) {
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setObject(1, locationId);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        throw new IllegalStateException("Event location is referenced by one or more posts");
                    }
                }
            }

            try (PreparedStatement delStmt = conn.prepareStatement("DELETE FROM event_locations WHERE id = ?")) {
                delStmt.setObject(1, locationId);
                int affected = delStmt.executeUpdate();
                if (affected == 0) {
                    throw new IllegalArgumentException("EventLocation not found: " + locationId);
                }
            }
        }
    }

    // --- Private helpers ---

    private UUID insertLocation(Connection conn, EventLocationDto loc, Instant now) throws SQLException {
        UUID locationId = UUID.randomUUID();
        String sql = """
            INSERT INTO event_locations (
                id, country_code, venue_name, building_num, street, postal_code, city,
                coordinates, created_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                ?
            )
        """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            bindLocationParams(new ParamBinder(stmt, conn), locationId, loc, now);
            stmt.executeUpdate();
        }
        return locationId;
    }

    private void updateLocationInline(Connection conn, UUID locationId, EventLocationDto loc) throws SQLException {
        String sql = """
            UPDATE event_locations SET
                country_code = ?, venue_name = ?, building_num = ?, street = ?,
                postal_code = ?, city = ?,
                coordinates = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
            WHERE id = ?
        """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            ParamBinder b = new ParamBinder(stmt, conn);
            b.bindString(loc.countryCode())
             .bindString(loc.venueName())
             .bindString(loc.buildingNum())
             .bindString(loc.street())
             .bindString(loc.postalCode())
             .bindString(loc.city())
             .bindDouble(loc.coordinates().longitude())
             .bindDouble(loc.coordinates().latitude())
             .bindUUID(locationId);
            stmt.executeUpdate();
        }
    }

    private void attachMedia(Connection conn, UUID postId, List<UUID> mediaIds, UUID coverMediaId) throws SQLException {
        if (mediaIds == null || mediaIds.isEmpty()) return;

        String sql = """
            INSERT INTO post_media (id, post_id, media_id, position, is_cover)
            VALUES (?, ?, ?, ?, ?)
        """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < mediaIds.size(); i++) {
                UUID mid = mediaIds.get(i);
                boolean isCover = mid.equals(coverMediaId) || (coverMediaId == null && i == 0);
                ParamBinder b = new ParamBinder(stmt, conn);
                b.bindUUID(UUID.randomUUID())
                 .bindUUID(postId)
                 .bindUUID(mid)
                 .bindInt(i)
                 .bindBoolean(isCover);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void bindLocationParams(ParamBinder b, UUID id, EventLocationDto loc, Instant now) throws SQLException {
        b.bindUUID(id)
         .bindString(loc.countryCode())
         .bindString(loc.venueName())
         .bindString(loc.buildingNum())
         .bindString(loc.street())
         .bindString(loc.postalCode())
         .bindString(loc.city())
         .bindDouble(loc.coordinates().longitude())
         .bindDouble(loc.coordinates().latitude())
         .bindTimestamp(now);
    }

    private EventLocationDto mapLocationFromReturning(ResultSet rs) throws SQLException {
        return new EventLocationDto(
            (UUID) rs.getObject("id"),
            rs.getString("country_code"),
            rs.getString("venue_name"),
            rs.getString("building_num"),
            rs.getString("street"),
            rs.getString("postal_code"),
            rs.getString("city"),
            new GeoPointDto(rs.getDouble("latitude"), rs.getDouble("longitude")),
            rs.getTimestamp("created_at").toInstant()
        );
    }
}
