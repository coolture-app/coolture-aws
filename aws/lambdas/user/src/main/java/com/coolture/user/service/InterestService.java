package com.coolture.user.service;

import com.coolture.user.dto.InterestCatalogDto;
import com.coolture.user.dto.InterestCatalogDto.Category;
import com.coolture.user.dto.InterestCatalogDto.Interest;
import com.coolture.user.dto.InterestCatalogDto.Kind;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.repository.InterestRepository;
import com.coolture.user.repository.InterestRepository.InterestEntity;
import com.coolture.user.repository.InterestRepository.KindEntity;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class InterestService {

    private static final int MIN_KINDS = 1;
    private static final int DEFAULT_MIN_INTERESTS = 0;
    private static final int DEFAULT_MAX_INTERESTS = 6;

    private final InterestRepository repository;
    private final int minInterests;
    private final int maxInterests;

    public InterestService() {
        this(
                new InterestRepository(),
                intFromEnv("INTERESTS_MIN_SELECTIONS", DEFAULT_MIN_INTERESTS),
                intFromEnv("INTERESTS_MAX_SELECTIONS", DEFAULT_MAX_INTERESTS)
        );
    }

    public InterestService(InterestRepository repository, int minInterests, int maxInterests) {
        this.repository = repository;
        this.minInterests = minInterests;
        this.maxInterests = maxInterests;
    }

    public InterestCatalogDto getCatalog() throws SQLException {
        List<Kind> kinds = repository.findKinds().stream()
                .map(kind -> new Kind(kind.key(), kind.labelPl(), kind.labelEn()))
                .toList();
        Map<String, List<Interest>> interestsByCategory = repository.findInterests().stream()
                .filter(InterestEntity::offered)
                .collect(Collectors.groupingBy(
                        InterestEntity::categoryKey,
                        Collectors.mapping(
                                interest -> new Interest(interest.key(), interest.labelPl(), interest.labelEn()),
                                Collectors.toList())));
        List<Category> categories = repository.findCategories().stream()
                .filter(category -> interestsByCategory.containsKey(category.key()))
                .map(category -> new Category(
                        category.key(),
                        category.labelPl(),
                        category.labelEn(),
                        interestsByCategory.get(category.key())))
                .toList();
        return new InterestCatalogDto(MIN_KINDS, minInterests, maxInterests, kinds, categories);
    }

    public UserInterestsDto getSelection(UUID targetId, UUID callerId) throws SQLException {
        if (repository.isBlockedPair(targetId, callerId)) {
            throw new IllegalArgumentException("User " + targetId + " not found");
        }
        List<String> kinds = repository.findSelectedKinds(targetId);
        List<String> interests = repository.findSelectedKeys(targetId);
        // Someone who picked nothing has no rows either, so only then is the user looked up.
        if (kinds.isEmpty() && interests.isEmpty() && !repository.userExists(targetId)) {
            throw new IllegalArgumentException("User " + targetId + " not found");
        }
        return new UserInterestsDto(kinds, interests);
    }

    public UserInterestsDto replaceSelection(UUID targetId, UUID callerId, UserInterestsDto request) throws SQLException {
        requireOwner(targetId, callerId);

        Set<String> knownKinds = repository.findKinds().stream()
                .map(KindEntity::key)
                .collect(Collectors.toSet());
        Set<String> kinds = new LinkedHashSet<>(request.kinds());
        if (!knownKinds.containsAll(kinds)) {
            throw new IllegalArgumentException("Unknown kind");
        }
        if (kinds.size() < MIN_KINDS) {
            throw new IllegalArgumentException("Select at least " + MIN_KINDS + " kind");
        }

        Set<String> selectable = repository.findInterests().stream()
                .filter(InterestEntity::offered)
                .map(InterestEntity::key)
                .collect(Collectors.toSet());
        Set<String> interests = new LinkedHashSet<>(request.interests());
        if (!selectable.containsAll(interests)) {
            throw new IllegalArgumentException("Unknown interest");
        }
        if (interests.size() < minInterests || interests.size() > maxInterests) {
            throw new IllegalArgumentException(
                    "Select between " + minInterests + " and " + maxInterests + " interests");
        }

        repository.replaceSelection(targetId, kinds, interests);
        return new UserInterestsDto(List.copyOf(kinds), List.copyOf(interests));
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
