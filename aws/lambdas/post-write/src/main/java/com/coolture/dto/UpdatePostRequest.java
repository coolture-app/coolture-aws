package com.coolture.dto;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UpdatePostRequest(
    @Size(min = 1, max = 128, message = "Title must be between 1 and 128 characters")
    String title,

    @Size(max = 1024, message = "Description must be shorter than 1024 characters")
    String description,

    @Size(max = 2048)
    String eventUrl,

    Instant startsAt,

    Instant endsAt,

    @Size(max = 10, message = "Maximum 10 tags allowed")
    List<@Size(max = 32, message = "Tag length cannot exceed 32 characters") String> tags,

    PostType type,

    PostVisibility visibility,

    @Valid
    EventLocationDto location,

    List<UUID> mediaIds,

    UUID coverMediaId
) {}
