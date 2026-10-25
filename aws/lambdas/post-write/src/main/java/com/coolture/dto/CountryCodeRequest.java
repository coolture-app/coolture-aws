package com.coolture.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CountryCodeRequest(
    @NotBlank
    @Size(min = 3, max = 3)
    @Pattern(regexp = "[A-Z]{3}", message = "Must be exactly 3 uppercase letters (ISO 3166-1 alpha-3)")
    String code
) {}
