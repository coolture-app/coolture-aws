package com.coolture.services;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.pagination.CursorCodec;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.CursorPayload;
import com.coolture.repository.PostReadRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PostReadService {

    private static final Set<String> VALID_PARTICIPATION_TYPES = Set.of("INTERESTED", "TAKES_PART", "interested", "takes_part");
    private static final Set<String> VALID_REACTION_TYPES = Set.of("LIKE", "DISLIKE", "like", "dislike");

    private final PostReadRepository repository;

    public PostReadService() {
        this(new PostReadRepository());
    }

    PostReadService(PostReadRepository repository) {
        this.repository = repository;
    }

    public CursorPage<PostCardDto> getRecommendations(UUID callerId, String cursor, int limit) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("Authentication is required for recommendations");
        }

        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        int effectiveLimit = clampLimit(limit);
        List<PostCardDto> rows = repository.findRecommendations(callerId, cursorCreatedAt, cursorId, effectiveLimit + 1);

        return CursorPage.of(rows, effectiveLimit, PostCardDto::id, PostCardDto::createdAt);
    }

    public CursorPage<PostCardDto> getFeed(UUID callerId, PostFeedFilters filters, String cursor, int limit) throws SQLException {
        validateParticipationFilter(filters.participationTypes(), callerId);
        validateReactionFilter(filters.reactionType(), callerId);

        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        int effectiveLimit = clampLimit(limit);
        List<PostCardDto> rows = repository.findFeed(filters, callerId, cursorCreatedAt, cursorId, effectiveLimit + 1);

        return CursorPage.of(rows, effectiveLimit, PostCardDto::id, PostCardDto::createdAt);
    }

    public PostDetailDto getById(UUID postId, UUID callerId) throws SQLException {
        return repository.findById(postId, callerId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
    }

    public List<PostMarkDto> getMapMarks(UUID callerId, PostFeedFilters filters, MapBoundsDto bounds) throws SQLException {
        validateParticipationFilter(filters.participationTypes(), callerId);
        validateReactionFilter(filters.reactionType(), callerId);
        return repository.findPostMarks(filters, bounds, callerId);
    }

    public CursorPage<EventLocationDto> listEventLocations(String cursor, int limit) throws SQLException {
        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        int effectiveLimit = clampLimit(limit);
        List<EventLocationDto> rows = repository.findEventLocations(cursorCreatedAt, cursorId, effectiveLimit + 1);

        return CursorPage.of(rows, effectiveLimit, EventLocationDto::id, EventLocationDto::createdAt);
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
