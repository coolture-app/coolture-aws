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
    int positiveReactionCount,
    GeoPointDto coordinates,
    String venueName,
    String city
) {}
