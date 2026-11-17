package com.coolture.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class PostQueries {

    private PostQueries() {}

    private static final String LIVE = "p.id = ? AND p.deleted_at IS NULL AND p.status <> 'DELETED'";

    /** Not deleted, whoever asks. Removing your own like, stance or comment needs only this. */
    public static boolean exists(Connection conn, UUID postId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM posts p WHERE " + LIVE)) {
            new ParamBinder(stmt, conn).bindUUID(postId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Not deleted, and its author and the viewer aren't a blocked pair. A blocked pair sees what a deleted post shows. */
    public static boolean isPostVisible(Connection conn, UUID postId, UUID viewer) throws SQLException {
        String sql = "SELECT 1 FROM posts p WHERE " + LIVE + " AND " + Blocks.notWithViewer("p.author_id");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            Blocks.bindViewer(new ParamBinder(stmt, conn).bindUUID(postId), viewer);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public static void ensurePostVisible(Connection conn, UUID postId, UUID viewer) throws SQLException {
        if (!isPostVisible(conn, postId, viewer)) {
            throw new IllegalArgumentException("Post with id '" + postId + "' was not found");
        }
    }

    public static boolean hasEnded(Connection conn, UUID postId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT " + EventTime.ENDED + " FROM posts p WHERE p.id = ?")) {
            new ParamBinder(stmt, conn).bindUUID(postId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
