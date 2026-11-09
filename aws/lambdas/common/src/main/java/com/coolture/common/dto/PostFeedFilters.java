package com.coolture.common.dto;

import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PostFeedFilters(
    String q,
    List<String> tags,
    UUID authorId,
    PostStatus status,
    PostVisibility visibility,
    PostType type,
    Instant startsFrom,
    Instant startsTo,
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
        String q, List<String> tags, UUID authorId, PostStatus status, PostVisibility visibility, PostType type,
        Instant startsFrom, Instant startsTo, Double latitude, Double longitude, Double radiusKm,
        List<String> participationTypes, String reactionType, String cursor, int limit, String sortBy
    ) {
        this(q, tags, authorId, status, visibility, type, startsFrom, startsTo, latitude, longitude, radiusKm,
            participationTypes, reactionType, cursor, limit, sortBy, false);
    }

    /** No filter at all, as the plain home feed. */
    public static PostFeedFilters none() {
        return new PostFeedFilters(null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, 20, null, false);
    }

    /** A chosen date is explicit, so it also turns the past-events window off. */
    public boolean showsAllPastEvents() {
        return includePast || startsFrom != null || startsTo != null;
    }
}
