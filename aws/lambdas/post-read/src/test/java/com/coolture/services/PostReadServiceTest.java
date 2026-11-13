package com.coolture.services;

import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.pagination.CursorCodec;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.CursorPayload;
import com.coolture.repository.PostReadRepository;
import com.coolture.repository.PostSort;
import com.coolture.repository.PostsCursor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.Instant;
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

        verify(repository).findFeed(eq(filters), eq(PostSort.RECENT), eq(callerId), isNull(), eq(21));
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

        verify(repository).findFeed(eq(filters), eq(PostSort.RECENT), eq(callerId), isNull(), eq(101));
    }

    /** NEAREST gets a position and STANCE_NEWEST a participation filter, which they need. */
    private static PostFeedFilters sortedBy(String sortBy) {
        boolean nearest = "NEAREST".equals(sortBy);
        boolean stances = "STANCE_NEWEST".equals(sortBy);
        return new PostFeedFilters(
            null, null, null, null, null, null, null, null, nearest ? 54.35 : null, nearest ? 18.65 : null, null,
            stances ? List.of("NOT_INTERESTED") : null, null, null, 20, sortBy
        );
    }

    @Test
    void getFeed_whenStanceNewestHasNoParticipationTypes_throws() {
        PostFeedFilters noStances = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, 20, "STANCE_NEWEST"
        );

        assertThrows(IllegalArgumentException.class, () -> service.getFeed(UUID.randomUUID(), noStances, null, 20));
    }

    @Test
    void getFeed_whenNearestHasNoPosition_throws() {
        PostFeedFilters noPosition = new PostFeedFilters(
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, 20, "NEAREST"
        );

        assertThrows(IllegalArgumentException.class, () -> service.getFeed(null, noPosition, null, 20));
    }

    private static PostCardDto card(int likes) {
        Instant now = Instant.parse("2026-11-02T12:00:00.123456Z");
        return new PostCardDto(UUID.randomUUID(), null, null, "t", null, null, now.plusSeconds(60), null, List.of(),
            likes, 0, 0, null, null, null, now, null, null, null, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RECOMMENDED", "NONSENSE"})
    void getFeed_whenSortIsUnknown_throws(String sortBy) {
        assertThrows(IllegalArgumentException.class, () -> service.getFeed(null, sortedBy(sortBy), null, 20));
    }

    @Test
    void getFeed_whenCursorIsUnreadable_throws() {
        assertThrows(IllegalArgumentException.class, () -> service.getFeed(null, sortedBy(null), "garbage", 20));
    }

    @Test
    void getFeed_whenCursorIsFromAnotherSort_throws() {
        String popularCursor = PostsCursor.after(PostSort.POPULAR, card(3));

        assertThrows(IllegalArgumentException.class, () -> service.getFeed(null, sortedBy("RECENT"), popularCursor, 20));
    }

    @Test
    void getFeed_whenCursorIsTheOldFormat_throws() {
        String old = CursorCodec.encode(new CursorPayload(UUID.randomUUID(), Instant.now()));

        assertThrows(IllegalArgumentException.class, () -> service.getFeed(null, sortedBy("RECENT"), old, 20));
    }

    @ParameterizedTest
    @EnumSource(PostSort.class)
    void getFeed_nextCursorContinuesTheSameSortFromTheLastItem(PostSort sort) throws SQLException {
        PostFeedFilters filters = sortedBy(sort.name());
        List<PostCardDto> rows = List.of(card(9), card(5), card(1));
        when(repository.findFeed(any(), eq(sort), any(), isNull(), eq(3))).thenReturn(rows);

        UUID caller = UUID.randomUUID();
        CursorPage<PostCardDto> page = service.getFeed(caller, filters, null, 2);

        assertEquals(rows.subList(0, 2), page.items());
        assertTrue(page.page().hasMore());

        service.getFeed(caller, filters, page.page().nextCursor(), 2);

        ArgumentCaptor<PostsCursor> cursor = ArgumentCaptor.forClass(PostsCursor.class);
        verify(repository, times(2)).findFeed(any(), eq(sort), any(), cursor.capture(), eq(3));
        PostCardDto last = rows.get(1);
        assertEquals(new PostsCursor(sort, last.id(),
            sort == PostSort.RECENT ? last.createdAt() : null,
            sort == PostSort.POPULAR ? last.positiveReactionCount() : null,
            sort == PostSort.UPCOMING ? last.startsAt() : null), cursor.getValue());
    }

    @Test
    void showsAllPastEvents_whenOptedInOrADateIsChosen() {
        Instant date = Instant.parse("2026-11-02T12:00:00Z");
        assertFalse(sortedBy(null).showsAllPastEvents());
        assertTrue(new PostFeedFilters(null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, 20, null, true).showsAllPastEvents());
        assertTrue(new PostFeedFilters(null, null, null, null, null, null, date, null, null, null, null,
            null, null, null, 20, null).showsAllPastEvents());
        assertTrue(new PostFeedFilters(null, null, null, null, null, null, null, date, null, null, null,
            null, null, null, 20, null).showsAllPastEvents());
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
        when(repository.findPostMarks(filters, bounds, callerId, false)).thenReturn(List.of());

        var result = service.getMapMarks(callerId, filters, bounds, false);

        assertNotNull(result);
        verify(repository).findPostMarks(filters, bounds, callerId, false);
    }

}

