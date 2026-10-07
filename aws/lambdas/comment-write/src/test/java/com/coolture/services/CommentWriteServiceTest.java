package com.coolture.services;

import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.CommentStatus;
import com.coolture.dto.CommentCreateRequest;
import com.coolture.dto.CommentUpdateRequest;
import com.coolture.repository.CommentWriteRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentWriteServiceTest {

    private final CommentWriteRepository repository = mock(CommentWriteRepository.class);
    private final CommentWriteService service = new CommentWriteService(repository);

    private CommentWriteRepository.ExistingComment existing(
            UUID id, UUID postId, UUID authorId, UUID rootId, UUID parentId, UUID[] ancestors, String status) {
        return new CommentWriteRepository.ExistingComment(
            id, postId, authorId, rootId, parentId, ancestors, status, null);
    }

    private CommentSummaryDto dto(UUID id, UUID postId) {
        UserSummaryDto author = new UserSummaryDto(
            UUID.randomUUID(), "jdoe", "John", "Doe", null, Instant.now(), 0, 0);
        return new CommentSummaryDto(
            id, postId, null, null, author, "hello", 0, 0,
            Instant.now(), null, null, CommentStatus.ACTIVE);
    }

    @Test
    void create_whenRoot_savesWithEmptyAncestorsAndNullRoot() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        when(repository.createComment(eq(postId), eq(callerId), eq("hello"),
            isNull(), isNull(), any(UUID[].class)))
            .thenReturn(dto(commentId, postId));

        CommentSummaryDto result = service.create(
            postId, callerId, new CommentCreateRequest("hello", null));

        assertEquals(commentId, result.id());
        verify(repository).ensurePostActive(postId);
    }

    @Test
    void create_whenReply_chainsAncestorsAndRoot() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID rootId = UUID.randomUUID();
        when(repository.findExistingComment(parentId)).thenReturn(Optional.of(
            existing(parentId, postId, UUID.randomUUID(), rootId, rootId, new UUID[]{rootId}, "ACTIVE")));
        when(repository.createComment(eq(postId), eq(callerId), eq("reply"),
            eq(rootId), eq(parentId), any(UUID[].class)))
            .thenReturn(dto(UUID.randomUUID(), postId));

        service.create(postId, callerId, new CommentCreateRequest("reply", parentId));

        verify(repository).createComment(eq(postId), eq(callerId), eq("reply"),
            eq(rootId), eq(parentId), any(UUID[].class));
    }

    @Test
    void create_whenParentAtMaxDepth_throwsForbidden() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(repository.findExistingComment(parentId)).thenReturn(Optional.of(
            existing(parentId, postId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new UUID[]{UUID.randomUUID(), UUID.randomUUID()}, "ACTIVE")));

        SecurityException ex = assertThrows(SecurityException.class, () ->
            service.create(postId, UUID.randomUUID(), new CommentCreateRequest("x", parentId)));

        assertTrue(ex.getMessage().contains("depth"));
    }

    @Test
    void create_whenParentBelongsToDifferentPost_throwsForbidden() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(repository.findExistingComment(parentId)).thenReturn(Optional.of(
            existing(parentId, UUID.randomUUID(), UUID.randomUUID(), null, null, new UUID[0], "ACTIVE")));

        assertThrows(SecurityException.class, () ->
            service.create(postId, UUID.randomUUID(), new CommentCreateRequest("x", parentId)));
    }

    @Test
    void create_whenParentMissing_throwsNotFound() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(repository.findExistingComment(parentId)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
            service.create(postId, UUID.randomUUID(), new CommentCreateRequest("x", parentId)));

        assertTrue(ex.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    void create_whenParentDeleted_throwsNotFound() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        when(repository.findExistingComment(parentId)).thenReturn(Optional.of(
            existing(parentId, postId, UUID.randomUUID(), null, null, new UUID[0], "DELETED")));

        assertThrows(IllegalArgumentException.class, () ->
            service.create(postId, UUID.randomUUID(), new CommentCreateRequest("x", parentId)));
    }

    @Test
    void update_whenAuthor_updatesContent() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        when(repository.findExistingComment(commentId)).thenReturn(Optional.of(
            existing(commentId, UUID.randomUUID(), callerId, null, null, new UUID[0], "ACTIVE")));
        when(repository.updateComment(commentId, "edited")).thenReturn(dto(commentId, UUID.randomUUID()));

        CommentSummaryDto result = service.update(
            commentId, callerId, new CommentUpdateRequest("edited"));

        assertEquals(commentId, result.id());
        verify(repository).updateComment(commentId, "edited");
    }

    @Test
    void update_whenNotAuthor_throwsForbidden() throws Exception {
        UUID commentId = UUID.randomUUID();
        when(repository.findExistingComment(commentId)).thenReturn(Optional.of(
            existing(commentId, UUID.randomUUID(), UUID.randomUUID(), null, null, new UUID[0], "ACTIVE")));

        assertThrows(SecurityException.class, () ->
            service.update(commentId, UUID.randomUUID(), new CommentUpdateRequest("edited")));
    }

    @Test
    void softDelete_whenAuthor_deletesWithAncestors() throws Exception {
        UUID commentId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        UUID[] ancestors = new UUID[]{UUID.randomUUID()};
        when(repository.findExistingComment(commentId)).thenReturn(Optional.of(
            existing(commentId, postId, callerId, ancestors[0], ancestors[0], ancestors, "ACTIVE")));

        service.softDelete(commentId, callerId);

        verify(repository).softDeleteComment(commentId, postId, ancestors);
    }

    @Test
    void softDelete_whenNotAuthor_throwsForbidden() throws Exception {
        UUID commentId = UUID.randomUUID();
        when(repository.findExistingComment(commentId)).thenReturn(Optional.of(
            existing(commentId, UUID.randomUUID(), UUID.randomUUID(), null, null, new UUID[0], "ACTIVE")));

        assertThrows(SecurityException.class, () ->
            service.softDelete(commentId, UUID.randomUUID()));
    }
}
