package com.coolture.user.dto;

import java.util.List;

public record InterestCatalogDto(
        int minSelections,
        int maxSelections,
        List<Category> categories
) {
    public record Category(String key, String labelPl, String labelEn, List<Interest> interests) {}

    public record Interest(String key, String labelPl, String labelEn) {}
}
