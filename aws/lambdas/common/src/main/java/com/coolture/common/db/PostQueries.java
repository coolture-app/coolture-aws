package com.coolture.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class PostQueries {

    public static final String VISIBLE_TO_CALLER = """
        (p.visibility = 'PUBLIC'
         OR p.author_id = ?::uuid
         OR (p.visibility = 'FRIENDS' AND EXISTS (SELECT 1 FROM user_relations ur WHERE ur.source_user_id = ?::uuid AND ur.target_user_id = p.author_id AND ur.type = 'FOLLOW')))""";

    private PostQueries() {}

    public static boolean isPostVisible(Connection conn, UUID postId, UUID callerId) throws SQLException {
        String sql = "SELECT 1 FROM posts p WHERE p.id = ? AND p.deleted_at IS NULL AND p.status <> 'DELETED' AND "
            + VISIBLE_TO_CALLER;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            new ParamBinder(stmt, conn)
                .bindUUID(postId)
                .bindNullableUUID(callerId)
                .bindNullableUUID(callerId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void ensurePostVisible(Connection conn, UUID postId, UUID callerId) throws SQLException {
        if (!isPostVisible(conn, postId, callerId)) {
            throw new IllegalArgumentException("Post with id '" + postId + "' was not found");
        }
    }
}
