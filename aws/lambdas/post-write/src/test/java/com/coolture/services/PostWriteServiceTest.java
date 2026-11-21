package com.coolture.services;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostLinkDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.UpdatePostRequest;
import com.coolture.common.messaging.EmbeddingRequestPublisher;
import com.coolture.repository.PostWriteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostWriteServiceTest {

    private PostWriteRepository repository;
    private EmbeddingRequestPublisher embeddingPublisher;
    private PostWriteService service;

    @BeforeEach
    void setUp() {
        repository = mock(PostWriteRepository.class);
        embeddingPublisher = mock(EmbeddingRequestPublisher.class);
        service = new PostWriteService(repository, embeddingPublisher);
    }

    @Test
    void createPost_whenOfflineWithoutLocation_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest(
            "Concert", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.OFFLINE,
            null, null, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("OFFLINE posts require a location"));
    }

    @Test
    void createPost_whenOnlineWithLocation_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        EventLocationDto loc = new EventLocationDto(
            "PL", "Venue", "1", "Street", "00-001", "City",
            new GeoPointDto(50.0, 20.0)
        );
        CreatePostRequest req = new CreatePostRequest(
            "Stream", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.ONLINE,
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
            "Online Event", "Description", "https://example.com", null,
            startsAt, endsAt, null, PostType.ONLINE,
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
            "Online Event", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.ONLINE,
            null, List.of(mediaId, mediaId), null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("mediaIds must be unique"));
    }

    @Test
    void createPost_whenMorePhotosThanTheLimit_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID)
            .limit(com.coolture.common.validation.PostLimits.MAX_PHOTOS + 1).toList();

        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.ONLINE,
            null, tooMany, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("at most 8 photos"));
    }

    @Test
    void createPost_whenCoverMediaIdNotPresentInMediaIds_throwsIllegalArgumentException() {
        UUID authorId = UUID.randomUUID();
        UUID media1 = UUID.randomUUID();
        UUID coverMedia = UUID.randomUUID();

        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.ONLINE,
            null, List.of(media1), coverMedia
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("coverMediaId must be present in mediaIds"));
    }

    private static CreatePostRequest onlinePostWith(List<UUID> mediaIds, UUID cover) {
        return new CreatePostRequest(
            "Online Event", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.ONLINE,
            null, mediaIds, cover
        );
    }

    @Test
    void createPost_keepsTheOrderItIsGiven() throws SQLException {
        UUID authorId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CreatePostRequest req = onlinePostWith(List.of(first, second), null);
        when(repository.createPost(eq(authorId), any())).thenReturn(mock(PostDetailDto.class));

        service.createPost(authorId, req);

        verify(repository).createPost(authorId, req);
    }

    @Test
    void createPost_movesANamedCoverToTheFrontAndKeepsTheOthersInOrder() throws SQLException {
        UUID authorId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID cover = UUID.randomUUID();
        CreatePostRequest req = onlinePostWith(List.of(first, second, cover), cover);
        when(repository.createPost(eq(authorId), any())).thenReturn(mock(PostDetailDto.class));

        service.createPost(authorId, req);

        verify(repository).createPost(authorId, req.withMediaIds(List.of(cover, first, second)));
    }

    @Test
    void createPost_whenAPhotoIsAProfileImage_throwsIllegalArgumentException() throws SQLException {
        UUID authorId = UUID.randomUUID();
        UUID avatar = UUID.randomUUID();
        CreatePostRequest req = onlinePostWith(List.of(avatar), null);
        when(repository.validateMediaOwnership(authorId, req.mediaIds()))
            .thenReturn(Map.of(avatar, MediaPurpose.PROFILE_IMAGE));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        assertTrue(ex.getMessage().contains("not an event photo"));
        verify(repository, never()).createPost(any(), any());
    }

    @Test
    void updatePost_whenNotAuthor_throwsSecurityException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();

        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url", null,
            Instant.now(), null, false, PostType.ONLINE,
            PostStatus.ACTIVE, Instant.now(), null, null, null
        );
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            "New Title", null, null, null, null, null, null, null, null, null, null
        );

        SecurityException ex = assertThrows(SecurityException.class, () -> service.updatePost(postId, otherUser, req, Set.of()));
        assertTrue(ex.getMessage().contains("Only the author can modify this post"));
    }

    @Test
    void updatePost_whenPostDeleted_throwsIllegalArgumentException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url", null,
            Instant.now(), null, false, PostType.ONLINE,
            PostStatus.DELETED, Instant.now(), null, Instant.now(), null
        );
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            "New Title", null, null, null, null, null, null, null, null, null, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.updatePost(postId, authorId, req, Set.of()));
        assertTrue(ex.getMessage().contains("Post not found"));
    }

    @Test
    void deletePost_whenNotAuthor_throwsSecurityException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();

        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url", null,
            Instant.now(), null, false, PostType.ONLINE,
            PostStatus.ACTIVE, Instant.now(), null, null, null
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
            postId, authorId, null, "Title", "Desc", "url", null,
            Instant.now(), null, false, PostType.ONLINE,
            PostStatus.ACTIVE, Instant.now(), null, null, null
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
    void createPost_whenValidOnlinePost_succeeds() throws SQLException {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.ONLINE,
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
            "PL", "Venue", "1", "Street", "00-001", "City",
            new GeoPointDto(50.0, 20.0)
        );
        CreatePostRequest req = new CreatePostRequest(
            "Concert", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.OFFLINE,
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
            postId, authorId, null, "Title", "Desc", "url", null,
            Instant.now(), null, false, PostType.ONLINE,
            PostStatus.ACTIVE, Instant.now(), null, null, null
        );
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            "New Title", "New Desc", null, null, null, null, null, null, null, null, null
        );
        when(repository.updatePost(eq(postId), eq(authorId), eq(existing), eq(req), any()))
            .thenReturn(mock(com.coolture.common.dto.PostDetailDto.class));

        var result = service.updatePost(postId, authorId, req, Set.of());

        assertNotNull(result);
        verify(repository).updatePost(eq(postId), eq(authorId), eq(existing), eq(req), any());
    }

    private PostWriteRepository.ExistingPost inPersonPost(UUID postId, UUID authorId) {
        EventLocationDto saved = new EventLocationDto("PL", "Hala", null, null, null, "Gdańsk", new GeoPointDto(54.4, 18.6));
        return new PostWriteRepository.ExistingPost(
            postId, authorId, UUID.randomUUID(), "Title", "Desc", null, null,
            Instant.now(), null, false, PostType.OFFLINE,
            PostStatus.ACTIVE, Instant.now(), null, null, saved
        );
    }

    @Test
    void updatePost_switchingAnInPersonPostToOnline_isAccepted() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostWriteRepository.ExistingPost existing = inPersonPost(postId, authorId);
        when(repository.findById(postId)).thenReturn(Optional.of(existing));
        UpdatePostRequest req = new UpdatePostRequest(
            null, null, null, null, null, null, null, PostType.ONLINE, null, null, null
        );
        when(repository.updatePost(eq(postId), eq(authorId), eq(existing), eq(req), any()))
            .thenReturn(mock(com.coolture.common.dto.PostDetailDto.class));

        service.updatePost(postId, authorId, req, Set.of());

        verify(repository).updatePost(eq(postId), eq(authorId), eq(existing), eq(req), any());
    }

    @Test
    void updatePost_switchingToOnlineWithAPlace_throwsIllegalArgumentException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostWriteRepository.ExistingPost existing = inPersonPost(postId, authorId);
        when(repository.findById(postId)).thenReturn(Optional.of(existing));
        UpdatePostRequest req = new UpdatePostRequest(
            null, null, null, null, null, null, null, PostType.ONLINE, existing.location(), null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.updatePost(postId, authorId, req, Set.of()));
    }

    @Test
    void updatePost_movingTheStartToThePast_throwsIllegalArgumentException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostWriteRepository.ExistingPost existing = inPersonPost(postId, authorId);
        when(repository.findById(postId)).thenReturn(Optional.of(existing));
        UpdatePostRequest req = new UpdatePostRequest(
            null, null, null, null, Instant.now().minus(Duration.ofDays(1)), null, null, null, null, null, null
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.updatePost(postId, authorId, req, Set.of()));

        assertTrue(ex.getMessage().contains("past"));
        verify(repository, never()).updatePost(any(), any(), any(), any(), any());
    }

    @Test
    void startMovedToPast_isRefused_onlyWhenItChangedAtTheMinute() {
        Instant now = Instant.parse("2026-11-17T12:00:30Z");
        Instant oldStart = Instant.parse("2026-11-10T18:00:00Z");

        // The form sends the unchanged start without seconds.
        assertDoesNotThrow(() -> PostWriteService.refuseStartMovedToPast(oldStart.plusSeconds(42), oldStart, now));
        assertDoesNotThrow(() -> PostWriteService.refuseStartMovedToPast(oldStart, null, now));
        assertDoesNotThrow(() -> PostWriteService.refuseStartMovedToPast(oldStart, Instant.parse("2026-11-17T12:00:00Z"), now));
        assertDoesNotThrow(() -> PostWriteService.refuseStartMovedToPast(oldStart, Instant.parse("2026-11-20T18:00:00Z"), now));

        assertThrows(IllegalArgumentException.class,
            () -> PostWriteService.refuseStartMovedToPast(oldStart, Instant.parse("2026-11-17T11:59:00Z"), now));
        assertThrows(IllegalArgumentException.class,
            () -> PostWriteService.refuseStartMovedToPast(oldStart, oldStart.plus(Duration.ofHours(1)), now));
    }

    @Test
    void createPost_whenValid_publishesEmbeddingMessage() throws SQLException {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest(
            "Online Event", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.ONLINE,
            null, null, null
        );
        PostDetailDto created = postDetail(UUID.randomUUID(), "Online Event", "Description");
        when(repository.createPost(authorId, req)).thenReturn(created);

        PostDetailDto result = service.createPost(authorId, req);

        assertSame(created, result);
        verify(embeddingPublisher).requestPost(created.id());
    }

    @Test
    void createPost_whenValidationFails_doesNotPublish() {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest(
            "Concert", "Description", "https://example.com", null,
            Instant.now(), Instant.now().plusSeconds(3600), null, PostType.OFFLINE,
            null, null, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.createPost(authorId, req));
        verifyNoInteractions(embeddingPublisher);
    }

    @Test
    void updatePost_publishesEmbeddingMessage() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostWriteRepository.ExistingPost existing = activeExistingPost(postId, authorId);
        when(repository.findById(postId)).thenReturn(Optional.of(existing));

        UpdatePostRequest req = new UpdatePostRequest(
            null, null, "https://new.example.com", null, null, null, null, null, null, null, null
        );
        PostDetailDto updated = postDetail(postId, "Title", "Desc");
        when(repository.updatePost(eq(postId), eq(authorId), eq(existing), eq(req), any())).thenReturn(updated);

        service.updatePost(postId, authorId, req, Set.of());

        verify(embeddingPublisher).requestPost(postId);
    }

    private PostWriteRepository.ExistingPost activeExistingPost(UUID postId, UUID authorId) {
        return new PostWriteRepository.ExistingPost(
            postId, authorId, null, "Title", "Desc", "url", null,
            Instant.now(), null, false, PostType.ONLINE,
            PostStatus.ACTIVE, Instant.now(), null, null, null
        );
    }

    private PostDetailDto postDetail(UUID postId, String title, String description) {
        return new PostDetailDto(
            postId, null, null, title, description, null, null,
            Instant.now(), null, false, 0, 0, 0, 0, 0,
            PostType.ONLINE, PostStatus.ACTIVE, null,
            Instant.now(), null, null, null, null, null, null, null
        );
    }

    // ===== No end date and merge-patch =====

    /** What these tests check of the saved fields. */
    private record End(String eventUrl, Instant endsAt, boolean openEnded) {}

    private End savedEnd(UUID postId, UUID authorId, PostWriteRepository.ExistingPost existing,
                         UpdatePostRequest req, Set<String> removed) throws SQLException {
        when(repository.findById(postId)).thenReturn(Optional.of(existing));
        when(repository.updatePost(eq(postId), eq(authorId), eq(existing), eq(req), any()))
            .thenReturn(postDetail(postId, existing.title(), existing.description()));
        service.updatePost(postId, authorId, req, removed);
        var saved = org.mockito.ArgumentCaptor.forClass(PostWriteRepository.Fields.class);
        verify(repository).updatePost(eq(postId), eq(authorId), eq(existing), eq(req), saved.capture());
        return new End(saved.getValue().eventUrl(), saved.getValue().endsAt(), saved.getValue().openEnded());
    }

    private static PostWriteRepository.ExistingPost withEnd(UUID postId, UUID authorId, Instant endsAt, boolean openEnded) {
        Instant start = Instant.parse("2030-01-15T18:00:00Z");
        return new PostWriteRepository.ExistingPost(postId, authorId, null, "Title", "Desc", "https://example.com", null,
            start, endsAt, openEnded, PostType.ONLINE, PostStatus.ACTIVE, Instant.now(), null, null, null);
    }

    private static UpdatePostRequest patch(String eventUrl, Instant endsAt, Boolean openEnded) {
        return new UpdatePostRequest(null, null, eventUrl, null, null, endsAt, openEnded, null, null, null, null);
    }

    @Test
    void createPost_withNoEndDateAndAnEnd_throwsIllegalArgumentException() {
        CreatePostRequest req = new CreatePostRequest("Wystawa", "Opis", null, null, Instant.now().plusSeconds(60),
            Instant.now().plusSeconds(7200), true, PostType.ONLINE, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.createPost(UUID.randomUUID(), req));
    }

    @Test
    void updatePost_withNoEndDateAndAnEnd_throwsIllegalArgumentException() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        when(repository.findById(postId)).thenReturn(Optional.of(withEnd(postId, authorId, null, false)));

        assertThrows(IllegalArgumentException.class, () -> service.updatePost(postId, authorId,
            patch(null, Instant.parse("2030-01-15T21:00:00Z"), true), Set.of()));
    }

    @Test
    void updatePost_leavingFieldsOut_keepsThem() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant end = Instant.parse("2030-01-15T21:00:00Z");

        End saved = savedEnd(postId, authorId, withEnd(postId, authorId, end, false),
            patch(null, null, null), Set.of());

        assertEquals(new End("https://example.com", end, false), saved);
    }

    @Test
    void updatePost_endsAtAndEventUrlSentAsNull_areRemoved() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        End saved = savedEnd(postId, authorId,
            withEnd(postId, authorId, Instant.parse("2030-01-15T21:00:00Z"), false),
            patch(null, null, null), Set.of("endsAt", "eventUrl"));

        assertEquals(new End(null, null, false), saved);
    }

    @Test
    void updatePost_noEndDate_dropsTheSavedEnd() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        End saved = savedEnd(postId, authorId,
            withEnd(postId, authorId, Instant.parse("2030-01-15T21:00:00Z"), false),
            patch(null, null, true), Set.of());

        assertEquals(new End("https://example.com", null, true), saved);
    }

    @Test
    void updatePost_anEndOnAPostWithNoEndDate_turnsItOff() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant end = Instant.parse("2030-01-16T21:00:00Z");

        End saved = savedEnd(postId, authorId, withEnd(postId, authorId, null, true),
            patch(null, end, null), Set.of());

        assertEquals(new End("https://example.com", end, false), saved);
    }

    @Test
    void updatePost_anotherFieldOnAPostWithNoEndDate_keepsIt() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        End saved = savedEnd(postId, authorId, withEnd(postId, authorId, null, true),
            patch("https://new.example.com", null, null), Set.of());

        assertEquals(new End("https://new.example.com", null, true), saved);
    }

    // ===== Links =====

    private static final List<PostLinkDto> SAVED_LINKS = List.of(new PostLinkDto("https://bilety.example", "Bilety"));

    private List<PostLinkDto> savedLinks(UpdatePostRequest req, Set<String> removed) throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        PostWriteRepository.ExistingPost existing = new PostWriteRepository.ExistingPost(postId, authorId, null, "Title",
            "Desc", null, SAVED_LINKS, Instant.parse("2030-01-15T18:00:00Z"), null, false, PostType.ONLINE,
            PostStatus.ACTIVE, Instant.now(), null, null, null);
        when(repository.findById(postId)).thenReturn(Optional.of(existing));
        when(repository.updatePost(eq(postId), eq(authorId), eq(existing), eq(req), any()))
            .thenReturn(postDetail(postId, existing.title(), existing.description()));
        service.updatePost(postId, authorId, req, removed);
        var saved = org.mockito.ArgumentCaptor.forClass(PostWriteRepository.Fields.class);
        verify(repository).updatePost(eq(postId), eq(authorId), eq(existing), eq(req), saved.capture());
        return saved.getValue().links();
    }

    private static UpdatePostRequest patchLinks(List<PostLinkDto> links) {
        return new UpdatePostRequest(null, null, null, links, null, null, null, null, null, null, null);
    }

    @Test
    void updatePost_linksLeftOut_keepThem() throws SQLException {
        assertEquals(SAVED_LINKS, savedLinks(patchLinks(null), Set.of()));
    }

    @Test
    void updatePost_linksSentAsNull_areRemoved() throws SQLException {
        assertEquals(List.of(), savedLinks(patchLinks(null), Set.of("links")));
    }

    @Test
    void updatePost_links_replaceTheSavedOnes_andABlankLabelIsNone() throws SQLException {
        List<PostLinkDto> sent = List.of(new PostLinkDto("https://a.example", "  "), new PostLinkDto("https://b.example", " Program "));

        assertEquals(List.of(new PostLinkDto("https://a.example", null), new PostLinkDto("https://b.example", "Program")),
            savedLinks(patchLinks(sent), Set.of()));
    }

    @Test
    void createPost_aBlankLabelIsNone() throws SQLException {
        UUID authorId = UUID.randomUUID();
        CreatePostRequest req = new CreatePostRequest("Stream", "Opis", null, List.of(new PostLinkDto("https://a.example", "")),
            Instant.now().plusSeconds(60), null, null, PostType.ONLINE, null, null, null);
        when(repository.createPost(eq(authorId), any())).thenReturn(mock(PostDetailDto.class));

        service.createPost(authorId, req);

        verify(repository).createPost(authorId, req.withLinks(List.of(new PostLinkDto("https://a.example", null))));
    }
}
