package com.coolture.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record MapBoundsDto(
    @Valid @NotNull GeoPointDto leftUpper,
    @Valid @NotNull GeoPointDto rightBottom
) {}
