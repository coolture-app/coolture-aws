package com.coolture.services;

import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostFriendsDto;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.PaginationMeta;
import com.coolture.repository.PostReadRepository;
import com.coolture.repository.PostSort;
import com.coolture.repository.PostsCursor;
import com.coolture.repository.Upcoming;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class PostReadService {

    private static final Set<String> VALID_PARTICIPATION_TYPES = Set.of("INTERESTED", "TAKES_PART", "NOT_INTERESTED");
    private static final Set<String> SHARED_PARTICIPATION_TYPES = Set.of("INTERESTED", "TAKES_PART");
    private static final Set<String> VALID_REACTION_TYPES = Set.of("LIKE");

    private final PostReadRepository repository;

    public PostReadService() {
        this(new PostReadRepository());
    }

    public PostReadService(PostReadRepository repository) {
        this.repository = repository;
    }

    public CursorPage<PostCardDto> getFeed(UUID callerId, PostFeedFilters filters) throws SQLException {
        validateParticipationFilter(filters.participationTypes(), callerId);
        validateReactionFilter(filters.reactionType(), callerId);
        validateParticipant(filters, callerId);
        PostSort sort = PostSort.parse(filters.sortBy());
        validateSort(sort, filters, callerId);
        PostsCursor keyset = PostsCursor.decode(filters.cursor(), sort).orElse(null);

        int effectiveLimit = clampLimit(filters.limit());
        if (sort == PostSort.UPCOMING) {
            Upcoming.Position at = keyset != null
                ? keyset.upcoming()
                : Upcoming.Position.start(Instant.now().truncatedTo(ChronoUnit.MILLIS));
            Upcoming.Page page = repository.findUpcoming(filters, callerId, at, effectiveLimit);
            String next = page.hasMore() ? PostsCursor.upcoming(page.next()) : null;
            return new CursorPage<>(page.items(), new PaginationMeta(effectiveLimit, page.hasMore(), next));
        }
        List<PostCardDto> rows = repository.findFeed(filters, sort, callerId, keyset, effectiveLimit + 1);

        return CursorPage.of(rows, effectiveLimit, card -> PostsCursor.after(sort, card));
    }

    public PostDetailDto getById(UUID postId, UUID callerId) throws SQLException {
        return repository.findById(postId, callerId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
    }

    public PostFriendsDto getFriends(UUID postId, UUID callerId) throws SQLException {
        return repository.findFriends(postId, callerId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
    }

    public List<PostMarkDto> getMapMarks(
            UUID callerId, PostFeedFilters filters, MapBoundsDto bounds, boolean excludeMine) throws SQLException {
        validateParticipationFilter(filters.participationTypes(), callerId);
        validateReactionFilter(filters.reactionType(), callerId);
        validateParticipant(filters, callerId);
        return repository.findPostMarks(filters, bounds, callerId, excludeMine);
    }

    /**
     * Of someone else, only Interesuje mnie and Biorę udział can be asked for. Whether the viewer may see them is the
     * query's rule, as on cards: a refused friend gets an empty list.
     */
    private static void validateParticipant(PostFeedFilters filters, UUID callerId) {
        if (!someoneElse(filters, callerId)) {
            return;
        }
        if (callerId == null) {
            throw new SecurityException("Authentication is required to filter by participantId");
        }
        if (filters.participationTypes() == null || filters.participationTypes().isEmpty()
            || !SHARED_PARTICIPATION_TYPES.containsAll(filters.participationTypes())) {
            throw new IllegalArgumentException("participantId needs participationTypes of INTERESTED or TAKES_PART");
        }
        if (filters.reactionType() != null) {
            throw new IllegalArgumentException("participantId can't go with reactionType");
        }
    }

    private static void validateSort(PostSort sort, PostFeedFilters filters, UUID callerId) {
        if (sort.needsPosition() && (filters.latitude() == null || filters.longitude() == null)) {
            throw new IllegalArgumentException("sortBy NEAREST needs latitude and longitude");
        }
        if (sort.needsStances() && someoneElse(filters, callerId)) {
            throw new IllegalArgumentException("participantId can't go with STANCE_NEWEST");
        }
        if (sort.needsStances() && (filters.participationTypes() == null || filters.participationTypes().isEmpty())) {
            throw new IllegalArgumentException("sortBy STANCE_NEWEST needs participationTypes");
        }
    }

    private static boolean someoneElse(PostFeedFilters filters, UUID callerId) {
        return filters.participantId() != null && !filters.participantId().equals(callerId);
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
