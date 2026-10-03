package com.coolture.services;

import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.dto.CommentCreateRequest;
import com.coolture.dto.CommentUpdateRequest;
import com.coolture.repository.CommentWriteRepository;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

public class CommentWriteService {

    private static final int MAX_DEPTH = 2;

    private final CommentWriteRepository repository;

    public CommentWriteService() {
        this(new CommentWriteRepository());
    }

    CommentWriteService(CommentWriteRepository repository) {
        this.repository = repository;
    }

    public CommentSummaryDto create(UUID postId, UUID callerId, CommentCreateRequest req) throws SQLException {
        repository.ensurePostActive(postId);

        CommentWriteRepository.ExistingComment parent = null;
        if (req.parentCommentId() != null) {
            parent = findActiveOrThrow(req.parentCommentId());
            if (!parent.postId().equals(postId)) {
                throw new SecurityException("Parent comment belongs to a different post");
            }
            if (parent.depth() >= MAX_DEPTH) {
                throw new SecurityException("Maximum comment thread depth exceeded");
            }
        }

        UUID[] ancestors = buildAncestors(parent);
        UUID rootCommentId = parent == null
            ? null
            : (parent.rootCommentId() != null ? parent.rootCommentId() : parent.id());

        return repository.createComment(
            postId, callerId, req.content(), rootCommentId,
            parent == null ? null : parent.id(), ancestors);
    }

    public CommentSummaryDto update(UUID commentId, UUID callerId, CommentUpdateRequest req) throws SQLException {
        CommentWriteRepository.ExistingComment comment = findActiveOrThrow(commentId);
        requireAuthor(comment, callerId);
        return repository.updateComment(commentId, req.content());
    }

    public void softDelete(UUID commentId, UUID callerId) throws SQLException {
        CommentWriteRepository.ExistingComment comment = findActiveOrThrow(commentId);
        requireAuthor(comment, callerId);
        repository.softDeleteComment(commentId, comment.postId(), comment.ancestorIds());
    }

    private CommentWriteRepository.ExistingComment findActiveOrThrow(UUID commentId) throws SQLException {
        CommentWriteRepository.ExistingComment comment = repository.findExistingComment(commentId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Comment with id '" + commentId + "' was not found"));

        if ("DELETED".equals(comment.status()) || comment.deletedAt() != null) {
            throw new IllegalArgumentException(
                "Comment with id '" + commentId + "' was not found");
        }
        return comment;
    }

    private void requireAuthor(CommentWriteRepository.ExistingComment comment, UUID callerId) {
        if (!comment.authorId().equals(callerId)) {
            throw new SecurityException("Only the author can modify this comment");
        }
    }

    private static UUID[] buildAncestors(CommentWriteRepository.ExistingComment parent) {
        if (parent == null) return new UUID[0];
        UUID[] base = parent.ancestorIds() != null ? parent.ancestorIds() : new UUID[0];
        UUID[] result = Arrays.copyOf(base, base.length + 1);
        result[base.length] = parent.id();
        return result;
    }
}
