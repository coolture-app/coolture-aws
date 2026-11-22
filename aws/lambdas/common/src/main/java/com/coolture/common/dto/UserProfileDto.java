package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The counts, createdAt, interests, kinds and events are null only in a blocker's slim profile, which leaves out the bio
 * too.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserProfileDto(
    UUID id,
    String username,
    String firstName,
    String lastName,
    MediaResourceDto avatar,
    Integer followersCount,
    Integer followingCount,
    String bio,
    Instant createdAt,
    boolean isFollowing,
    boolean isBlocked,
    boolean followsYou,
    List<String> interests,
    /** Kinds of event, in the catalogue's order. */
    List<String> kinds,
    ProfileEventsDto events
) {}
