package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostMediaDto(
    MediaResourceDto media,
    int position,
    boolean isCover
) {}
