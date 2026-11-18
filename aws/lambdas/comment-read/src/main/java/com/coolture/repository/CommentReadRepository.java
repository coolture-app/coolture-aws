package com.coolture.repository;

import com.coolture.common.db.Blocks;
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
            UUID postId, UUID viewer, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = "SELECT " + CommentQueries.SELECT_COLUMNS + CommentQueries.AUTHOR_JOINS
            + "WHERE c.post_id = ? AND c.parent_comment_id IS NULL AND " + CommentQueries.NOT_HIDDEN
            + " AND " + CommentQueries.CURSOR_PREDICATE
            + "ORDER BY c.created_at DESC, c.id DESC LIMIT ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            ParamBinder binder = new ParamBinder(stmt, conn);
            binder.bindUUID(postId);
            Blocks.bindViewer(binder, viewer);
            binder.bindCursorParams(cursorCreatedAt, cursorId);
            binder.bindInt(limit);
            return mapCommentList(stmt);
        }
    }

    public List<CommentSummaryDto> findRepliesForParent(
            UUID parentId, UUID viewer, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        String sql = "SELECT " + CommentQueries.SELECT_COLUMNS + CommentQueries.AUTHOR_JOINS
            + "WHERE c.parent_comment_id = ? AND " + CommentQueries.NOT_HIDDEN
            + " AND " + CommentQueries.CURSOR_PREDICATE
            + "ORDER BY c.created_at DESC, c.id DESC LIMIT ?";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            ParamBinder binder = new ParamBinder(stmt, conn);
            binder.bindUUID(parentId);
            Blocks.bindViewer(binder, viewer);
            binder.bindCursorParams(cursorCreatedAt, cursorId);
            binder.bindInt(limit);
            return mapCommentList(stmt);
        }
    }

    public record CommentHeader(UUID id, UUID postId, String status, boolean deleted) {}

    /** Empty when the comment is missing or hidden from the viewer. */
    public Optional<CommentHeader> findCommentHeader(UUID commentId, UUID viewer) throws SQLException {
        String sql = "SELECT c.id, c.post_id, c.status, c.deleted_at FROM comments c WHERE c.id = ? AND " + CommentQueries.NOT_HIDDEN;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            Blocks.bindViewer(new ParamBinder(stmt, conn).bindUUID(commentId), viewer);
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

    public void ensurePostVisible(UUID postId, UUID viewer) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            PostQueries.ensurePostVisible(conn, postId, viewer);
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
