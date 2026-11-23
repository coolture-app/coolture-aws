package com.coolture.dto;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.PostLinkDto;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.validation.PostLimits;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UpdatePostRequest(
    @Size(min = 1, max = 128, message = "Title must be between 1 and 128 characters")
    @Pattern(regexp = "(?s).*\\S.*", message = "Title must not be blank")
    String title,

    @Size(max = 1024, message = "Description must be shorter than 1024 characters")
    @Pattern(regexp = "(?s).*\\S.*", message = "Description must not be blank")
    String description,

    @Size(max = 2048)
    String eventUrl,

    @Size(max = PostLimits.MAX_LINKS, message = "A post has at most " + PostLimits.MAX_LINKS + " links")
    List<@NotNull @Valid PostLinkDto> links,

    Instant startsAt,

    Instant endsAt,

    Boolean openEnded,

    PostType type,

    @Valid
    EventLocationDto location,

    List<UUID> mediaIds,

    UUID coverMediaId
) {}
