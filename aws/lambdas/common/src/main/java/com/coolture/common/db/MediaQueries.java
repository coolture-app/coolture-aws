package com.coolture.common.db;

import com.coolture.common.dto.PostMediaDto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared media database queries.
 */
public final class MediaQueries {

    private MediaQueries() {}

    public static List<PostMediaDto> getPostMediaList(Connection conn, UUID postId) throws SQLException {
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
                    list.add(ResultSetMappers.mapPostMedia(rs));
                }
            }
        }
        return list;
    }
}
