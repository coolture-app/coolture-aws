package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostMarkDto(
    UUID id,
    String title,
    String description,
    String coverMediaUrl,
    int positiveReactionCount,
    GeoPointDto coordinates
) {}
