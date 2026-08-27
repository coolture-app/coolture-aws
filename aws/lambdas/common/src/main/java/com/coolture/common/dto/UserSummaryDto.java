package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserSummaryDto(
    UUID id,
    String username,
    String firstName,
    String lastName,
    MediaResourceDto avatar,
    Instant createdAt,
    int followersCount,
    int followingCount
) {}
