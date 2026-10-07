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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CommentReadRepository {

    public List<CommentSummaryDto> findRootCommentsForPost(
            UUID postId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = "SELECT " + CommentQueries.SELECT_COLUMNS + CommentQueries.AUTHOR_JOINS
            + "WHERE c.post_id = ? AND c.parent_comment_id IS NULL AND " + CommentQueries.CURSOR_PREDICATE
            + "ORDER BY c.created_at DESC, c.id DESC LIMIT ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            ParamBinder binder = new ParamBinder(stmt, conn);
            binder.bindUUID(postId);
            binder.bindCursorParams(cursorCreatedAt, cursorId);
            binder.bindInt(limit);
            return mapCommentList(stmt);
        }
    }

    public List<CommentSummaryDto> findRepliesForParent(
            UUID parentId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = "SELECT " + CommentQueries.SELECT_COLUMNS + CommentQueries.AUTHOR_JOINS
            + "WHERE c.parent_comment_id = ? AND " + CommentQueries.CURSOR_PREDICATE
            + "ORDER BY c.created_at DESC, c.id DESC LIMIT ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            ParamBinder binder = new ParamBinder(stmt, conn);
            binder.bindUUID(parentId);
            binder.bindCursorParams(cursorCreatedAt, cursorId);
            binder.bindInt(limit);
            return mapCommentList(stmt);
        }
    }

    public record CommentHeader(UUID id, UUID postId, String status, boolean deleted) {}

    public Optional<CommentHeader> findCommentHeader(UUID commentId) throws SQLException {
        String sql = "SELECT id, post_id, status, deleted_at FROM comments WHERE id = ?";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, commentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new CommentHeader(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("post_id"),
                        rs.getString("status"),
                        rs.getTimestamp("deleted_at") != null));
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

    private List<CommentSummaryDto> mapCommentList(PreparedStatement stmt) throws SQLException {
        List<CommentSummaryDto> list = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                list.add(ResultSetMappers.mapCommentSummary(rs));
            }
        }
        return list;
    }
}
