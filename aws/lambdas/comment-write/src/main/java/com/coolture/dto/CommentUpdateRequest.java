package com.coolture.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentUpdateRequest(
    @NotBlank(message = "Content is required")
    @Size(max = 512, message = "Content must be shorter than 512 characters")
    String content
) {}
