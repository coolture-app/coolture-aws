package com.coolture.dto.create;

import com.coolture.dto.EventLocationDto;
import com.coolture.dto.enums.PostType;
import com.coolture.dto.enums.PostVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreatePostRequest(
    @NotBlank(message = "Title is required")
    @Size(min = 1, max = 128, message = "Title must be between 1 and 128 characters")
    String title,

    @NotBlank(message = "Description is required")
    @Size(max = 1024, message = "Description must be shorter than 1024 characters")
    String description,

    @Size(max = 2048)
    String eventUrl,

    @NotNull(message = "startsAt is required")
    @Future(message = "startsAt must be in the future")
    Instant startsAt,

    Instant endsAt,

    @Size(max = 10, message = "Maximum 10 tags allowed")
    List<@Size(max = 32, message = "Tag length cannot exceed 32 characters") String> tags,

    @NotNull(message = "type is required")
    PostType type,

    PostVisibility visibility,

    @Valid
    EventLocationDto location,

    List<UUID> mediaIds,

    UUID coverMediaId
) {}
