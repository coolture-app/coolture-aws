package com.coolture.services;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.UpdatePostRequest;
import com.coolture.repository.PostWriteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostWriteServiceTest {

    private PostWriteRepository repository;
    private PostWriteService service;

    @BeforeEach
    void setUp() {
        repository = mock(PostWriteRepository.class);
        service = new PostWriteService(repository);
    }

    @Test
    void createPost_whenOfflineWithoutLocation_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest(
            "Concert", "Description", "https://example.com",
            Instant.now(), Instant.now().plusSeconds(3600),
            List.of("music"), PostType.OFFLINE, PostVisibility.PUBLIC,
            null, null, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("OFFLINE posts require a location"));
    }

    @Test
    void createPost_whenOnlineWithLocation_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        EventLocationDto loc = new EventLocationDto(
            null, "POL", "Venue", "1", "Street", "00-001", "City",
            new GeoPointDto(50.0, 20.0), null
        );
        CreatePostRequest req = new CreatePostRequest(
            "Stream", "Description", "https://example.com",
            Instant.now(), Instant.now().plusSeconds(3600),
            List.of("tech"), PostType.ONLINE, PostVisibility.PUBLIC,
            loc, null, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("ONLINE posts cannot have a location"));
    }

    @Test
    void createPost_whenEndsAtBeforeStartsAt_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        Instant startsAt = Instant.now();
        Instant endsAt = startsAt.minusSeconds(3600);

        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com",
            startsAt, endsAt,
            List.of("talk"), PostType.ONLINE, PostVisibility.PUBLIC,
            null, null, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("endsAt must be strictly after startsAt"));
    }

    @Test
    void createPost_whenDuplicateMediaIds_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();

        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com",
            Instant.now(), Instant.now().plusSeconds(3600),
            List.of("talk"), PostType.ONLINE, PostVisibility.PUBLIC,
            null, List.of(mediaId, mediaId), null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("mediaIds must be unique"));
    }

    @Test
    void createPost_whenCoverMediaIdNotPresentInMediaIds_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        UUID media1 = UUID.randomUUID();
        UUID coverMedia = UUID.randomUUID();

        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com",
            Instant.now(), Instant.now().plusSeconds(3600),
            List.of("talk"), PostType.ONLINE, PostVisibility.PUBLIC,
            null, List.of(media1), coverMedia
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("coverMediaId must be present in mediaIds"));
    }

    @Test
    void updatePost_whenNotAuthor_throwsSecurityException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();

        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url",
            Instant.now(), null, List.of(), PostType.ONLINE,
            PostStatus.ACTIVE, PostVisibility.PUBLIC, Instant.now(), null, null, null
        );
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            "New Title", null, null, null, null, null, null, null, null, null, null
        );

        SecurityException ex = assertThrows(SecurityException.class, () -> service.updatePost(postId, otherUser, req));
        assertTrue(ex.getMessage().contains("Only the author can modify this post"));
    }

    @Test
    void updatePost_whenPostDeleted_throwsIllegalArgumentException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url",
            Instant.now(), null, List.of(), PostType.ONLINE,
            PostStatus.DELETED, PostVisibility.PUBLIC, Instant.now(), null, Instant.now(), null
        );
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            "New Title", null, null, null, null, null, null, null, null, null, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.updatePost(postId, authorId, req));
        assertTrue(ex.getMessage().contains("Post not found"));
    }

    @Test
    void deletePost_whenNotAuthor_throwsSecurityException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();

        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url",
            Instant.now(), null, List.of(), PostType.ONLINE,
            PostStatus.ACTIVE, PostVisibility.PUBLIC, Instant.now(), null, null, null
        );
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        assertThrows(SecurityException.class, () -> service.deletePost(postId, callerId));
        verify(repository, never()).softDeletePost(any());
    }

    @Test
    void deletePost_whenAuthor_callsSoftDelete() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url",
            Instant.now(), null, List.of(), PostType.ONLINE,
            PostStatus.ACTIVE, PostVisibility.PUBLIC, Instant.now(), null, null, null
        );
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        service.deletePost(postId, authorId);

        verify(repository).softDeletePost(postId);
    }
}
