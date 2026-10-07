package com.coolture.services;

import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.common.pagination.CursorCodec;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.CursorPayload;
import com.coolture.repository.CommentReadRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CommentReadService {

    private final CommentReadRepository repository;

    public CommentReadService() {
        this(new CommentReadRepository());
    }

    CommentReadService(CommentReadRepository repository) {
        this.repository = repository;
    }

    public CursorPage<CommentSummaryDto> list(
            UUID postId, UUID parentCommentId, String cursor, int limit) throws SQLException {
        repository.ensurePostActive(postId);

        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        int effectiveLimit = clampLimit(limit);
        List<CommentSummaryDto> rows = (parentCommentId == null)
            ? repository.findRootCommentsForPost(postId, cursorCreatedAt, cursorId, effectiveLimit + 1)
            : findRepliesValidated(postId, parentCommentId, cursorCreatedAt, cursorId, effectiveLimit + 1);

        return CursorPage.of(rows, effectiveLimit, CommentSummaryDto::id, CommentSummaryDto::createdAt);
    }

    private List<CommentSummaryDto> findRepliesValidated(
            UUID postId, UUID parentId, Instant cursorCreatedAt, UUID cursorId, int limit) throws SQLException {
        CommentReadRepository.CommentHeader parent = repository.findCommentHeader(parentId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Comment with id '" + parentId + "' was not found"));

        if (!parent.postId().equals(postId)) {
            throw new IllegalArgumentException(
                "Comment with id '" + parentId + "' was not found");
        }
        return repository.findRepliesForParent(parentId, cursorCreatedAt, cursorId, limit);
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, 100);
    }
}
