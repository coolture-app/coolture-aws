package com.coolture.dto;

import com.coolture.common.dto.enums.ReactionType;
import jakarta.validation.constraints.NotNull;

public record ReactionRequest(@NotNull ReactionType type) {}
