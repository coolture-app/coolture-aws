package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostMarkDto(
    UUID id,
    String title,
    Instant startsAt,
    Instant endsAt,
    String coverMediaUrl,
    /** The description's start for the map's preview, whitespace joined, at most `PostReadRepository.EXCERPT_CHARS`. */
    String excerpt,
    int positiveReactionCount,
    int interestedCount,
    int goingCount,
    GeoPointDto coordinates,
    String venueName,
    String city
) {}
