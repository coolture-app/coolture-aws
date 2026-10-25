package com.coolture.dto;

import com.coolture.common.dto.enums.ParticipationType;
import jakarta.validation.constraints.NotNull;

public record ParticipationRequest(@NotNull ParticipationType type) {}
