package com.coolture.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Arrays;
import java.util.Locale;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventLocationDto(
    @Pattern(regexp = "[A-Z]{2}", message = "countryCode must be two letters, such as PL")
    String countryCode,

    @Size(max = 64)
    String venueName,

    @Size(max = 16)
    String buildingNum,

    @Size(max = 128)
    String street,

    @Size(max = 16)
    String postalCode,

    @NotBlank
    @Size(max = 128)
    String city,

    @NotNull
    @Valid
    GeoPointDto coordinates
) {
    public EventLocationDto {
        countryCode = alpha2(countryCode);
    }

    /**
     * A frontend from before 2-letter codes sends `POL` until a deploy finishes, and so does a scraper snapshot saved
     * before them. An unknown 3-letter code stays as it is, and validation rejects it.
     */
    private static String alpha2(String code) {
        if (code == null || code.isBlank()) return null;
        String upper = code.trim().toUpperCase(Locale.ROOT);
        if (upper.length() != 3) return upper;
        return Arrays.stream(Locale.getISOCountries())
            .filter(alpha2 -> Locale.of("", alpha2).getISO3Country().equals(upper))
            .findFirst()
            .orElse(upper);
    }
}
