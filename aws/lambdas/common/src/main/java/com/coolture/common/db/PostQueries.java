package com.coolture.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class PostQueries {

    private PostQueries() {}

    public static void ensurePostActive(Connection conn, UUID postId) throws SQLException {
        String sql = "SELECT status, deleted_at FROM posts WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()
                        || "DELETED".equals(rs.getString("status"))
                        || rs.getTimestamp("deleted_at") != null) {
                    throw new IllegalArgumentException("Post with id '" + postId + "' was not found");
                }
            }
        }
    }
}
