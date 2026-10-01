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
    String sortBy
) {}
