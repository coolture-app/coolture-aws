package com.coolture.services;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.ParticipationRequest;
import com.coolture.dto.ReactionRequest;
import com.coolture.dto.UpdatePostRequest;
import com.coolture.common.messaging.EmbeddingRequestPublisher;
import com.coolture.repository.PostWriteRepository;
import com.coolture.common.validation.PostLimits;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

public class PostWriteService {

    private final PostWriteRepository repository;
    private final EmbeddingRequestPublisher embeddingPublisher;

    public PostWriteService() {
        this(new PostWriteRepository());
    }

    PostWriteService(PostWriteRepository repository) {
        this(repository, new EmbeddingRequestPublisher());
    }

    PostWriteService(PostWriteRepository repository, EmbeddingRequestPublisher embeddingPublisher) {
        this.repository = repository;
        this.embeddingPublisher = embeddingPublisher;
    }

    public PostDetailDto createPost(UUID authorId, CreatePostRequest req) throws SQLException {
        validateTypeLocationInvariant(req.type(), req.location());
        validateDateRange(req.startsAt(), req.endsAt());
        validateMedia(req.mediaIds(), req.coverMediaId());

        requireEventPhotos(repository.validateMediaOwnership(authorId, req.mediaIds()));
        PostDetailDto created = repository.createPost(authorId,
            req.coverMediaId() == null ? req : req.withMediaIds(coverFirst(req.mediaIds(), req.coverMediaId())));
        embeddingPublisher.requestPost(created.id());
        return created;
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
        // Switching to online drops the saved place, so only a place sent with it is wrong.
        EventLocationDto newLocation = req.location() != null || newType == PostType.ONLINE
            ? req.location()
            : existing.location();
        validateTypeLocationInvariant(newType, newLocation);

        Instant newStartsAt = req.startsAt() != null ? req.startsAt() : existing.startsAt();
        Instant newEndsAt = req.endsAt() != null ? req.endsAt() : existing.endsAt();
        validateDateRange(newStartsAt, newEndsAt);

        UpdatePostRequest toSave = req;
        if (req.mediaIds() != null) {
            validateMedia(req.mediaIds(), req.coverMediaId());
            requireEventPhotos(repository.validateMediaOwnership(callerId, req.mediaIds()));
            if (req.coverMediaId() != null) {
                toSave = req.withMediaIds(coverFirst(req.mediaIds(), req.coverMediaId()));
            }
        }

        PostDetailDto updated = repository.updatePost(postId, callerId, existing, toSave);
        if (embeddedContentChanged(existing, updated)) {
            embeddingPublisher.requestPost(updated.id());
        }
        return updated;
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

    public void upsertReaction(UUID postId, UUID userId, ReactionRequest req) throws SQLException {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID is required");
        }
        if (userId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (req == null || req.type() == null) {
            throw new IllegalArgumentException("Reaction type is required");
        }
        repository.upsertReaction(postId, userId, req.type());
    }

    public void deleteReaction(UUID postId, UUID userId) throws SQLException {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID is required");
        }
        if (userId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        repository.deleteReaction(postId, userId);
    }

    public void upsertParticipation(UUID postId, UUID userId, ParticipationRequest req) throws SQLException {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID is required");
        }
        if (userId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (req == null || req.type() == null) {
            throw new IllegalArgumentException("Participation type is required");
        }
        repository.upsertParticipation(postId, userId, req.type());
    }

    public void deleteParticipation(UUID postId, UUID userId) throws SQLException {
        if (postId == null) {
            throw new IllegalArgumentException("Post ID is required");
        }
        if (userId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        repository.deleteParticipation(postId, userId);
    }

    private boolean embeddedContentChanged(PostWriteRepository.ExistingPost existing, PostDetailDto updated) {
        return !Objects.equals(existing.title(), updated.title())
            || !Objects.equals(existing.description(), updated.description())
            || !Objects.equals(normalizeTags(existing.tags()), normalizeTags(updated.tags()));
    }

    private List<String> normalizeTags(List<String> tags) {
        return tags != null ? tags : List.of();
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

    /** A post holds only event photos, which keeps a profile image out of it. */
    private static void requireEventPhotos(Map<UUID, MediaPurpose> purposes) {
        purposes.forEach((id, purpose) -> {
            if (purpose != null && purpose != MediaPurpose.EVENT_MEDIA) {
                throw new IllegalArgumentException("Media " + id + " is a " + purpose + ", not an event photo");
            }
        });
    }

    /** A post's first photo is its cover, so a named cover moves there and the others keep their order. */
    private static List<UUID> coverFirst(List<UUID> mediaIds, UUID coverMediaId) {
        if (mediaIds == null || mediaIds.isEmpty()) return mediaIds;
        return Stream.concat(Stream.of(coverMediaId), mediaIds.stream().filter(id -> !id.equals(coverMediaId))).toList();
    }

    private void validateMedia(List<UUID> mediaIds, UUID coverMediaId) {
        if (mediaIds == null || mediaIds.isEmpty()) return;

        if (mediaIds.size() > PostLimits.MAX_PHOTOS) {
            throw new IllegalArgumentException("A post can have at most " + PostLimits.MAX_PHOTOS + " photos");
        }

        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new IllegalArgumentException("mediaIds must be unique");
        }

        if (coverMediaId != null && !mediaIds.contains(coverMediaId)) {
            throw new IllegalArgumentException("coverMediaId must be present in mediaIds");
        }
    }
}
