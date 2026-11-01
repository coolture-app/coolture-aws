package com.coolture.user.service;

import com.coolture.user.dto.InterestCatalogDto;
import com.coolture.user.dto.InterestCatalogDto.Category;
import com.coolture.user.dto.InterestCatalogDto.Interest;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.repository.InterestRepository;
import com.coolture.user.repository.InterestRepository.InterestEntity;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class InterestService {

    private static final int DEFAULT_MIN_SELECTIONS = 3;
    private static final int DEFAULT_MAX_SELECTIONS = 6;

    private final InterestRepository repository;
    private final int minSelections;
    private final int maxSelections;

    public InterestService() {
        this(
                new InterestRepository(),
                intFromEnv("INTERESTS_MIN_SELECTIONS", DEFAULT_MIN_SELECTIONS),
                intFromEnv("INTERESTS_MAX_SELECTIONS", DEFAULT_MAX_SELECTIONS)
        );
    }

    public InterestService(InterestRepository repository, int minSelections, int maxSelections) {
        this.repository = repository;
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
    }

    public InterestCatalogDto getCatalog() throws SQLException {
        List<InterestEntity> interests = repository.findAll();
        List<Category> categories = new ArrayList<>();
        for (InterestEntity category : interests) {
            if (category.isCategory()) {
                List<Interest> children = interests.stream()
                        .filter(interest -> category.key().equals(interest.parentKey()))
                        .map(interest -> new Interest(interest.key(), interest.labelPl(), interest.labelEn()))
                        .toList();
                categories.add(new Category(category.key(), category.labelPl(), category.labelEn(), children));
            }
        }
        return new InterestCatalogDto(minSelections, maxSelections, categories);
    }

    public UserInterestsDto getSelection(UUID targetId, UUID callerId) throws SQLException {
        requireOwner(targetId, callerId);
        return new UserInterestsDto(repository.findSelectedKeys(targetId));
    }

    public UserInterestsDto replaceSelection(UUID targetId, UUID callerId, UserInterestsDto request) throws SQLException {
        requireOwner(targetId, callerId);

        Map<String, InterestEntity> known = repository.findAll().stream()
                .collect(Collectors.toMap(InterestEntity::key, Function.identity()));
        Set<String> keys = new LinkedHashSet<>(request.interests());

        long selected = 0;
        for (String key : keys) {
            InterestEntity interest = known.get(key);
            if (interest == null) {
                throw new IllegalArgumentException("Unknown interest");
            }
            if (!interest.isCategory()) {
                selected++;
            }
        }
        if (selected < minSelections || selected > maxSelections) {
            throw new IllegalArgumentException(
                    "Select between " + minSelections + " and " + maxSelections + " interests");
        }

        repository.replaceSelection(targetId, keys);
        return new UserInterestsDto(List.copyOf(keys));
    }

    private static void requireOwner(UUID targetId, UUID callerId) {
        if (!targetId.equals(callerId)) {
            throw new SecurityException("You can only access your own interests");
        }
    }

    private static int intFromEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }
}
