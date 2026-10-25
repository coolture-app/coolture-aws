package com.coolture.media.dto;

import com.coolture.common.dto.enums.MediaPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MediaUploadInitRequest(
        @NotNull MediaPurpose purpose,
        @NotBlank @Size(max = 64) String mimeType,
        @NotNull @Positive Long sizeBytes,
        @NotBlank @Size(max = 255) String fileName
) {}
