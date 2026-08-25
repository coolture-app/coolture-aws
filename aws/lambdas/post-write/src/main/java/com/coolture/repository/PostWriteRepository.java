package com.coolture.repository;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostMediaDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
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
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                    EventLocationDto locDto = null;
                    UUID locId = (UUID) rs.getObject("loc_id");
                    if (locId != null) {
                        locDto = new EventLocationDto(
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
                    List<String> tagsList = tagsArray != null
                        ? List.of((String[]) tagsArray.getArray())
                        : Collections.emptyList();

                    Timestamp startsAtTs = rs.getTimestamp("starts_at");
                    Timestamp endsAtTs = rs.getTimestamp("ends_at");
                    Timestamp createdAtTs = rs.getTimestamp("created_at");
                    Timestamp modifiedAtTs = rs.getTimestamp("last_modified_at");
                    Timestamp deletedAtTs = rs.getTimestamp("deleted_at");

                    return Optional.of(new ExistingPost(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("author_id"),
                        (UUID) rs.getObject("event_location_id"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("event_url"),
                        startsAtTs != null ? startsAtTs.toInstant() : null,
                        endsAtTs != null ? endsAtTs.toInstant() : null,
                        tagsList,
                        PostType.valueOf(rs.getString("type")),
                        PostStatus.valueOf(rs.getString("status")),
                        PostVisibility.valueOf(rs.getString("visibility")),
                        createdAtTs != null ? createdAtTs.toInstant() : null,
                        modifiedAtTs != null ? modifiedAtTs.toInstant() : null,
                        deletedAtTs != null ? deletedAtTs.toInstant() : null,
                        locDto
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
                List<UUID> foundIds = new ArrayList<>();
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
                    locationId = UUID.randomUUID();
                    String locSql = """
                        INSERT INTO event_locations (
                            id, country_code, venue_name, building_num, street, postal_code, city,
                            coordinates, created_at
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, ?,
                            ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                            ?
                        )
                    """;
                    try (PreparedStatement stmt = conn.prepareStatement(locSql)) {
                        stmt.setObject(1, locationId);
                        stmt.setString(2, req.location().countryCode());
                        stmt.setString(3, req.location().venueName());
                        stmt.setString(4, req.location().buildingNum());
                        stmt.setString(5, req.location().street());
                        stmt.setString(6, req.location().postalCode());
                        stmt.setString(7, req.location().city());
                        stmt.setDouble(8, req.location().coordinates().longitude());
                        stmt.setDouble(9, req.location().coordinates().latitude());
                        stmt.setTimestamp(10, Timestamp.from(now));
                        stmt.executeUpdate();
                    }
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
                    stmt.setObject(1, postId);
                    stmt.setObject(2, authorId);
                    stmt.setObject(3, locationId);
                    stmt.setString(4, req.title());
                    stmt.setString(5, req.description());
                    stmt.setString(6, req.eventUrl());

                    if (req.tags() != null && !req.tags().isEmpty()) {
                        Array tagsArray = conn.createArrayOf("varchar", req.tags().toArray());
                        stmt.setArray(7, tagsArray);
                    } else {
                        stmt.setNull(7, Types.ARRAY);
                    }

                    stmt.setString(8, req.type().name());
                    stmt.setString(9, PostStatus.ACTIVE.name());
                    stmt.setString(10, (req.visibility() != null ? req.visibility() : PostVisibility.PUBLIC).name());
                    stmt.setTimestamp(11, Timestamp.from(req.startsAt()));
                    stmt.setTimestamp(12, req.endsAt() != null ? Timestamp.from(req.endsAt()) : null);
                    stmt.setTimestamp(13, Timestamp.from(now));
                    stmt.executeUpdate();
                }

                // 3. Attach Media
                if (req.mediaIds() != null && !req.mediaIds().isEmpty()) {
                    String pmSql = """
                        INSERT INTO post_media (id, post_id, media_id, position, is_cover)
                        VALUES (?, ?, ?, ?, ?)
                    """;
                    try (PreparedStatement stmt = conn.prepareStatement(pmSql)) {
                        for (int i = 0; i < req.mediaIds().size(); i++) {
                            UUID mid = req.mediaIds().get(i);
                            boolean isCover = mid.equals(req.coverMediaId()) || (req.coverMediaId() == null && i == 0);
                            stmt.setObject(1, UUID.randomUUID());
                            stmt.setObject(2, postId);
                            stmt.setObject(3, mid);
                            stmt.setInt(4, i);
                            stmt.setBoolean(5, isCover);
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }
                }

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
        boolean locationTouched = req.location() != null;
        UUID effectiveLocationId = existing.eventLocationId();

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Handle Location updates
                if (newType == PostType.ONLINE) {
                    effectiveLocationId = null;
                } else if (locationTouched) {
                    if (existing.eventLocationId() == null) {
                        effectiveLocationId = UUID.randomUUID();
                        String locSql = """
                            INSERT INTO event_locations (
                                id, country_code, venue_name, building_num, street, postal_code, city,
                                coordinates, created_at
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?,
                                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                                ?
                            )
                        """;
                        try (PreparedStatement stmt = conn.prepareStatement(locSql)) {
                            stmt.setObject(1, effectiveLocationId);
                            stmt.setString(2, req.location().countryCode());
                            stmt.setString(3, req.location().venueName());
                            stmt.setString(4, req.location().buildingNum());
                            stmt.setString(5, req.location().street());
                            stmt.setString(6, req.location().postalCode());
                            stmt.setString(7, req.location().city());
                            stmt.setDouble(8, req.location().coordinates().longitude());
                            stmt.setDouble(9, req.location().coordinates().latitude());
                            stmt.setTimestamp(10, Timestamp.from(now));
                            stmt.executeUpdate();
                        }
                    } else {
                        String locUpdateSql = """
                            UPDATE event_locations SET
                                country_code = ?, venue_name = ?, building_num = ?, street = ?,
                                postal_code = ?, city = ?,
                                coordinates = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                            WHERE id = ?
                        """;
                        try (PreparedStatement stmt = conn.prepareStatement(locUpdateSql)) {
                            stmt.setString(1, req.location().countryCode());
                            stmt.setString(2, req.location().venueName());
                            stmt.setString(3, req.location().buildingNum());
                            stmt.setString(4, req.location().street());
                            stmt.setString(5, req.location().postalCode());
                            stmt.setString(6, req.location().city());
                            stmt.setDouble(7, req.location().coordinates().longitude());
                            stmt.setDouble(8, req.location().coordinates().latitude());
                            stmt.setObject(9, existing.eventLocationId());
                            stmt.executeUpdate();
                        }
                    }
                }

                // 2. Handle Media updates
                if (req.mediaIds() != null) {
                    try (PreparedStatement delStmt = conn.prepareStatement("DELETE FROM post_media WHERE post_id = ?")) {
                        delStmt.setObject(1, postId);
                        delStmt.executeUpdate();
                    }

                    if (!req.mediaIds().isEmpty()) {
                        String pmSql = """
                            INSERT INTO post_media (id, post_id, media_id, position, is_cover)
                            VALUES (?, ?, ?, ?, ?)
                        """;
                        try (PreparedStatement stmt = conn.prepareStatement(pmSql)) {
                            for (int i = 0; i < req.mediaIds().size(); i++) {
                                UUID mid = req.mediaIds().get(i);
                                boolean isCover = mid.equals(req.coverMediaId()) || (req.coverMediaId() == null && i == 0);
                                stmt.setObject(1, UUID.randomUUID());
                                stmt.setObject(2, postId);
                                stmt.setObject(3, mid);
                                stmt.setInt(4, i);
                                stmt.setBoolean(5, isCover);
                                stmt.addBatch();
                            }
                            stmt.executeBatch();
                        }
                    }
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
                    stmt.setString(1, req.title() != null ? req.title() : existing.title());
                    stmt.setString(2, req.description() != null ? req.description() : existing.description());
                    stmt.setString(3, req.eventUrl() != null ? req.eventUrl() : existing.eventUrl());

                    List<String> effectiveTags = req.tags() != null ? req.tags() : existing.tags();
                    if (effectiveTags != null && !effectiveTags.isEmpty()) {
                        Array tagsArray = conn.createArrayOf("varchar", effectiveTags.toArray());
                        stmt.setArray(4, tagsArray);
                    } else {
                        stmt.setNull(4, Types.ARRAY);
                    }

                    stmt.setString(5, newType.name());
                    stmt.setString(6, (req.visibility() != null ? req.visibility() : existing.visibility()).name());
                    stmt.setObject(7, effectiveLocationId);

                    Instant effectiveStartsAt = req.startsAt() != null ? req.startsAt() : existing.startsAt();
                    Instant effectiveEndsAt = req.endsAt() != null ? req.endsAt() : existing.endsAt();
                    stmt.setTimestamp(8, Timestamp.from(effectiveStartsAt));
                    stmt.setTimestamp(9, effectiveEndsAt != null ? Timestamp.from(effectiveEndsAt) : null);
                    stmt.setTimestamp(10, Timestamp.from(now));
                    stmt.setObject(11, postId);
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
                   el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
                   el.street, el.postal_code, el.city,
                   ST_Y(el.coordinates::geometry) AS latitude,
                   ST_X(el.coordinates::geometry) AS longitude
            FROM posts p
            JOIN users u ON u.id = p.author_id
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

                UserSummaryDto author = new UserSummaryDto(
                    (UUID) rs.getObject("author_id"),
                    rs.getString("username"),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    null,
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

                return new PostDetailDto(
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
                );
            }
        }
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

            stmt.setObject(1, id);
            stmt.setString(2, req.countryCode());
            stmt.setString(3, req.venueName());
            stmt.setString(4, req.buildingNum());
            stmt.setString(5, req.street());
            stmt.setString(6, req.postalCode());
            stmt.setString(7, req.city());
            stmt.setDouble(8, req.coordinates().longitude());
            stmt.setDouble(9, req.coordinates().latitude());
            stmt.setTimestamp(10, Timestamp.from(now));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
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

            stmt.setString(1, req.countryCode());
            stmt.setString(2, req.venueName());
            stmt.setString(3, req.buildingNum());
            stmt.setString(4, req.street());
            stmt.setString(5, req.postalCode());
            stmt.setString(6, req.city());

            if (req.coordinates() != null) {
                stmt.setDouble(7, req.coordinates().longitude());
                stmt.setDouble(8, req.coordinates().latitude());
                stmt.setDouble(9, req.coordinates().longitude());
                stmt.setDouble(10, req.coordinates().latitude());
            } else {
                stmt.setNull(7, Types.DOUBLE);
                stmt.setNull(8, Types.DOUBLE);
                stmt.setNull(9, Types.DOUBLE);
                stmt.setNull(10, Types.DOUBLE);
            }
            stmt.setObject(11, locationId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
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
}
