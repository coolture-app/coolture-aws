package com.coolture.user.dto;

import com.coolture.common.dto.UserProfileDto;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** The caller's own profile, plus what only the caller needs to know about their account. */
public record MeDto(
        @JsonUnwrapped UserProfileDto profile,
        boolean interestsSelected
) {}
