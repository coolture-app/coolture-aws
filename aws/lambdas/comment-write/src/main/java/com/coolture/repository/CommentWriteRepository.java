package com.coolture.repository;

import com.coolture.common.db.CommentQueries;
import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.PostQueries;
import com.coolture.common.db.ResultSetMappers;
import com.coolture.common.dto.CommentSummaryDto;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class CommentWriteRepository {

    public record ExistingComment(
        UUID id,
        UUID postId,
        UUID authorId,
        UUID rootCommentId,
        UUID parentCommentId,
        UUID[] ancestorIds,
        String status,
        Instant deletedAt
    ) {
        public int depth() {
            return ancestorIds == null ? 0 : ancestorIds.length;
        }
    }

    public Optional<ExistingComment> findExistingComment(UUID commentId) throws SQLException {
        String sql = """
            SELECT id, post_id, author_id, root_comment_id, parent_comment_id,
                   ancestor_ids, status, deleted_at
            FROM comments WHERE id = ?
        """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, commentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ExistingComment(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("post_id"),
                        (UUID) rs.getObject("author_id"),
                        (UUID) rs.getObject("root_comment_id"),
                        (UUID) rs.getObject("parent_comment_id"),
                        ResultSetMappers.mapUUIDArray(rs, "ancestor_ids"),
                        rs.getString("status"),
                        ResultSetMappers.mapInstant(rs, "deleted_at")));
                }
            }
        }
        return Optional.empty();
    }

    public void ensurePostActive(UUID postId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            PostQueries.ensurePostActive(conn, postId);
        }
    }

    public CommentSummaryDto createComment(
            UUID postId, UUID authorId, String content,
            UUID rootCommentId, UUID parentCommentId, UUID[] ancestorIds) throws SQLException {
        UUID commentId = UUID.randomUUID();
        Instant now = Instant.now();

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                PostQueries.ensurePostActive(conn, postId);

                String insertSql = """
                    INSERT INTO comments (
                        id, post_id, author_id, root_comment_id, parent_comment_id,
                        ancestor_ids, content, replies_count, status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', ?)
                """;
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    ParamBinder binder = new ParamBinder(stmt, conn);
                    binder.bindUUID(commentId)
                        .bindUUID(postId)
                        .bindUUID(authorId)
                        .bindNullableUUID(rootCommentId)
                        .bindNullableUUID(parentCommentId)
                        .bindUUIDArray(ancestorIds)
                        .bindString(content)
                        .bindTimestamp(now);
                    stmt.executeUpdate();
                }

                if (ancestorIds != null && ancestorIds.length > 0) {
                    incrementRepliesCount(conn, ancestorIds);
                }
                incrementPostCommentsCount(conn, postId);

                CommentSummaryDto created = CommentQueries.findDetailById(conn, commentId)
                    .orElseThrow(() -> new IllegalStateException("Failed to load created comment"));
                conn.commit();
                return created;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public CommentSummaryDto updateComment(UUID commentId, String content) throws SQLException {
        Instant now = Instant.now();
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                String sql = "UPDATE comments SET content = ?, last_edited_at = ? "
                    + "WHERE id = ? AND status = 'ACTIVE'";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    ParamBinder binder = new ParamBinder(stmt, conn);
                    binder.bindString(content).bindTimestamp(now).bindUUID(commentId);
                    int updated = stmt.executeUpdate();
                    if (updated == 0) {
                        conn.rollback();
                        throw new IllegalArgumentException(
                            "Comment with id '" + commentId + "' was not found");
                    }
                }
                CommentSummaryDto updated = CommentQueries.findDetailById(conn, commentId)
                    .orElseThrow(() -> new IllegalArgumentException(
                        "Comment with id '" + commentId + "' was not found"));
                conn.commit();
                return updated;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public void softDeleteComment(UUID commentId, UUID postId, UUID[] ancestorIds) throws SQLException {
        Instant now = Instant.now();
        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                int targetDeleted;
                String sql = "UPDATE comments SET status = 'DELETED', deleted_at = ?, content = '' "
                    + "WHERE id = ? AND status = 'ACTIVE'";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    ParamBinder binder = new ParamBinder(stmt, conn);
                    binder.bindTimestamp(now).bindUUID(commentId);
                    targetDeleted = stmt.executeUpdate();
                }
                if (targetDeleted == 0) {
                    conn.rollback();
                    throw new IllegalArgumentException(
                        "Comment with id '" + commentId + "' was not found");
                }

                if (ancestorIds != null && ancestorIds.length > 0) {
                    decrementRepliesCount(conn, ancestorIds, 1);
                }
                decrementPostCommentsCount(conn, postId, 1);

                conn.commit();
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private void incrementRepliesCount(Connection conn, UUID[] ids) throws SQLException {
        String sql = "UPDATE comments SET replies_count = replies_count + 1 WHERE id = ANY(?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setArray(1, conn.createArrayOf("uuid", ids));
            stmt.executeUpdate();
        }
    }

    private void decrementRepliesCount(Connection conn, UUID[] ids, int amount) throws SQLException {
        String sql = "UPDATE comments SET replies_count = GREATEST(replies_count - ?, 0) WHERE id = ANY(?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, amount);
            stmt.setArray(2, conn.createArrayOf("uuid", ids));
            stmt.executeUpdate();
        }
    }

    private void incrementPostCommentsCount(Connection conn, UUID postId) throws SQLException {
        String sql = "UPDATE posts SET comments_count = comments_count + 1 WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.executeUpdate();
        }
    }

    private void decrementPostCommentsCount(Connection conn, UUID postId, int amount) throws SQLException {
        String sql = "UPDATE posts SET comments_count = GREATEST(comments_count - ?, 0) WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, amount);
            stmt.setObject(2, postId);
            stmt.executeUpdate();
        }
    }
}
