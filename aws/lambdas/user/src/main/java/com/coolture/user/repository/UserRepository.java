package com.coolture.user.repository;

import com.coolture.common.db.Blocks;
import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.TextSearch;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static com.coolture.common.db.ResultSetMappers.mapEnum;
import static com.coolture.common.db.ResultSetMappers.mapInstant;

public class UserRepository {

    public record UserEntity(
            UUID id,
            String username,
            String firstName,
            String lastName,
            String bio,
            Instant createdAt
    ) {}

    public record AvatarRecord(
            UUID id,
            String objectKey,
            MediaPurpose purpose,
            String mimeType,
            Long sizeBytes,
            MediaStatus status,
            Instant createdAt,
            Instant deletedAt
    ) {}

    /**
     * Creates a user if one with the given ID does not already exist.
     * Uses ON CONFLICT DO NOTHING to handle concurrent inserts safely.
     * Returns the existing or newly created user.
     */
    public UserEntity createUserIfAbsent(UUID id, String username, String firstName, String lastName) throws SQLException {
        Optional<UserEntity> existing = findById(id);
        if (existing.isPresent()) {
            return existing.get();
        }

        String sql = """
            INSERT INTO users (id, username, first_name, last_name, bio, created_at)
            VALUES (?, ?, ?, ?, null, now())
            ON CONFLICT (id) DO NOTHING
        """;

        try (Connection conn = DatabaseConfig.getConnection()) {
            // Try inserting with the given username
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setObject(1, id);
                stmt.setString(2, username);
                stmt.setString(3, firstName);
                stmt.setString(4, lastName);
                stmt.executeUpdate();
            } catch (SQLException e) {
                // Username uniqueness violation — retry with a fallback username
                if ("23505".equals(e.getSQLState())) {
                    Optional<UserEntity> user = findById(id);
                    if (user.isPresent()) {
                        return user.get();
                    }
                    String fallbackUsername = "user_" + id.toString().replace("-", "").substring(0, 12);
                    try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setObject(1, id);
                        stmt.setString(2, fallbackUsername);
                        stmt.setString(3, firstName);
                        stmt.setString(4, lastName);
                        stmt.executeUpdate();
                    }
                } else {
                    throw e;
                }
            }
        }
        // Always return the current row (whether just inserted or already existed)
        return findById(id).orElseThrow(() -> new SQLException("Failed to create or find user " + id));
    }

    public Optional<UserEntity> findById(UUID id) throws SQLException {
        String sql = "SELECT id, username, first_name, last_name, bio, created_at FROM users WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** False for a user who doesn't exist yet. */
    public boolean sharesStances(UUID userId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT share_stances FROM users WHERE id = ?")) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getBoolean("share_stances");
            }
        }
    }

    public record HomeRecord(String name, double latitude, double longitude) {}

    public Optional<HomeRecord> findHome(UUID userId) throws SQLException {
        String sql = """
            SELECT home_name, ST_Y(home_location::geometry) AS latitude, ST_X(home_location::geometry) AS longitude
            FROM users
            WHERE id = ? AND home_name IS NOT NULL
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new HomeRecord(rs.getString("home_name"), rs.getDouble("latitude"), rs.getDouble("longitude")));
                }
            }
        }
        return Optional.empty();
    }

    /** Returns false when the user doesn't exist. */
    public boolean setHome(UUID userId, HomeRecord home) throws SQLException {
        String sql = """
            UPDATE users
            SET home_name = ?, home_location = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
            WHERE id = ?
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, home.name());
            stmt.setDouble(2, home.longitude());
            stmt.setDouble(3, home.latitude());
            stmt.setObject(4, userId);
            return stmt.executeUpdate() > 0;
        }
    }

    public void clearHome(UUID userId) throws SQLException {
        String sql = "UPDATE users SET home_name = NULL, home_location = NULL WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, userId);
            stmt.executeUpdate();
        }
    }

    public Optional<UserEntity> findByUsername(String username) throws SQLException {
        String sql = "SELECT id, username, first_name, last_name, bio, created_at FROM users WHERE username = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapUser(rs));
                }
            }
        }
        return Optional.empty();
    }

    public UserEntity updateUser(UUID id, String username, String firstName, String lastName, String bio,
                                 Boolean shareStances) throws SQLException {
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (username != null) {
            setClauses.add("username = ?");
            params.add(username);
        }
        if (firstName != null) {
            setClauses.add("first_name = ?");
            params.add(firstName);
        }
        if (lastName != null) {
            setClauses.add("last_name = ?");
            params.add(lastName);
        }
        if (bio != null) {
            setClauses.add("bio = ?");
            params.add(bio);
        }
        if (shareStances != null) {
            setClauses.add("share_stances = ?");
            params.add(shareStances);
        }

        if (setClauses.isEmpty()) {
            return findById(id).orElseThrow(() -> new IllegalArgumentException("User " + id + " not found"));
        }

        String sql = "UPDATE users SET " + String.join(", ", setClauses) +
                     " WHERE id = ? RETURNING id, username, first_name, last_name, bio, created_at";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int idx = 1;
            for (Object param : params) {
                stmt.setObject(idx++, param);
            }
            stmt.setObject(idx, id);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapUser(rs);
                }
            }
        }
        throw new IllegalArgumentException("User " + id + " not found");
    }

    /**
     * Whether user u follows the caller, whose id is bound as parameter 1, so it goes in the SELECT list. A guest's
     * null matches no row, and a block deletes the follow.
     */
    private static final String FOLLOWS_CALLER = """
        EXISTS (SELECT 1 FROM user_relations f
                WHERE f.source_user_id = u.id AND f.target_user_id = ?::uuid AND f.type = 'FOLLOW')""";

    public record ListedUser(UserEntity user, boolean followsYou) {}

    public List<ListedUser> search(UUID callerId, String q, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT id, username, first_name, last_name, bio, created_at,
                   %s AS follows_you
            FROM users u
            WHERE %s
              AND %s
              AND (?::timestamptz IS NULL OR (created_at < ?::timestamptz OR (created_at = ?::timestamptz AND id < ?::uuid)))
            ORDER BY created_at DESC, id DESC
            LIMIT ?
        """.formatted(FOLLOWS_CALLER, TextSearch.matchesAll("u.username", "u.first_name", "u.last_name"),
            Blocks.notWithViewer("u.id"));

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            ParamBinder b = new ParamBinder(stmt, conn).bindNullableUUID(callerId);
            TextSearch.bind(b, q);
            Blocks.bindViewer(b, callerId)
                .bindCursorParams(cursorCreatedAt, cursorId)
                .bindInt(limit);

            List<ListedUser> users = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    users.add(new ListedUser(mapUser(rs), rs.getBoolean("follows_you")));
                }
            }
            return users;
        }
    }

    public int countFollowers(UUID userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_relations WHERE target_user_id = ? AND type = 'FOLLOW'";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    public int countFollowing(UUID userId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_relations WHERE source_user_id = ? AND type = 'FOLLOW'";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    public boolean isFollowing(UUID sourceUserId, UUID targetUserId) throws SQLException {
        if (sourceUserId == null || targetUserId == null) return false;
        String sql = "SELECT 1 FROM user_relations WHERE source_user_id = ? AND target_user_id = ? AND type = 'FOLLOW'";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, sourceUserId);
            stmt.setObject(2, targetUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean existsById(UUID id) throws SQLException {
        if (id == null) return false;
        String sql = "SELECT 1 FROM users WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean isBlocked(UUID sourceUserId, UUID targetUserId) throws SQLException {
        if (sourceUserId == null || targetUserId == null) return false;
        String sql = "SELECT 1 FROM user_relations WHERE source_user_id = ? AND target_user_id = ? AND type = 'BLOCK'";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, sourceUserId);
            stmt.setObject(2, targetUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public record UserRelationRow(UserEntity user, Instant relationCreatedAt, boolean followsYou) {}

    public List<UserRelationRow> findFollowers(UUID userId, UUID callerId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT u.id, u.username, u.first_name, u.last_name, u.bio, u.created_at, ur.created_at AS relation_created_at,
                   %s AS follows_you
            FROM user_relations ur
            JOIN users u ON u.id = ur.source_user_id
            WHERE ur.target_user_id = ?
              AND ur.type = 'FOLLOW'
              AND %s
              AND (?::timestamptz IS NULL OR (ur.created_at < ?::timestamptz OR (ur.created_at = ?::timestamptz AND ur.source_user_id < ?::uuid)))
            ORDER BY ur.created_at DESC, ur.source_user_id DESC
            LIMIT ?
        """.formatted(FOLLOWS_CALLER, Blocks.notWithViewer("u.id"));
        return queryUserRelations(sql, userId, callerId, true, cursorCreatedAt, cursorId, limit);
    }

    public List<UserRelationRow> findFollowing(UUID userId, UUID callerId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT u.id, u.username, u.first_name, u.last_name, u.bio, u.created_at, ur.created_at AS relation_created_at,
                   %s AS follows_you
            FROM user_relations ur
            JOIN users u ON u.id = ur.target_user_id
            WHERE ur.source_user_id = ?
              AND ur.type = 'FOLLOW'
              AND %s
              AND (?::timestamptz IS NULL OR (ur.created_at < ?::timestamptz OR (ur.created_at = ?::timestamptz AND ur.target_user_id < ?::uuid)))
            ORDER BY ur.created_at DESC, ur.target_user_id DESC
            LIMIT ?
        """.formatted(FOLLOWS_CALLER, Blocks.notWithViewer("u.id"));
        return queryUserRelations(sql, userId, callerId, true, cursorCreatedAt, cursorId, limit);
    }

    public List<UserRelationRow> findBlocking(UUID userId, UUID callerId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = """
            SELECT u.id, u.username, u.first_name, u.last_name, u.bio, u.created_at, ur.created_at AS relation_created_at,
                   %s AS follows_you
            FROM user_relations ur
            JOIN users u ON u.id = ur.target_user_id
            WHERE ur.source_user_id = ?
              AND ur.type = 'BLOCK'
              AND (?::timestamptz IS NULL OR (ur.created_at < ?::timestamptz OR (ur.created_at = ?::timestamptz AND ur.target_user_id < ?::uuid)))
            ORDER BY ur.created_at DESC, ur.target_user_id DESC
            LIMIT ?
        """.formatted(FOLLOWS_CALLER);
        return queryUserRelations(sql, userId, callerId, false, cursorCreatedAt, cursorId, limit);
    }

    private List<UserRelationRow> queryUserRelations(String sql, UUID userId, UUID callerId, boolean leavesOutBlocked,
                                                     Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            ParamBinder b = new ParamBinder(stmt, conn)
                .bindNullableUUID(callerId)
                .bindUUID(userId);
            if (leavesOutBlocked) {
                Blocks.bindViewer(b, callerId);
            }
            b.bindTimestamp(cursorCreatedAt)
             .bindTimestamp(cursorCreatedAt)
             .bindTimestamp(cursorCreatedAt)
             .bindNullableUUID(cursorId)
             .bindInt(limit);

            List<UserRelationRow> list = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    UserEntity user = mapUser(rs);
                    Instant relCreatedAt = mapInstant(rs, "relation_created_at");
                    list.add(new UserRelationRow(user, relCreatedAt, rs.getBoolean("follows_you")));
                }
            }
            return list;
        }
    }

    public void insertRelation(UUID sourceUserId, UUID targetUserId, String type, Instant createdAt) throws SQLException {
        String sql = "INSERT INTO user_relations (source_user_id, target_user_id, type, created_at) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, sourceUserId);
            stmt.setObject(2, targetUserId);
            stmt.setString(3, type);
            stmt.setTimestamp(4, Timestamp.from(createdAt != null ? createdAt : Instant.now()));
            stmt.executeUpdate();
        }
    }

    public int deleteRelation(UUID sourceUserId, UUID targetUserId, String type) throws SQLException {
        String sql = "DELETE FROM user_relations WHERE source_user_id = ? AND target_user_id = ? AND type = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, sourceUserId);
            stmt.setObject(2, targetUserId);
            stmt.setString(3, type);
            return stmt.executeUpdate();
        }
    }

    public void deleteRelationAny(UUID sourceUserId, UUID targetUserId) throws SQLException {
        String sql = "DELETE FROM user_relations WHERE source_user_id = ? AND target_user_id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, sourceUserId);
            stmt.setObject(2, targetUserId);
            stmt.executeUpdate();
        }
    }

    public Optional<AvatarRecord> findActiveAvatar(UUID userId, boolean thumbnail) throws SQLException {
        String sql = """
            SELECT m.id, m.object_key, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at
            FROM profile_images pi
            JOIN media m ON m.id = (CASE WHEN ? = true THEN pi.thumbnail_media_id ELSE pi.full_media_id END)
            WHERE pi.user_id = ? AND pi.is_active = true AND m.status != 'DELETED'
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setBoolean(1, thumbnail);
            stmt.setObject(2, userId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapAvatar(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Map<UUID, AvatarRecord> findActiveThumbnails(Collection<UUID> userIds) throws SQLException {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();

        String sql = """
            SELECT pi.user_id, m.id, m.object_key, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at
            FROM profile_images pi
            JOIN media m ON m.id = pi.thumbnail_media_id
            WHERE pi.user_id = ANY(?) AND pi.is_active = true AND m.status != 'DELETED'
        """;

        Map<UUID, AvatarRecord> result = new HashMap<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            Array array = conn.createArrayOf("uuid", userIds.toArray());
            stmt.setArray(1, array);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    UUID userId = (UUID) rs.getObject("user_id");
                    result.put(userId, mapAvatar(rs));
                }
            }
        }
        return result;
    }

    public void validateMediaOwnedAndNotDeleted(UUID mediaId, UUID ownerId) throws SQLException {
        String sql = "SELECT id, status FROM media WHERE id = ? AND owner_id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, mediaId);
            stmt.setObject(2, ownerId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Media " + mediaId + " not found");
                }
                String status = rs.getString("status");
                if ("DELETED".equalsIgnoreCase(status)) {
                    throw new IllegalArgumentException("Media " + mediaId + " not found");
                }
            }
        }
    }

    public void deactivateProfileImages(UUID userId, Instant unsetAt) throws SQLException {
        String sql = "UPDATE profile_images SET is_active = false, unset_at = ? WHERE user_id = ? AND is_active = true";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setTimestamp(1, Timestamp.from(unsetAt != null ? unsetAt : Instant.now()));
            stmt.setObject(2, userId);

            stmt.executeUpdate();
        }
    }

    public void insertProfileImage(UUID userId, UUID fullMediaId, UUID thumbnailMediaId, Instant setAt) throws SQLException {
        String sql = """
            INSERT INTO profile_images (id, user_id, full_media_id, thumbnail_media_id, is_active, set_at)
            VALUES (gen_random_uuid(), ?, ?, ?, true, ?)
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, userId);
            stmt.setObject(2, fullMediaId);
            stmt.setObject(3, thumbnailMediaId);
            stmt.setTimestamp(4, Timestamp.from(setAt != null ? setAt : Instant.now()));

            stmt.executeUpdate();
        }
    }

    /**
     * Atomically deactivates old profile images and inserts a new one in a single transaction.
     * Prevents race conditions where a failure between the two operations leaves the user with no avatar,
     * and prevents concurrent requests from leaving two active rows.
     */
    public void setProfileImageTransactional(UUID userId, UUID fullMediaId, UUID thumbnailMediaId, Instant now) throws SQLException {
        String lockSql = "SELECT 1 FROM users WHERE id = ? FOR UPDATE";
        String deactivateSql = "UPDATE profile_images SET is_active = false, unset_at = ? WHERE user_id = ? AND is_active = true";
        String insertSql = """
            INSERT INTO profile_images (id, user_id, full_media_id, thumbnail_media_id, is_active, set_at)
            VALUES (gen_random_uuid(), ?, ?, ?, true, ?)
        """;

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(lockSql)) {
                    lock.setObject(1, userId);
                    try (ResultSet rs = lock.executeQuery()) {
                        // row locked
                    }
                }
                try (PreparedStatement deactivate = conn.prepareStatement(deactivateSql)) {
                    deactivate.setTimestamp(1, Timestamp.from(now));
                    deactivate.setObject(2, userId);
                    deactivate.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                    insert.setObject(1, userId);
                    insert.setObject(2, fullMediaId);
                    insert.setObject(3, thumbnailMediaId);
                    insert.setTimestamp(4, Timestamp.from(now));
                    insert.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public boolean isBlockedEitherDirection(UUID userId1, UUID userId2) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return Blocks.between(conn, userId1, userId2);
        }
    }

    private static UserEntity mapUser(ResultSet rs) throws SQLException {
        return new UserEntity(
                (UUID) rs.getObject("id"),
                rs.getString("username"),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("bio"),
                mapInstant(rs, "created_at")
        );
    }

    private static AvatarRecord mapAvatar(ResultSet rs) throws SQLException {
        return new AvatarRecord(
                (UUID) rs.getObject("id"),
                rs.getString("object_key"),
                mapEnum(rs, "purpose", MediaPurpose.class),
                rs.getString("mime_type"),
                rs.getObject("size_bytes") != null ? rs.getLong("size_bytes") : null,
                mapEnum(rs, "status", MediaStatus.class),
                mapInstant(rs, "created_at"),
                mapInstant(rs, "deleted_at")
        );
    }
}
