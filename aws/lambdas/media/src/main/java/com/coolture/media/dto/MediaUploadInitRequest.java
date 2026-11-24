package com.coolture.media.dto;

import com.coolture.common.dto.enums.MediaPurpose;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MediaUploadInitRequest(
        @NotNull MediaPurpose purpose,
        @NotBlank @Size(max = 64) String mimeType,
        @NotNull @Positive Long sizeBytes,
        @NotBlank @Size(max = 255) String fileName,
        /** Optional, in pixels, read from the image before upload. Lets a post's frame take the photo's shape. */
        @Positive @Max(MAX_SIDE) Integer width,
        @Positive @Max(MAX_SIDE) Integer height
) {
    /** Far above any real photo, so only a nonsense value is refused. */
    public static final int MAX_SIDE = 100_000;
}
