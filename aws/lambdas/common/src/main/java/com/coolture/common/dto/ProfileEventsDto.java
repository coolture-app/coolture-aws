package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a profile's event tabs hold, so the page leaves out an empty tab without asking for it. The two stance counts
 * are events not ended, and are null unless the viewer is the user or a friend they share their stances with.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileEventsDto(
    int posts,
    int upcomingPosts,
    Integer takesPart,
    Integer interested
) {}
