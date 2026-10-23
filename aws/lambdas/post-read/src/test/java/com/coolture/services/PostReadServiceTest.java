package com.coolture.services;

import com.coolture.common.dto.PostFeedFilters;
import com.coolture.repository.PostReadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PostReadServiceTest {

    private PostReadRepository repository;
    private PostReadService service;

    @BeforeEach
    void setUp() {
        repository = mock(PostReadRepository.class);
        service = new PostReadService(repository);
    }

    @Test
    void getRecommendations_whenCallerIdNull_throwsSecurityException() {
        assertThrows(SecurityException.class, () -> service.getRecommendations(null, null, 20));
    }

    @Test
    void getRecommendations_whenValid_callsRepository() throws SQLException {
        UUID callerId = UUID.randomUUID();
        when(repository.findRecommendations(eq(callerId), isNull(), isNull(), eq(21)))
            .thenReturn(List.of());

        var result = service.getRecommendations(callerId, null, 20);

        assertNotNull(result);
        assertTrue(result.items().isEmpty());
        verify(repository).findRecommendations(eq(callerId), isNull(), isNull(), eq(21));
    }

    @Test
    void getFeed_whenParticipationFilterWithoutCallerId_throwsSecurityException() {
        PostFeedFilters filters = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            List.of("INTERESTED"), null, null, 20, null
        );

        assertThrows(SecurityException.class, () -> service.getFeed(null, filters, null, 20));
    }

    @Test
    void getFeed_whenInvalidParticipationType_throwsIllegalArgumentException() {
        UUID callerId = UUID.randomUUID();
        PostFeedFilters filters = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            List.of("INVALID_TYPE"), null, null, 20, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.getFeed(callerId, filters, null, 20));
    }

    @Test
    void getFeed_whenReactionFilterWithoutCallerId_throwsSecurityException() {
        PostFeedFilters filters = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            null, "LIKE", null, 20, null
        );

        assertThrows(SecurityException.class, () -> service.getFeed(null, filters, null, 20));
    }

    @Test
    void getFeed_whenInvalidReactionType_throwsIllegalArgumentException() {
        UUID callerId = UUID.randomUUID();
        PostFeedFilters filters = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            null, "SUPER_LIKE", null, 20, null
        );

        assertThrows(IllegalArgumentException.class, () -> service.getFeed(callerId, filters, null, 20));
    }

    @Test
    void getById_whenNotFound_throwsIllegalArgumentException() throws SQLException {
        UUID postId = UUID.randomUUID();
        when(repository.findById(postId, null)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.getById(postId, null));
        assertTrue(ex.getMessage().contains("Post not found"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, -100})
    void getFeed_whenLimitNonPositive_clampsTo20(int limit) throws SQLException {
        UUID callerId = UUID.randomUUID();
        PostFeedFilters filters = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, limit, null
        );

        when(repository.findFeed(any(), any(), any(), any(), eq(21)))
            .thenReturn(List.of());

        service.getFeed(callerId, filters, null, limit);

        verify(repository).findFeed(eq(filters), eq(callerId), isNull(), isNull(), eq(21));
    }

    @Test
    void getFeed_whenLimitExceeds100_clampsTo100() throws SQLException {
        UUID callerId = UUID.randomUUID();
        PostFeedFilters filters = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, 500, null
        );

        when(repository.findFeed(any(), any(), any(), any(), eq(101)))
            .thenReturn(List.of());

        service.getFeed(callerId, filters, null, 500);

        verify(repository).findFeed(eq(filters), eq(callerId), isNull(), isNull(), eq(101));
    }

    @Test
    void listCountryCodes_callsRepository() throws SQLException {
        when(repository.findAllCountryCodes()).thenReturn(List.of(new com.coolture.common.dto.CountryCodeDto("POL")));

        var result = service.listCountryCodes();

        assertEquals(1, result.size());
        assertEquals("POL", result.get(0).code());
        verify(repository).findAllCountryCodes();
    }

    @Test
    void getById_whenFound_returnsDetail() throws SQLException {
        UUID postId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        var detail = mock(com.coolture.common.dto.PostDetailDto.class);
        when(repository.findById(postId, callerId)).thenReturn(Optional.of(detail));

        var result = service.getById(postId, callerId);

        assertNotNull(result);
        assertEquals(detail, result);
        verify(repository).findById(postId, callerId);
    }

    @Test
    void getMapMarks_whenValid_callsRepository() throws SQLException {
        UUID callerId = UUID.randomUUID();
        PostFeedFilters filters = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, 20, null
        );
        var bounds = new com.coolture.common.dto.MapBoundsDto(
            new com.coolture.common.dto.GeoPointDto(54.5, 18.0),
            new com.coolture.common.dto.GeoPointDto(54.0, 19.0)
        );
        when(repository.findPostMarks(filters, bounds, callerId)).thenReturn(List.of());

        var result = service.getMapMarks(callerId, filters, bounds);

        assertNotNull(result);
        verify(repository).findPostMarks(filters, bounds, callerId);
    }

    @Test
    void listEventLocations_callsRepository() throws SQLException {
        when(repository.findEventLocations(isNull(), isNull(), eq(21)))
            .thenReturn(List.of());

        var result = service.listEventLocations(null, 20);

        assertNotNull(result);
        assertTrue(result.items().isEmpty());
        verify(repository).findEventLocations(isNull(), isNull(), eq(21));
    }
}

