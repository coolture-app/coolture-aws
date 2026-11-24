package com.coolture.user.dto;

import java.util.List;

/** Onboarding's choices: kinds of event, then optional interests (topics) grouped by category. */
public record InterestCatalogDto(
        int minKinds,
        int minInterests,
        int maxInterests,
        List<Kind> kinds,
        List<Category> categories
) {
    public record Kind(String key, String labelPl, String labelEn) {}

    public record Category(String key, String labelPl, String labelEn, List<Interest> interests) {}

    public record Interest(String key, String labelPl, String labelEn) {}
}
