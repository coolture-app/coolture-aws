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
public record PostCardDto(
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
    Instant createdAt,
    Instant lastModifiedAt,
    Instant deletedAt,
    MediaResourceDto coverMedia,
    ReactionType myReaction,
    ParticipationType myParticipation,
    /** Every photo, cover first, at most PostLimits.MAX_PHOTOS. Null when the post has none. */
    List<MediaResourceDto> photos,
    FriendStancesDto friendStances
) {
    public PostCardDto withPhotos(List<MediaResourceDto> newPhotos) {
        return new PostCardDto(id, author, location, title, description, eventUrl, links, startsAt, endsAt, openEnded,
            positiveReactionCount, participantCount, interestedCount, goingCount, commentsCount, type, status,
            createdAt, lastModifiedAt, deletedAt, coverMedia, myReaction, myParticipation,
            newPhotos == null || newPhotos.isEmpty() ? null : newPhotos, friendStances);
    }

    public PostCardDto withFriendStances(FriendStancesDto stances) {
        return new PostCardDto(id, author, location, title, description, eventUrl, links, startsAt, endsAt, openEnded,
            positiveReactionCount, participantCount, interestedCount, goingCount, commentsCount, type, status,
            createdAt, lastModifiedAt, deletedAt, coverMedia, myReaction, myParticipation, photos, stances);
    }
}
