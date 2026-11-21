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
    /** The page the scraper imported the post from. Not for display: the app shows links. */
    String eventUrl,
    /** Null when the post has none. */
    List<PostLinkDto> links,
    Instant startsAt,
    Instant endsAt,
    /** No end date: endsAt is null and the event never ends (EventTime). */
    boolean openEnded,
    int positiveReactionCount,
    int participantCount,
    int interestedCount,
    int goingCount,
    int commentsCount,
    PostType type,
    PostStatus status,
    /** Kinds of event, best first. Null until derived. */
    List<String> kinds,
    Instant createdAt,
    Instant lastModifiedAt,
    Instant deletedAt,
    MediaResourceDto coverMedia,
    List<PostMediaDto> media,
    ReactionType myReaction,
    ParticipationType myParticipation,
    FriendStancesDto friendStances
) {}
