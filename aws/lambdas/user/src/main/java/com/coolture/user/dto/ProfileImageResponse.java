package com.coolture.user.dto;

import java.time.Instant;
import java.util.UUID;

public record ProfileImageResponse(
        UUID fullMediaId,
        UUID thumbnailMediaId,
        Instant setAt
) {}
