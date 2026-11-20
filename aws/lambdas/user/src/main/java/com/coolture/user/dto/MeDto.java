package com.coolture.user.dto;

import com.coolture.common.dto.UserProfileDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** The caller's own profile, plus what only the caller needs to know about their account. */
public record MeDto(
        @JsonUnwrapped UserProfileDto profile,
        /** Has a kind of event. Onboarding asks whoever has none, so accounts from before kinds pick them on sign-in. */
        boolean interestsSelected,
        boolean shareStances,
        @JsonInclude(JsonInclude.Include.NON_NULL) HomeDto home
) {}
