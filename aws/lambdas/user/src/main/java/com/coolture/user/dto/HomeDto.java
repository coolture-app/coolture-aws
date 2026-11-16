package com.coolture.user.dto;

import com.coolture.common.dto.GeoPointDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Where someone lives: a city, town, village or district, never an address. Only its owner sees it. */
public record HomeDto(
        @NotBlank @Size(max = 128) String name,
        @NotNull @Valid GeoPointDto coordinates
) {}
