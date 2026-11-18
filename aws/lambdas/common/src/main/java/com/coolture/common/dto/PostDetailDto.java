package com.coolture.common.dto;

import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.ReactionType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostDetailDto(
    UUID id,
    UserSummaryDto author,
    EventLocationDto location,
    String title,
    String description,
    String eventUrl,
    Instant startsAt,
    Instant endsAt,
    /** No end date: endsAt is null and the event never ends (EventTime). */
    boolean openEnded,
    List<String> tags,
    int positiveReactionCount,
    int participantCount,
    int interestedCount,
    int goingCount,
    int commentsCount,
    PostType type,
    PostStatus status,
    Instant createdAt,
    Instant lastModifiedAt,
    Instant deletedAt,
    MediaResourceDto coverMedia,
    List<PostMediaDto> media,
    ReactionType myReaction,
    ParticipationType myParticipation,
    FriendStancesDto friendStances
) {}
