package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A link under a post's description. With no label, the app shows the link's domain. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostLinkDto(
    @NotBlank(message = "A link needs a url")
    @Size(max = 2048, message = "A link's url must be at most 2048 characters")
    @Pattern(regexp = "^https?://\\S+$", message = "A link's url must start with http:// or https://")
    String url,

    @Size(max = 40, message = "A link's label must be at most 40 characters")
    String label
) {
    /** A blank label is no label. */
    public PostLinkDto {
        label = label == null || label.isBlank() ? null : label.strip();
    }
}
