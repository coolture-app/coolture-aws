package com.coolture.services;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.UpdatePostRequest;
import com.coolture.messaging.PostEmbeddingPublisher;
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
    private PostEmbeddingPublisher embeddingPublisher;
    private PostWriteService service;

    @BeforeEach
    void setUp() {
        repository = mock(PostWriteRepository.class);
        embeddingPublisher = mock(PostEmbeddingPublisher.class);
        service = new PostWriteService(repository, embeddingPublisher);
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

    @Test
    void upsertReaction_callsRepository() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        com.coolture.dto.ReactionRequest req = new com.coolture.dto.ReactionRequest(com.coolture.common.dto.enums.ReactionType.LIKE);

        service.upsertReaction(postId, userId, req);

        verify(repository).upsertReaction(postId, userId, com.coolture.common.dto.enums.ReactionType.LIKE);
    }

    @Test
    void deleteReaction_callsRepository() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.deleteReaction(postId, userId);

        verify(repository).deleteReaction(postId, userId);
    }

    @Test
    void upsertParticipation_callsRepository() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        com.coolture.dto.ParticipationRequest req = new com.coolture.dto.ParticipationRequest(com.coolture.common.dto.enums.ParticipationType.INTERESTED);

        service.upsertParticipation(postId, userId, req);

        verify(repository).upsertParticipation(postId, userId, com.coolture.common.dto.enums.ParticipationType.INTERESTED);
    }

    @Test
    void deleteParticipation_callsRepository() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        service.deleteParticipation(postId, userId);

        verify(repository).deleteParticipation(postId, userId);
    }

    @Test
    void createCountryCode_whenValid_callsRepository() throws SQLException {
        com.coolture.dto.CountryCodeRequest req = new com.coolture.dto.CountryCodeRequest("pln");
        when(repository.createCountryCode("PLN")).thenReturn(new com.coolture.common.dto.CountryCodeDto("PLN"));

        var result = service.createCountryCode(req);

        assertEquals("PLN", result.code());
        verify(repository).createCountryCode("PLN");
    }

    @Test
    void createCountryCode_whenNullOrBlank_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.createCountryCode(null));
        assertThrows(IllegalArgumentException.class, () -> service.createCountryCode(new com.coolture.dto.CountryCodeRequest(null)));
        assertThrows(IllegalArgumentException.class, () -> service.createCountryCode(new com.coolture.dto.CountryCodeRequest("   ")));
    }

    @Test
    void updateCountryCode_whenValid_callsRepository() throws SQLException {
        com.coolture.dto.CountryCodeRequest req = new com.coolture.dto.CountryCodeRequest("pol");
        when(repository.updateCountryCode("PLN", "POL")).thenReturn(new com.coolture.common.dto.CountryCodeDto("POL"));

        var result = service.updateCountryCode("pln", req);

        assertEquals("POL", result.code());
        verify(repository).updateCountryCode("PLN", "POL");
    }

    @Test
    void updateCountryCode_whenInvalid_throwsIllegalArgumentException() {
        com.coolture.dto.CountryCodeRequest req = new com.coolture.dto.CountryCodeRequest("pol");
        assertThrows(IllegalArgumentException.class, () -> service.updateCountryCode(null, req));
        assertThrows(IllegalArgumentException.class, () -> service.updateCountryCode("   ", req));
        assertThrows(IllegalArgumentException.class, () -> service.updateCountryCode("PLN", null));
        assertThrows(IllegalArgumentException.class, () -> service.updateCountryCode("PLN", new com.coolture.dto.CountryCodeRequest(" ")));
    }

    @Test
    void deleteCountryCode_whenValid_callsRepository() throws SQLException {
        service.deleteCountryCode("pln");
        verify(repository).deleteCountryCode("PLN");
    }

    @Test
    void deleteCountryCode_whenNullOrBlank_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteCountryCode(null));
        assertThrows(IllegalArgumentException.class, () -> service.deleteCountryCode("   "));
    }

    @Test
    void createPost_whenValidOnlinePost_succeeds() throws SQLException {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com",
            Instant.now(), Instant.now().plusSeconds(3600),
            List.of("talk"), PostType.ONLINE, PostVisibility.PUBLIC,
            null, null, null
        );

        when(repository.createPost(eq(authorId), eq(req))).thenReturn(mock(com.coolture.common.dto.PostDetailDto.class));

        var result = service.createPost(authorId, req);

        assertNotNull(result);
        verify(repository).createPost(eq(authorId), eq(req));
    }

    @Test
    void createPost_whenValidOfflinePost_succeeds() throws SQLException {
        UUID authorId = UUID.randomUUID();
        EventLocationDto loc = new EventLocationDto(
            null, "POL", "Venue", "1", "Street", "00-001", "City",
            new GeoPointDto(50.0, 20.0), null
        );
        CreatePostRequest req = new CreatePostRequest(
            "Concert", "Description", "https://example.com",
            Instant.now(), Instant.now().plusSeconds(3600),
            List.of("music"), PostType.OFFLINE, PostVisibility.PUBLIC,
            loc, null, null
        );

        when(repository.createPost(eq(authorId), eq(req))).thenReturn(mock(com.coolture.common.dto.PostDetailDto.class));

        var result = service.createPost(authorId, req);

        assertNotNull(result);
        verify(repository).createPost(eq(authorId), eq(req));
    }

    @Test
    void updatePost_whenValid_succeeds() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url",
            Instant.now(), null, List.of(), PostType.ONLINE,
            PostStatus.ACTIVE, PostVisibility.PUBLIC, Instant.now(), null, null, null
        );
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            "New Title", "New Desc", null, null, null, null, null, null, null, null, null
        );
        when(repository.updatePost(eq(postId), eq(authorId), eq(existing), eq(req)))
            .thenReturn(mock(com.coolture.common.dto.PostDetailDto.class));

        var result = service.updatePost(postId, authorId, req);

        assertNotNull(result);
        verify(repository).updatePost(eq(postId), eq(authorId), eq(existing), eq(req));
    }

    @Test
    void createLocation_whenValid_callsRepository() throws SQLException {
        EventLocationDto loc = new EventLocationDto(
            null, "POL", "Venue", "1", "Street", "00-001", "City",
            new GeoPointDto(50.0, 20.0), null
        );
        when(repository.createLocation(loc)).thenReturn(loc);

        var result = service.createLocation(loc);

        assertNotNull(result);
        verify(repository).createLocation(loc);
    }

    @Test
    void createLocation_whenNull_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.createLocation(null));
    }

    @Test
    void updateLocation_whenValid_callsRepository() throws SQLException {
        UUID locId = UUID.randomUUID();
        EventLocationDto loc = new EventLocationDto(
            locId, "POL", "Venue", "1", "Street", "00-001", "City",
            new GeoPointDto(50.0, 20.0), null
        );
        when(repository.updateLocation(locId, loc)).thenReturn(loc);

        var result = service.updateLocation(locId, loc);

        assertNotNull(result);
        verify(repository).updateLocation(locId, loc);
    }

    @Test
    void updateLocation_whenInvalid_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.updateLocation(null, mock(EventLocationDto.class)));
        assertThrows(IllegalArgumentException.class, () -> service.updateLocation(UUID.randomUUID(), null));
    }

    @Test
    void deleteLocation_whenValid_callsRepository() throws SQLException {
        UUID locId = UUID.randomUUID();

        service.deleteLocation(locId);

        verify(repository).deleteLocation(locId);
    }

    @Test
    void deleteLocation_whenNull_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteLocation(null));
    }

    @Test
    void createPost_whenValid_publishesEmbeddingMessage() throws SQLException {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com",
            Instant.now(), Instant.now().plusSeconds(3600),
            List.of("talk"), PostType.ONLINE, PostVisibility.PUBLIC,
            null, null, null
        );
        PostDetailDto created = postDetail(UUID.randomUUID(), "Online Event", "Description", List.of("talk"));
        when(repository.createPost(authorId, req)).thenReturn(created);

        PostDetailDto result = service.createPost(authorId, req);

        assertSame(created, result);
        verify(embeddingPublisher).publish(created.id());
    }

    @Test
    void createPost_whenValidationFails_doesNotPublish() {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest(
            "Concert", "Description", "https://example.com",
            Instant.now(), Instant.now().plusSeconds(3600),
            List.of("music"), PostType.OFFLINE, PostVisibility.PUBLIC,
            null, null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        verifyNoInteractions(embeddingPublisher);
    }

    @Test
    void updatePost_whenEmbeddedContentChanged_publishesEmbeddingMessage() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostWriteRepository.ExistingPost existing = activeExistingPost(postId, authorId);
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            "New Title", null, null, null, null, null, null, null, null, null, null
        );
        PostDetailDto updated = postDetail(postId, "New Title", "Desc", List.of());
        when(repository.updatePost(postId, authorId, existing, req)).thenReturn(updated);

        service.updatePost(postId, authorId, req);

        verify(embeddingPublisher).publish(postId);
    }

    @Test
    void updatePost_whenEmbeddedContentUnchanged_doesNotPublish() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostWriteRepository.ExistingPost existing = activeExistingPost(postId, authorId);
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            "Title", null, "https://new.example.com", null, null, null, null, null, null, null, null
        );
        PostDetailDto updated = postDetail(postId, "Title", "Desc", null);
        when(repository.updatePost(postId, authorId, existing, req)).thenReturn(updated);

        service.updatePost(postId, authorId, req);

        verifyNoInteractions(embeddingPublisher);
    }

    private PostWriteRepository.ExistingPost activeExistingPost(UUID postId, UUID authorId) {
        return new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url",
            Instant.now(), null, List.of(), PostType.ONLINE,
            PostStatus.ACTIVE, PostVisibility.PUBLIC, Instant.now(), null, null, null
        );
    }

    private PostDetailDto postDetail(UUID postId, String title, String description, List<String> tags) {
        return new PostDetailDto(
            postId, null, null, title, description, null,
            Instant.now(), null, tags, 0, 0, 0, 0,
            PostType.ONLINE, PostStatus.ACTIVE, PostVisibility.PUBLIC,
            Instant.now(), null, null, null, null, null, null
        );
    }
}
