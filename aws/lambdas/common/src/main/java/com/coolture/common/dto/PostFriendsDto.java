package com.coolture.common.dto;

import java.util.List;

/** Every friend behind a post's {@link FriendStancesDto}, newest stance first. */
public record PostFriendsDto(List<FriendStancesDto.Friend> going, List<FriendStancesDto.Friend> interested) {}
