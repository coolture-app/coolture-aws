package com.coolture.common.dto;

import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostFeedFilters(
    String q,
    List<String> tags,
    UUID authorId,
    PostStatus status,
    /** Only posts by people the viewer follows, or that a friend is interested in or goes to. Null means off. */
    Boolean followed,
    PostType type,
    /** Only events running at some moment from this time on (EventTime.OVERLAPS). */
    Instant from,
    /** Only events running at some moment up to this time. */
    Instant to,
    Double latitude,
    Double longitude,
    Double radiusKm,
    List<String> participationTypes,
    String reactionType,
    String cursor,
    int limit,
    String sortBy,
    boolean includePast
) {

    public PostFeedFilters(
        String q, List<String> tags, UUID authorId, PostStatus status, Boolean followed, PostType type,
        Instant from, Instant to, Double latitude, Double longitude, Double radiusKm,
        List<String> participationTypes, String reactionType, String cursor, int limit, String sortBy
    ) {
        this(q, tags, authorId, status, followed, type, from, to, latitude, longitude, radiusKm,
            participationTypes, reactionType, cursor, limit, sortBy, false);
    }

    /** No filter at all, as the plain home feed. */
    public static PostFeedFilters none() {
        return new PostFeedFilters(null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, 20, null, false);
    }

    /** A chosen date is explicit, so it also turns the past-events window off. */
    public boolean showsAllPastEvents() {
        return includePast || from != null || to != null;
    }
}
