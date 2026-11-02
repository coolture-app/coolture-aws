package com.coolture.user.service;

import com.coolture.common.messaging.EmbeddingRequestPublisher;
import com.coolture.user.dto.InterestCatalogDto;
import com.coolture.user.dto.InterestCatalogDto.Category;
import com.coolture.user.dto.InterestCatalogDto.Interest;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.repository.InterestRepository;
import com.coolture.user.repository.InterestRepository.InterestEntity;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class InterestService {

    private static final int DEFAULT_MIN_SELECTIONS = 3;
    private static final int DEFAULT_MAX_SELECTIONS = 6;

    private final InterestRepository repository;
    private final EmbeddingRequestPublisher embeddingRequests;
    private final int minSelections;
    private final int maxSelections;

    public InterestService() {
        this(
                new InterestRepository(),
                new EmbeddingRequestPublisher(),
                intFromEnv("INTERESTS_MIN_SELECTIONS", DEFAULT_MIN_SELECTIONS),
                intFromEnv("INTERESTS_MAX_SELECTIONS", DEFAULT_MAX_SELECTIONS)
        );
    }

    public InterestService(InterestRepository repository, EmbeddingRequestPublisher embeddingRequests,
                           int minSelections, int maxSelections) {
        this.repository = repository;
        this.embeddingRequests = embeddingRequests;
        this.minSelections = minSelections;
        this.maxSelections = maxSelections;
    }

    public InterestCatalogDto getCatalog() throws SQLException {
        Map<String, List<Interest>> interestsByCategory = repository.findInterests().stream()
                .collect(Collectors.groupingBy(
                        InterestEntity::categoryKey,
                        Collectors.mapping(
                                interest -> new Interest(interest.key(), interest.labelPl(), interest.labelEn()),
                                Collectors.toList())));
        List<Category> categories = repository.findCategories().stream()
                .map(category -> new Category(
                        category.key(),
                        category.labelPl(),
                        category.labelEn(),
                        interestsByCategory.getOrDefault(category.key(), List.of())))
                .toList();
        return new InterestCatalogDto(minSelections, maxSelections, categories);
    }

    public UserInterestsDto getSelection(UUID targetId, UUID callerId) throws SQLException {
        requireOwner(targetId, callerId);
        return new UserInterestsDto(repository.findSelectedKeys(targetId));
    }

    public boolean hasSelection(UUID userId) throws SQLException {
        return repository.hasSelection(userId);
    }

    public UserInterestsDto replaceSelection(UUID targetId, UUID callerId, UserInterestsDto request) throws SQLException {
        requireOwner(targetId, callerId);

        Set<String> known = repository.findInterests().stream()
                .map(InterestEntity::key)
                .collect(Collectors.toSet());
        Set<String> keys = new LinkedHashSet<>(request.interests());
        if (!known.containsAll(keys)) {
            throw new IllegalArgumentException("Unknown interest");
        }
        if (keys.size() < minSelections || keys.size() > maxSelections) {
            throw new IllegalArgumentException(
                    "Select between " + minSelections + " and " + maxSelections + " interests");
        }

        repository.replaceSelection(targetId, keys);
        embeddingRequests.requestUser(targetId);
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
