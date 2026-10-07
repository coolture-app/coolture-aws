package com.coolture.services;

import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.CommentStatus;
import com.coolture.common.pagination.CursorPage;
import com.coolture.repository.CommentReadRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentReadServiceTest {

    private final CommentReadRepository repository = mock(CommentReadRepository.class);
    private final CommentReadService service = new CommentReadService(repository);

    private CommentSummaryDto dto(UUID id, UUID postId, Instant createdAt) {
        UserSummaryDto author = new UserSummaryDto(
            UUID.randomUUID(), "jdoe", "John", "Doe", null, createdAt, 0, 0);
        return new CommentSummaryDto(
            id, postId, null, null, author, "hello", 0, 0,
            createdAt, null, null, CommentStatus.ACTIVE);
    }

    @Test
    void list_whenRoots_returnsPageWithLimitPlusOne() throws Exception {
        UUID postId = UUID.randomUUID();
        Instant now = Instant.now();
        List<CommentSummaryDto> rows = List.of(
            dto(UUID.randomUUID(), postId, now),
            dto(UUID.randomUUID(), postId, now),
            dto(UUID.randomUUID(), postId, now));
        when(repository.findRootCommentsForPost(eq(postId), isNull(), isNull(), eq(3)))
            .thenReturn(rows);

        CursorPage<CommentSummaryDto> page = service.list(postId, null, null, 2);

        assertEquals(2, page.items().size());
        assertTrue(page.page().hasMore());
        assertEquals(2, page.page().limit());
        verify(repository).ensurePostActive(postId);
    }

    @Test
    void list_whenRepliesAndParentBelongs_returnsReplies() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(repository.findCommentHeader(parentId))
            .thenReturn(Optional.of(new CommentReadRepository.CommentHeader(parentId, postId, "ACTIVE", false)));
        List<CommentSummaryDto> rows = List.of(dto(UUID.randomUUID(), postId, Instant.now()));
        when(repository.findRepliesForParent(eq(parentId), isNull(), isNull(), eq(21)))
            .thenReturn(rows);

        CursorPage<CommentSummaryDto> page = service.list(postId, parentId, null, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.page().hasMore());
    }

    @Test
    void list_whenParentMissing_throwsNotFound() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(repository.findCommentHeader(parentId)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.list(postId, parentId, null, 20));

        assertTrue(ex.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    void list_whenParentBelongsToDifferentPost_throwsNotFound() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(repository.findCommentHeader(parentId))
            .thenReturn(Optional.of(
                new CommentReadRepository.CommentHeader(parentId, UUID.randomUUID(), "ACTIVE", false)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.list(postId, parentId, null, 20));

        assertTrue(ex.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    void list_whenParentDeleted_stillReturnsRepliesToPreserveThread() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(repository.findCommentHeader(parentId))
            .thenReturn(Optional.of(
                new CommentReadRepository.CommentHeader(parentId, postId, "DELETED", true)));
        List<CommentSummaryDto> rows = List.of(dto(UUID.randomUUID(), postId, Instant.now()));
        when(repository.findRepliesForParent(eq(parentId), isNull(), isNull(), eq(21)))
            .thenReturn(rows);

        CursorPage<CommentSummaryDto> page = service.list(postId, parentId, null, 20);

        assertEquals(1, page.items().size());
    }

    @Test
    void list_whenMalformedCursor_startsFromFirstPage() throws Exception {
        UUID postId = UUID.randomUUID();
        when(repository.findRootCommentsForPost(eq(postId), isNull(), isNull(), anyInt()))
            .thenReturn(List.of());

        CursorPage<CommentSummaryDto> page = service.list(postId, null, "not-a-cursor!!!", 20);

        assertEquals(0, page.items().size());
        verify(repository).findRootCommentsForPost(eq(postId), isNull(), isNull(), eq(21));
    }

    @Test
    void list_whenLimitOutOfRange_clampsTo1To100() throws Exception {
        UUID postId = UUID.randomUUID();
        when(repository.findRootCommentsForPost(eq(postId), isNull(), isNull(), anyInt()))
            .thenReturn(List.of());

        service.list(postId, null, null, 0);
        verify(repository).findRootCommentsForPost(eq(postId), isNull(), isNull(), eq(21));

        service.list(postId, null, null, 500);
        verify(repository).findRootCommentsForPost(eq(postId), isNull(), isNull(), eq(101));
    }
}
