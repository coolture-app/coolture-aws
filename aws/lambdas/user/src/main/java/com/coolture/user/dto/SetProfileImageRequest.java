package com.coolture.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SetProfileImageRequest(
        @NotNull UUID fullMediaId,
        @NotNull UUID thumbnailMediaId
) {}
