package com.coolture.common.db;

import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostMediaDto;
import com.coolture.common.validation.PostLimits;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared media database queries.
 */
public final class MediaQueries {

    private MediaQueries() {}

    public static List<PostMediaDto> getPostMediaList(Connection conn, UUID postId) throws SQLException {
        String sql = """
            SELECT pm.position, pm.is_cover,
                   m.id AS media_id, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at, m.object_key,
                   m.width, m.height
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
                    list.add(ResultSetMappers.mapPostMedia(rs));
                }
            }
        }
        return list;
    }

    /** Fills in each card's photos, cover first, with one query for the whole list. */
    public static List<PostCardDto> withPhotos(Connection conn, List<PostCardDto> cards) throws SQLException {
        if (cards.isEmpty()) {
            return cards;
        }
        String sql = """
            SELECT post_id, media_id, purpose, mime_type, size_bytes, status, created_at, deleted_at, object_key, width, height
            FROM (
                SELECT pm.post_id, m.id AS media_id, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at,
                       m.deleted_at, m.object_key, m.width, m.height,
                       ROW_NUMBER() OVER (PARTITION BY pm.post_id ORDER BY pm.is_cover DESC, pm.position ASC) AS n
                FROM post_media pm
                JOIN media m ON m.id = pm.media_id
                WHERE pm.post_id = ANY(?::uuid[])
            ) photos
            WHERE n <= ?
            ORDER BY post_id, n
        """;

        Map<UUID, List<MediaResourceDto>> byPost = new HashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            new ParamBinder(stmt, conn)
                .bindUUIDArray(cards.stream().map(PostCardDto::id).toArray(UUID[]::new))
                .bindInt(PostLimits.MAX_PHOTOS);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    byPost.computeIfAbsent((UUID) rs.getObject("post_id"), id -> new ArrayList<>())
                        .add(ResultSetMappers.mapMediaResource(rs, "media_id"));
                }
            }
        }
        return cards.stream().map(card -> card.withPhotos(byPost.get(card.id()))).toList();
    }
}
