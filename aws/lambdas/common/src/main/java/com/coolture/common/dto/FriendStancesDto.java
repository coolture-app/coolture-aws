package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/** The viewer's friends who marked a post Interesuje mnie or Biorę udział. A stance nobody took is left out. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FriendStancesDto(Group going, Group interested) {

    /** All friends with this stance, and the newest few by name. */
    public record Group(int count, List<Friend> people) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Friend(UUID id, String username, String firstName, String lastName, String avatarUrl) {}
}
