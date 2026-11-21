package com.coolture.repository;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.PostKinds;
import com.coolture.common.db.PostQueries;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.StanceCounts;
import com.coolture.common.json.JsonUtils;
import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostLinkDto;
import com.coolture.common.dto.PostMediaDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.UpdatePostRequest;

import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.ReactionType;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        List<PostLinkDto> links,
        Instant startsAt,
        Instant endsAt,
        boolean openEnded,
        PostType type,
        PostStatus status,
        Instant createdAt,
        Instant lastModifiedAt,
        Instant deletedAt,
        EventLocationDto location
    ) {}

    public Optional<ExistingPost> findById(UUID postId) throws SQLException {
        String sql = """
            SELECT p.id, p.author_id, p.event_location_id, p.title, p.description, p.event_url, p.links,
                   p.starts_at, p.ends_at, p.open_ended, p.type, p.status,
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
                        mapLinks(rs),
                        mapInstant(rs, "starts_at"),
                        mapInstant(rs, "ends_at"),
                        rs.getBoolean("open_ended"),
                        mapEnum(rs, "type", PostType.class),
                        mapEnum(rs, "status", PostStatus.class),
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

    /** Checks the caller owns each media and none is deleted, and gives each one's purpose. */
    public Map<UUID, MediaPurpose> validateMediaOwnership(UUID callerId, List<UUID> mediaIds) throws SQLException {
        if (mediaIds == null || mediaIds.isEmpty()) return Map.of();

        String sql = "SELECT id, owner_id, status, purpose FROM media WHERE id = ANY(?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            Array array = conn.createArrayOf("uuid", mediaIds.toArray());
            stmt.setArray(1, array);

            try (ResultSet rs = stmt.executeQuery()) {
                Map<UUID, MediaPurpose> purposes = new HashMap<>();
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
                    String purpose = rs.getString("purpose");
                    purposes.put(id, purpose == null ? null : MediaPurpose.valueOf(purpose));
                }

                for (UUID requestedId : mediaIds) {
                    if (!purposes.containsKey(requestedId)) {
                        throw new IllegalArgumentException("Media not found: " + requestedId);
                    }
                }
                return purposes;
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
                        id, author_id, event_location_id, title, description, event_url, links,
                        type, status,
                        positive_reaction_count, participant_count, comments_count,
                        starts_at, ends_at, open_ended, created_at
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?, ?,
                        0, 0, 0,
                        ?, ?, ?, ?
                    )
                """;
                try (PreparedStatement stmt = conn.prepareStatement(postSql)) {
                    ParamBinder b = new ParamBinder(stmt, conn);
                    b.bindUUID(postId)
                     .bindUUID(authorId)
                     .bindObject(locationId)
                     .bindString(req.title())
                     .bindString(req.description())
                     .bindString(req.eventUrl())
                     .bindString(linksJson(req.links()))
                     .bindString(req.type().name())
                     .bindString(PostStatus.ACTIVE.name())
                     .bindTimestamp(req.startsAt())
                     .bindTimestamp(req.endsAt())
                     .bindBoolean(Boolean.TRUE.equals(req.openEnded()))
                     .bindTimestamp(now);
                    stmt.executeUpdate();
                }

                // 3. Attach Media
                attachMedia(conn, postId, req.mediaIds());

                PostDetailDto created = getPostDetail(conn, postId);
                conn.commit();
                return created;
            } catch (SQLException e) {
                conn.rollback();
                refuseDuplicateEvent(e);
                throw e;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** The unique index on a post's author and event_url, from V14. */
    private static final String EVENT_URL_INDEX = "posts_author_event_url_key";

    private static void refuseDuplicateEvent(SQLException e) {
        if ("23505".equals(e.getSQLState()) && e.getMessage() != null && e.getMessage().contains(EVENT_URL_INDEX)) {
            throw new DuplicateEventException(e);
        }
    }

    private static String linksJson(List<PostLinkDto> links) {
        return JsonUtils.toJson(links == null ? List.of() : links);
    }

    /** A post's fields after an edit, as the service resolved them from the request and the saved post. */
    public record Fields(String title, String description, String eventUrl, List<PostLinkDto> links, PostType type,
                         Instant startsAt, Instant endsAt, boolean openEnded) {}

    /** The place and photos come from the request; every other field from fields. */
    public PostDetailDto updatePost(UUID postId, UUID callerId, ExistingPost existing, UpdatePostRequest req,
                                    Fields fields) throws SQLException {
        Instant now = Instant.now();
        UUID effectiveLocationId = existing.eventLocationId();

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                // 1. Handle Location updates
                if (fields.type() == PostType.ONLINE) {
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
                    attachMedia(conn, postId, req.mediaIds());
                } else if (req.coverMediaId() != null) {
                    moveToFront(conn, postId, req.coverMediaId());
                }

                // 3. Update Post row
                String updatePostSql = """
                    UPDATE posts SET
                        title = ?,
                        description = ?,
                        event_url = ?,
                        links = ?::jsonb,
                        type = ?,
                        event_location_id = ?,
                        starts_at = ?,
                        ends_at = ?,
                        open_ended = ?,
                        status = 'EDITED',
                        last_modified_at = ?
                    WHERE id = ?
                """;
                try (PreparedStatement stmt = conn.prepareStatement(updatePostSql)) {
                    ParamBinder b = new ParamBinder(stmt, conn);
                    b.bindString(fields.title())
                     .bindString(fields.description())
                     .bindString(fields.eventUrl())
                     .bindString(linksJson(fields.links()))
                     .bindString(fields.type().name())
                     .bindObject(effectiveLocationId)
                     .bindTimestamp(fields.startsAt())
                     .bindTimestamp(fields.endsAt())
                     .bindBoolean(fields.openEnded())
                     .bindTimestamp(now)
                     .bindUUID(postId);
                    stmt.executeUpdate();
                }

                // Each post has its own row, so a post gone online leaves nothing pointing at it.
                if (effectiveLocationId == null && existing.eventLocationId() != null) {
                    try (PreparedStatement delStmt = conn.prepareStatement("DELETE FROM event_locations WHERE id = ?")) {
                        delStmt.setObject(1, existing.eventLocationId());
                        delStmt.executeUpdate();
                    }
                }

                PostDetailDto updated = getPostDetail(conn, postId);
                conn.commit();
                return updated;
            } catch (SQLException e) {
                conn.rollback();
                refuseDuplicateEvent(e);
                throw e;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
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
        try (Connection conn = DatabaseConfig.getConnection()) {
            return getPostDetail(conn, postId);
        }
    }

    private PostDetailDto getPostDetail(Connection conn, UUID postId) throws SQLException {
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
                   """ + PostKinds.OF_POST + """
                   AS kinds
            FROM posts p
            JOIN users u ON u.id = p.author_id
            """ + StanceCounts.JOIN + """
            LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
            LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.id = ?
        """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Post not found: " + postId);
                }

                List<PostMediaDto> mediaList = MediaQueries.getPostMediaList(conn, postId);
                MediaResourceDto coverMedia = mediaList.stream()
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
                    null,
                    null,
                    null
                );
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
            new ParamBinder(stmt, conn)
                .bindUUID(locationId)
                .bindString(loc.countryCode())
                .bindString(loc.venueName())
                .bindString(loc.buildingNum())
                .bindString(loc.street())
                .bindString(loc.postalCode())
                .bindString(loc.city())
                .bindDouble(loc.coordinates().longitude())
                .bindDouble(loc.coordinates().latitude())
                .bindTimestamp(now);
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

    /** The cover is the post's first photo, so naming a new one moves it there and the others keep their order. */
    private void moveToFront(Connection conn, UUID postId, UUID mediaId) throws SQLException {
        String sql = """
            UPDATE post_media pm
            SET position = ordered.new_position
            FROM (
                SELECT id, ROW_NUMBER() OVER (ORDER BY (media_id = ?) DESC, position ASC, id ASC) - 1 AS new_position,
                       BOOL_OR(media_id = ?) OVER () AS attached
                FROM post_media
                WHERE post_id = ?
            ) ordered
            WHERE pm.id = ordered.id AND ordered.attached
        """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, mediaId);
            stmt.setObject(2, mediaId);
            stmt.setObject(3, postId);
            if (stmt.executeUpdate() == 0) {
                throw new IllegalArgumentException("coverMediaId is not attached to this post");
            }
        }
    }

    private void attachMedia(Connection conn, UUID postId, List<UUID> mediaIds) throws SQLException {
        if (mediaIds == null || mediaIds.isEmpty()) return;

        String sql = """
            INSERT INTO post_media (id, post_id, media_id, position)
            VALUES (?, ?, ?, ?)
        """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < mediaIds.size(); i++) {
                UUID mid = mediaIds.get(i);
                ParamBinder b = new ParamBinder(stmt, conn);
                b.bindUUID(UUID.randomUUID())
                 .bindUUID(postId)
                 .bindUUID(mid)
                 .bindInt(i);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    /** A like takes back the viewer's "Nie dla mnie" on the post, which has no count. */
    public void upsertReaction(UUID postId, UUID userId, ReactionType newType) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!PostQueries.isPostVisible(conn, postId, userId)) {
                    throw new IllegalArgumentException("Post not found: " + postId);
                }
                if (findReaction(conn, postId, userId).isEmpty()) {
                    insertReaction(conn, postId, userId, newType);
                    incrementLikeCount(conn, postId);
                }
                if (findParticipation(conn, postId, userId).orElse(null) == ParticipationType.NOT_INTERESTED) {
                    deleteParticipationRow(conn, postId, userId);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public void deleteReaction(UUID postId, UUID userId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!PostQueries.exists(conn, postId)) {
                    throw new IllegalArgumentException("Post not found: " + postId);
                }
                if (findReaction(conn, postId, userId).isPresent()) {
                    deleteReactionRow(conn, postId, userId);
                    decrementLikeCount(conn, postId);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * "Nie dla mnie" isn't counted as a participant, and takes back the viewer's like. Only it can be set once the event
     * has ended, since interested and going mean nothing then. A changed stance starts its time
     * again, because the feed reads stances up to a session's start and a changed one must not act on the session it was
     * changed in.
     */
    public void upsertParticipation(UUID postId, UUID userId, ParticipationType newType) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!PostQueries.isPostVisible(conn, postId, userId)) {
                    throw new IllegalArgumentException("Post not found: " + postId);
                }
                if (counted(newType) && PostQueries.hasEnded(conn, postId)) {
                    throw new IllegalArgumentException("The event has ended");
                }
                Optional<ParticipationType> oldTypeOpt = findParticipation(conn, postId, userId);
                if (oldTypeOpt.isEmpty()) {
                    insertParticipation(conn, postId, userId, newType);
                } else if (oldTypeOpt.get() != newType) {
                    updateParticipation(conn, postId, userId, newType);
                }
                int countChange = (counted(newType) ? 1 : 0) - (oldTypeOpt.map(PostWriteRepository::counted).orElse(false) ? 1 : 0);
                changeParticipantCount(conn, postId, countChange);
                if (newType == ParticipationType.NOT_INTERESTED && findReaction(conn, postId, userId).isPresent()) {
                    deleteReactionRow(conn, postId, userId);
                    decrementLikeCount(conn, postId);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public void deleteParticipation(UUID postId, UUID userId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (!PostQueries.exists(conn, postId)) {
                    throw new IllegalArgumentException("Post not found: " + postId);
                }
                Optional<ParticipationType> oldTypeOpt = findParticipation(conn, postId, userId);
                if (oldTypeOpt.isPresent()) {
                    deleteParticipationRow(conn, postId, userId);
                    changeParticipantCount(conn, postId, counted(oldTypeOpt.get()) ? -1 : 0);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private static boolean counted(ParticipationType type) {
        return type != ParticipationType.NOT_INTERESTED;
    }

    private Optional<ReactionType> findReaction(Connection conn, UUID postId, UUID userId) throws SQLException {
        String sql = "SELECT type FROM post_reactions WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(ReactionType.valueOf(rs.getString("type")));
                }
            }
        }
        return Optional.empty();
    }

    private void insertReaction(Connection conn, UUID postId, UUID userId, ReactionType type) throws SQLException {
        String sql = "INSERT INTO post_reactions (post_id, user_id, type, created_at) VALUES (?, ?, ?, now())";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            stmt.setString(3, type.name());
            stmt.executeUpdate();
        }
    }

    private void deleteReactionRow(Connection conn, UUID postId, UUID userId) throws SQLException {
        String sql = "DELETE FROM post_reactions WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            stmt.executeUpdate();
        }
    }

    private void incrementLikeCount(Connection conn, UUID postId) throws SQLException {
        String sql = "UPDATE posts SET positive_reaction_count = positive_reaction_count + 1 WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.executeUpdate();
        }
    }

    private void decrementLikeCount(Connection conn, UUID postId) throws SQLException {
        String sql = "UPDATE posts SET positive_reaction_count = GREATEST(0, positive_reaction_count - 1) WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.executeUpdate();
        }
    }

    private Optional<ParticipationType> findParticipation(Connection conn, UUID postId, UUID userId) throws SQLException {
        String sql = "SELECT type FROM post_participations WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(ParticipationType.valueOf(rs.getString("type")));
                }
            }
        }
        return Optional.empty();
    }

    private void insertParticipation(Connection conn, UUID postId, UUID userId, ParticipationType type) throws SQLException {
        String sql = "INSERT INTO post_participations (post_id, user_id, type, created_at) VALUES (?, ?, ?, now())";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            stmt.setString(3, type.name());
            stmt.executeUpdate();
        }
    }

    private void updateParticipation(Connection conn, UUID postId, UUID userId, ParticipationType type) throws SQLException {
        String sql = "UPDATE post_participations SET type = ?, created_at = now() WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, type.name());
            stmt.setObject(2, postId);
            stmt.setObject(3, userId);
            stmt.executeUpdate();
        }
    }

    private void deleteParticipationRow(Connection conn, UUID postId, UUID userId) throws SQLException {
        String sql = "DELETE FROM post_participations WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            stmt.executeUpdate();
        }
    }

    private void changeParticipantCount(Connection conn, UUID postId, int change) throws SQLException {
        if (change == 0) {
            return;
        }
        String sql = "UPDATE posts SET participant_count = GREATEST(0, participant_count + ?) WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, change);
            stmt.setObject(2, postId);
            stmt.executeUpdate();
        }
    }
}
