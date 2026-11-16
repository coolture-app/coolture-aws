package com.coolture.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class PostQueries {

    private PostQueries() {}

    public static boolean isPostVisible(Connection conn, UUID postId) throws SQLException {
        String sql = "SELECT 1 FROM posts p WHERE p.id = ? AND p.deleted_at IS NULL AND p.status <> 'DELETED'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            new ParamBinder(stmt, conn).bindUUID(postId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void ensurePostVisible(Connection conn, UUID postId) throws SQLException {
        if (!isPostVisible(conn, postId)) {
            throw new IllegalArgumentException("Post with id '" + postId + "' was not found");
        }
    }
}
