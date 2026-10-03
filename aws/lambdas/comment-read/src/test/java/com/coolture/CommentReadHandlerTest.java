package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.CommentStatus;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.PaginationMeta;
import com.coolture.services.CommentReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentReadHandlerTest {

    private final CommentReadService service = mock(CommentReadService.class);
    private final Context lambdaContext = mock(Context.class);
    private final CommentReadHandler handler = new CommentReadHandler(service);

    @BeforeEach
    void setUp() {
        lenient().when(lambdaContext.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    private CommentSummaryDto commentDto(UUID id, UUID postId) {
        UserSummaryDto author = new UserSummaryDto(
            UUID.randomUUID(), "jdoe", "John", "Doe", null, Instant.now(), 0, 0);
        return new CommentSummaryDto(
            id, postId, null, null, author, "hello", 0, 0,
            Instant.now(), null, null, CommentStatus.ACTIVE);
    }

    @Test
    void listRoots_whenValidRequest_returns200AndDelegates() throws Exception {
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get(
            "/posts/" + postId + "/comments",
            Map.of("limit", "20"),
            Map.of("postId", postId.toString()));

        CursorPage<CommentSummaryDto> page = new CursorPage<>(
            List.of(commentDto(UUID.randomUUID(), postId)),
            new PaginationMeta(20, false, null));
        when(service.list(eq(postId), isNull(), isNull(), eq(20))).thenReturn(page);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        verify(service).list(eq(postId), isNull(), isNull(), eq(20));
    }

    @Test
    void listReplies_whenParentCommentIdGiven_passesItThrough() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get(
            "/posts/" + postId + "/comments",
            Map.of("parentCommentId", parentId.toString()),
            Map.of("postId", postId.toString()));

        CursorPage<CommentSummaryDto> page = new CursorPage<>(
            List.of(), new PaginationMeta(20, false, null));
        when(service.list(eq(postId), eq(parentId), isNull(), eq(20))).thenReturn(page);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        verify(service).list(eq(postId), eq(parentId), isNull(), eq(20));
    }

    @Test
    void handleRequest_whenPostNotFound_returns404() throws Exception {
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get(
            "/posts/" + postId + "/comments", null, Map.of("postId", postId.toString()));

        when(service.list(any(), any(), any(), anyInt()))
            .thenThrow(new IllegalArgumentException("Post with id '" + postId + "' was not found"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void handleRequest_whenInvalidPostId_returns400() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get(
            "/posts/not-a-uuid/comments", null, Map.of("postId", "not-a-uuid"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void handleRequest_whenNonGetMethod_returns400() {
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "POST", "/posts/" + postId + "/comments", null, Map.of("postId", postId.toString()));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(400, response.getStatusCode());
    }

    @Test
    void handleRequest_whenUnexpectedError_returnsRedacted500() throws Exception {
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get(
            "/posts/" + postId + "/comments", null, Map.of("postId", postId.toString()));

        when(service.list(any(), any(), any(), anyInt()))
            .thenThrow(new RuntimeException("boom-db-secret"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("Internal server error"));
        assertFalse(response.getBody().contains("boom-db-secret"));
    }
}
