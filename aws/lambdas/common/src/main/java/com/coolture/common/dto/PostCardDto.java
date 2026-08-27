package com.coolture.common.dto;

import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.common.dto.enums.ReactionType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostCardDto(
    UUID id,
    UserSummaryDto author,
    EventLocationDto location,
    String title,
    String description,
    String eventUrl,
    Instant startsAt,
    Instant endsAt,
    List<String> tags,
    int positiveReactionCount,
    int negativeReactionCount,
    int participantCount,
    int commentsCount,
    PostType type,
    PostStatus status,
    PostVisibility visibility,
    Instant createdAt,
    Instant lastModifiedAt,
    Instant deletedAt,
    MediaResourceDto coverMedia,
    ReactionType myReaction,
    ParticipationType myParticipation
) {}
