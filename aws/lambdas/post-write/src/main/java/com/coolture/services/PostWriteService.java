package com.coolture.services;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.UpdatePostRequest;
import com.coolture.repository.PostWriteRepository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public class PostWriteService {

    private final PostWriteRepository repository = new PostWriteRepository();

    public PostDetailDto createPost(UUID authorId, CreatePostRequest req) throws SQLException {
        validateTypeLocationInvariant(req.type(), req.location());
        validateDateRange(req.startsAt(), req.endsAt());
        validateMedia(req.mediaIds(), req.coverMediaId());

        repository.validateMediaOwnership(authorId, req.mediaIds());
        return repository.createPost(authorId, req);
    }

    public PostDetailDto updatePost(UUID postId, UUID callerId, UpdatePostRequest req) throws SQLException {
        PostWriteRepository.ExistingPost existing = repository.findById(postId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        if (existing.status() == PostStatus.DELETED || existing.deletedAt() != null) {
            throw new IllegalArgumentException("Post not found: " + postId);
        }

        if (!existing.authorId().equals(callerId)) {
            throw new SecurityException("Only the author can modify this post");
        }

        PostType newType = req.type() != null ? req.type() : existing.type();
        boolean locationTouched = req.location() != null;
        EventLocationDto newLocDto = locationTouched ? req.location() : existing.location();
        validateTypeLocationInvariant(newType, newLocDto);

        Instant newStartsAt = req.startsAt() != null ? req.startsAt() : existing.startsAt();
        Instant newEndsAt = req.endsAt() != null ? req.endsAt() : existing.endsAt();
        validateDateRange(newStartsAt, newEndsAt);

        if (req.mediaIds() != null) {
            validateMedia(req.mediaIds(), req.coverMediaId());
            repository.validateMediaOwnership(callerId, req.mediaIds());
        }

        return repository.updatePost(postId, callerId, existing, req);
    }

    public void deletePost(UUID postId, UUID callerId) throws SQLException {
        PostWriteRepository.ExistingPost existing = repository.findById(postId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        if (existing.status() == PostStatus.DELETED || existing.deletedAt() != null) {
            throw new IllegalArgumentException("Post not found: " + postId);
        }

        if (!existing.authorId().equals(callerId)) {
            throw new SecurityException("Only the author can delete this post");
        }

        repository.softDeletePost(postId);
    }

    public EventLocationDto createLocation(EventLocationDto req) throws SQLException {
        if (req == null) {
            throw new IllegalArgumentException("Location payload is required");
        }
        return repository.createLocation(req);
    }

    public EventLocationDto updateLocation(UUID locationId, EventLocationDto req) throws SQLException {
        if (locationId == null || req == null) {
            throw new IllegalArgumentException("Location ID and payload are required");
        }
        return repository.updateLocation(locationId, req);
    }

    public void deleteLocation(UUID locationId) throws SQLException {
        if (locationId == null) {
            throw new IllegalArgumentException("Location ID is required");
        }
        repository.deleteLocation(locationId);
    }

    private void validateTypeLocationInvariant(PostType type, EventLocationDto location) {
        if (PostType.OFFLINE == type && location == null) {
            throw new IllegalArgumentException("OFFLINE posts require a location");
        }
        if (PostType.ONLINE == type && location != null) {
            throw new IllegalArgumentException("ONLINE posts cannot have a location");
        }
    }

    private void validateDateRange(Instant startsAt, Instant endsAt) {
        if (endsAt != null && startsAt != null && !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("endsAt must be strictly after startsAt");
        }
    }

    private void validateMedia(List<UUID> mediaIds, UUID coverMediaId) {
        if (mediaIds == null || mediaIds.isEmpty()) return;

        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new IllegalArgumentException("mediaIds must be unique");
        }

        if (coverMediaId != null && !mediaIds.contains(coverMediaId)) {
            throw new IllegalArgumentException("coverMediaId must be present in mediaIds");
        }
    }
}
