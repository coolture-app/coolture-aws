package com.coolture.media.repository;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static com.coolture.common.db.ResultSetMappers.mapEnum;
import static com.coolture.common.db.ResultSetMappers.mapInstant;

public class MediaRepository {

    public record MediaRecord(
            UUID id,
            UUID ownerId,
            String objectKey,
            String fileName,
            MediaPurpose purpose,
            String mimeType,
            Long sizeBytes,
            MediaStatus status,
            Instant createdAt,
            Instant deletedAt
    ) {
        public MediaResourceDto toDto(String url) {
            return new MediaResourceDto(
                    id,
                    purpose,
                    mimeType,
                    sizeBytes,
                    status,
                    url,
                    createdAt,
                    deletedAt
            );
        }
    }

    public void insert(MediaRecord record) throws SQLException {
        String sql = """
            INSERT INTO media (id, owner_id, object_key, file_name, purpose, mime_type, size_bytes, status, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, record.id());
            stmt.setObject(2, record.ownerId());
            stmt.setString(3, record.objectKey());
            stmt.setString(4, record.fileName());
            stmt.setString(5, record.purpose() != null ? record.purpose().name() : null);
            stmt.setString(6, record.mimeType());
            stmt.setObject(7, record.sizeBytes());
            stmt.setString(8, record.status() != null ? record.status().name() : null);
            stmt.setTimestamp(9, record.createdAt() != null ? Timestamp.from(record.createdAt()) : Timestamp.from(Instant.now()));

            stmt.executeUpdate();
        }
    }

    public Optional<MediaRecord> findById(UUID id) throws SQLException {
        String sql = """
            SELECT id, owner_id, object_key, file_name, purpose, mime_type, size_bytes, status, created_at, deleted_at
            FROM media
            WHERE id = ?
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapMediaRecord(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<MediaRecord> findByIdAndOwnerId(UUID id, UUID ownerId) throws SQLException {
        String sql = """
            SELECT id, owner_id, object_key, file_name, purpose, mime_type, size_bytes, status, created_at, deleted_at
            FROM media
            WHERE id = ? AND owner_id = ?
        """;

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, id);
            stmt.setObject(2, ownerId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapMediaRecord(rs));
                }
            }
        }
        return Optional.empty();
    }

    public boolean existsById(UUID id) throws SQLException {
        String sql = "SELECT 1 FROM media WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setObject(1, id);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void updateStatus(UUID id, MediaStatus status) throws SQLException {
        String sql = "UPDATE media SET status = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, status.name());
            stmt.setObject(2, id);

            stmt.executeUpdate();
        }
    }

    public void markDeleted(UUID id, Instant deletedAt) throws SQLException {
        String sql = "UPDATE media SET status = ?, deleted_at = ? WHERE id = ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, MediaStatus.DELETED.name());
            stmt.setTimestamp(2, Timestamp.from(deletedAt != null ? deletedAt : Instant.now()));
            stmt.setObject(3, id);

            stmt.executeUpdate();
        }
    }

    public boolean isMediaInUse(UUID id) throws SQLException {
        String postMediaSql = "SELECT 1 FROM post_media WHERE media_id = ? LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(postMediaSql)) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return true;
            }
        }

        String profileImageSql = "SELECT 1 FROM profile_images WHERE (full_media_id = ? OR thumbnail_media_id = ?) AND is_active = true LIMIT 1";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(profileImageSql)) {
            stmt.setObject(1, id);
            stmt.setObject(2, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return true;
            }
        }

        return false;
    }

    private static MediaRecord mapMediaRecord(ResultSet rs) throws SQLException {
        return new MediaRecord(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("owner_id"),
                rs.getString("object_key"),
                rs.getString("file_name"),
                mapEnum(rs, "purpose", MediaPurpose.class),
                rs.getString("mime_type"),
                rs.getObject("size_bytes") != null ? rs.getLong("size_bytes") : null,
                mapEnum(rs, "status", MediaStatus.class),
                mapInstant(rs, "created_at"),
                mapInstant(rs, "deleted_at")
        );
    }
}
