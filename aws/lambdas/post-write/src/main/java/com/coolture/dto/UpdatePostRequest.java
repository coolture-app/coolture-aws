package com.coolture.dto;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.enums.PostType;
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

    PostType type,

    @Valid
    EventLocationDto location,

    List<UUID> mediaIds,

    UUID coverMediaId
) {
    public UpdatePostRequest withMediaIds(List<UUID> ordered) {
        return new UpdatePostRequest(title, description, eventUrl, startsAt, endsAt, type, location,
            ordered, coverMediaId);
    }
}
