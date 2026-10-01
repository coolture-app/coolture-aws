package com.coolture.common.dto;

import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MediaResourceDto(
    UUID id,
    MediaPurpose purpose,
    String mimeType,
    Long sizeBytes,
    MediaStatus status,
    String url,
    Instant createdAt,
    Instant deletedAt
) {}
