package com.coolture.common.dto;

import com.coolture.common.dto.enums.CommentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommentSummaryDto(
    UUID id,
    UUID postId,
    UUID rootCommentId,
    UUID parentCommentId,
    UserSummaryDto author,
    String content,
    int depth,
    int repliesCount,
    Instant createdAt,
    Instant lastEditedAt,
    Instant deletedAt,
    CommentStatus status
) {}
