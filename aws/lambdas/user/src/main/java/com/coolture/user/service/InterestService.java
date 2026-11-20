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
    private static final int MIN_INTERESTS = 0;
    private static final int MAX_INTERESTS = 6;

    private final InterestRepository repository;

    public InterestService() {
        this(new InterestRepository());
    }

    public InterestService(InterestRepository repository) {
        this.repository = repository;
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
        return new InterestCatalogDto(MIN_KINDS, MIN_INTERESTS, MAX_INTERESTS, kinds, categories);
    }

    public UserInterestsDto getSelection(UUID targetId, UUID callerId) throws SQLException {
        if (repository.isBlockedPair(targetId, callerId)) {
            throw new IllegalArgumentException("User " + targetId + " not found");
        }
        UserInterestsDto picks = repository.findPicks(targetId);
        // Someone who picked nothing has no rows either, so only then is the user looked up.
        if (picks.kinds().isEmpty() && picks.interests().isEmpty() && !repository.userExists(targetId)) {
            throw new IllegalArgumentException("User " + targetId + " not found");
        }
        return picks;
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
        if (interests.size() < MIN_INTERESTS || interests.size() > MAX_INTERESTS) {
            throw new IllegalArgumentException(
                    "Select between " + MIN_INTERESTS + " and " + MAX_INTERESTS + " interests");
        }

        repository.replaceSelection(targetId, kinds, interests);
        return new UserInterestsDto(List.copyOf(kinds), List.copyOf(interests));
    }

    private static void requireOwner(UUID targetId, UUID callerId) {
        if (!targetId.equals(callerId)) {
            throw new SecurityException("You can only access your own interests");
        }
    }
}
