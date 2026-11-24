package com.coolture.user.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record UserInterestsDto(
        @NotNull List<@NotNull String> kinds,
        @NotNull List<@NotNull String> interests
) {}
