package com.coolture.services;

import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.pagination.CursorCodec;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.CursorPayload;
import com.coolture.repository.PostReadRepository;
import com.coolture.repository.PostSort;
import com.coolture.repository.PostsCursor;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PostReadService {

    private static final Set<String> VALID_PARTICIPATION_TYPES = Set.of("INTERESTED", "TAKES_PART", "NOT_INTERESTED", "interested", "takes_part", "not_interested");
    private static final Set<String> VALID_REACTION_TYPES = Set.of("LIKE", "like");

    private final PostReadRepository repository;

    public PostReadService() {
        this(new PostReadRepository());
    }

    public PostReadService(PostReadRepository repository) {
        this.repository = repository;
    }

    public CursorPage<PostCardDto> getFeed(UUID callerId, PostFeedFilters filters, String cursor, int limit) throws SQLException {
        validateParticipationFilter(filters.participationTypes(), callerId);
        validateReactionFilter(filters.reactionType(), callerId);

        PostSort sort = PostSort.parse(filters.sortBy());
        if (sort.needsPosition() && (filters.latitude() == null || filters.longitude() == null)) {
            throw new IllegalArgumentException("sortBy NEAREST needs latitude and longitude");
        }
        if (sort.needsStances() && (filters.participationTypes() == null || filters.participationTypes().isEmpty())) {
            throw new IllegalArgumentException("sortBy STANCE_NEWEST needs participationTypes");
        }
        PostsCursor keyset = PostsCursor.decode(cursor, sort).orElse(null);

        int effectiveLimit = clampLimit(limit);
        List<PostCardDto> rows = repository.findFeed(filters, sort, callerId, keyset, effectiveLimit + 1);

        return CursorPage.of(rows, effectiveLimit, card -> PostsCursor.after(sort, card));
    }

    public PostDetailDto getById(UUID postId, UUID callerId) throws SQLException {
        return repository.findById(postId, callerId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
    }

    public List<PostMarkDto> getMapMarks(UUID callerId, PostFeedFilters filters, MapBoundsDto bounds, boolean excludeMine) throws SQLException {
        validateParticipationFilter(filters.participationTypes(), callerId);
        validateReactionFilter(filters.reactionType(), callerId);
        return repository.findPostMarks(filters, bounds, callerId, excludeMine);
    }


    private void validateParticipationFilter(List<String> participationTypes, UUID callerId) {
        if (participationTypes == null || participationTypes.isEmpty()) return;
        if (callerId == null) {
            throw new SecurityException("Authentication is required to filter by participation type");
        }
        for (String p : participationTypes) {
            if (!VALID_PARTICIPATION_TYPES.contains(p)) {
                throw new IllegalArgumentException("Unknown participation type: " + p);
            }
        }
    }

    private void validateReactionFilter(String reactionType, UUID callerId) {
        if (reactionType == null || reactionType.isBlank()) return;
        if (callerId == null) {
            throw new SecurityException("Authentication is required to filter by reaction type");
        }
        if (!VALID_REACTION_TYPES.contains(reactionType)) {
            throw new IllegalArgumentException("Unknown reaction type: " + reactionType);
        }
    }

    private int clampLimit(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, 100);
    }
}
