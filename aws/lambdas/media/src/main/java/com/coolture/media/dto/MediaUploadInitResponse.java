package com.coolture.media.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record MediaUploadInitResponse(
        UUID mediaId,
        String objectKey,
        String uploadUrl,
        String httpMethod,
        Instant expiresAt,
        Map<String, String> requiredHeaders
) {}
