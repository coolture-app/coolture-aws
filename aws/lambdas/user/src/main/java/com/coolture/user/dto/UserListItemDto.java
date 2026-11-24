package com.coolture.user.dto;

import com.coolture.common.dto.UserSummaryDto;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * A person in the users API's lists. Post and comment authors use UserSummaryDto alone, without the two follow flags.
 */
public record UserListItemDto(
        @JsonUnwrapped UserSummaryDto summary,
        boolean isFollowing,
        boolean followsYou
) {}
