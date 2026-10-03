package com.coolture.common.db;

import com.coolture.common.dto.CommentSummaryDto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared comment SQL fragments and queries.
 * Column aliases match what {@link ResultSetMappers#mapCommentSummary} expects.
 */
public final class CommentQueries {

    private CommentQueries() {}

    public static final String SELECT_COLUMNS = """
            c.id AS comment_id, c.post_id, c.root_comment_id, c.parent_comment_id,
            c.content, c.ancestor_ids, c.replies_count,
            c.created_at AS comment_created_at, c.last_edited_at,
            c.deleted_at AS comment_deleted_at, c.status AS comment_status,
            u.id AS author_id, u.username, u.first_name, u.last_name,
            u.created_at AS user_created_at,
            avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose,
            avatar_m.mime_type AS avatar_mime_type,
            avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
            avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at
        """;

    public static final String AUTHOR_JOINS = """
            FROM comments c
            JOIN users u ON u.id = c.author_id
            LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
            LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
        """;

    public static final String CURSOR_PREDICATE = """
            (?::timestamptz IS NULL
                OR c.created_at < ?
                OR (c.created_at = ? AND c.id < ?))
        """;

    public static Optional<CommentSummaryDto> findDetailById(Connection conn, UUID commentId) throws SQLException {
        String sql = "SELECT " + SELECT_COLUMNS + AUTHOR_JOINS + "WHERE c.id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, commentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(ResultSetMappers.mapCommentSummary(rs));
                }
            }
        }
        return Optional.empty();
    }
}
