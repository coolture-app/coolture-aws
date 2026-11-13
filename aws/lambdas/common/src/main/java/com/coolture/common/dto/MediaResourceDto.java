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
    Instant deletedAt,
    /** Pixels. Null when unknown: media from before V10, or not an image. */
    Integer width,
    Integer height
) {
    /** Without a size, for media whose size isn't read, such as avatars. */
    public MediaResourceDto(UUID id, MediaPurpose purpose, String mimeType, Long sizeBytes, MediaStatus status,
                            String url, Instant createdAt, Instant deletedAt) {
        this(id, purpose, mimeType, sizeBytes, status, url, createdAt, deletedAt, null, null);
    }

    public MediaResourceDto withUrl(String newUrl) {
        return new MediaResourceDto(id, purpose, mimeType, sizeBytes, status, newUrl, createdAt, deletedAt, width, height);
    }
}
