package com.coolture.common.dto;

import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.ReactionType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostMarkDto(
    UUID id,
    String title,
    Instant startsAt,
    Instant endsAt,
    /** No end date: endsAt is null and the event never ends (EventTime). */
    boolean openEnded,
    String coverMediaUrl,
    /** The description's start for the map's preview, whitespace joined, at most `PostReadRepository.EXCERPT_CHARS`. */
    String excerpt,
    int positiveReactionCount,
    int interestedCount,
    int goingCount,
    GeoPointDto coordinates,
    String venueName,
    String city,
    /** The viewer's own, so the preview's buttons show it with no request. Null for a guest. */
    ReactionType myReaction,
    ParticipationType myParticipation,
    FriendStancesDto friendStances
) {
    public PostMarkDto withFriendStances(FriendStancesDto stances) {
        return new PostMarkDto(id, title, startsAt, endsAt, openEnded, coverMediaUrl, excerpt, positiveReactionCount, interestedCount,
            goingCount, coordinates, venueName, city, myReaction, myParticipation, stances);
    }
}
