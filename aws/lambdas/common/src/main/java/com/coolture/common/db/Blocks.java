package com.coolture.common.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A block works both ways: two users are a blocked pair when either blocked the other. Neither sees the other's posts,
 * comments or profile, and nothing says who blocked whom. A guest has no blocks.
 */
public final class Blocks {

    private Blocks() {}

    /** Whether the user in {@code userColumn} and the viewer are a blocked pair. Bind with {@link #bindViewer}. */
    public static String withViewer(String userColumn) {
        return "EXISTS (SELECT 1 FROM user_relations blk WHERE blk.type = 'BLOCK'"
            + " AND ((blk.source_user_id = ?::uuid AND blk.target_user_id = " + userColumn + ")"
            + " OR (blk.source_user_id = " + userColumn + " AND blk.target_user_id = ?::uuid)))";
    }

    public static String notWithViewer(String userColumn) {
        return "NOT " + withViewer(userColumn);
    }

    public static ParamBinder bindViewer(ParamBinder b, UUID viewer) throws SQLException {
        return b.bindNullableUUID(viewer).bindNullableUUID(viewer);
    }

    public static boolean between(Connection conn, UUID a, UUID b) throws SQLException {
        if (a == null || b == null) {
            return false;
        }
        String sql = "SELECT " + withViewer("?::uuid");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            // withViewer reads the viewer, then the column, then the column, then the viewer.
            new ParamBinder(stmt, conn).bindUUID(a).bindUUID(b).bindUUID(b).bindUUID(a);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
