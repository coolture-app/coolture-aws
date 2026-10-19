package com.coolture.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 32) String username,
        @Size(max = 128) String firstName,
        @Size(max = 128) String lastName,
        @Size(max = 512) String bio
) {}
