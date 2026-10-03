package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.CommentStatus;
import com.coolture.common.security.SecurityContext;
import com.coolture.dto.CommentCreateRequest;
import com.coolture.services.CommentWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class CommentWriteHandlerTest {

    private final CommentWriteService service = mock(CommentWriteService.class);
    private final Context lambdaContext = mock(Context.class);
    private final CommentWriteHandler handler = new CommentWriteHandler(service);

    @BeforeEach
    void setUp() {
        lenient().when(lambdaContext.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    private CommentSummaryDto dto(UUID id, UUID postId) {
        UserSummaryDto author = new UserSummaryDto(
            UUID.randomUUID(), "jdoe", "John", "Doe", null, Instant.now(), 0, 0);
        return new CommentSummaryDto(
            id, postId, null, null, author, "hello", 0, 0,
            Instant.now(), null, null, CommentStatus.ACTIVE);
    }

    @Test
    void handleRequest_whenMissingAuth_returns401() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "POST", "/posts/" + UUID.randomUUID() + "/comments",
            "{\"content\":\"hi\"}", null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                .thenThrow(new SecurityException("User not authenticated"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void createComment_whenValidRequest_returns201() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "POST", "/posts/" + postId + "/comments",
            "{\"content\":\"hello\"}", Map.of("postId", postId.toString()));

        when(service.create(eq(postId), eq(userId), any(CommentCreateRequest.class)))
            .thenReturn(dto(commentId, postId));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(201, response.getStatusCode());
            verify(service).create(eq(postId), eq(userId), any(CommentCreateRequest.class));
        }
    }

    @Test
    void createComment_whenBlankContent_returns400WithoutCallingService() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "POST", "/posts/" + postId + "/comments",
            "{\"content\":\"\"}", Map.of("postId", postId.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(400, response.getStatusCode());
            verify(service, org.mockito.Mockito.never())
                .create(any(), any(), any());
        }
    }

    @Test
    void updateComment_whenValidRequest_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PATCH", "/comments/" + commentId,
            "{\"content\":\"edited\"}", Map.of("commentId", commentId.toString()));

        when(service.update(eq(commentId), eq(userId), any()))
            .thenReturn(dto(commentId, UUID.randomUUID()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void deleteComment_whenValidRequest_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "DELETE", "/comments/" + commentId, null, Map.of("commentId", commentId.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).softDelete(commentId, userId);
        }
    }

    @Test
    void handleRequest_whenNotAuthor_returns403() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "DELETE", "/comments/" + commentId, null, Map.of("commentId", commentId.toString()));

        doThrow(new SecurityException("Only the author can modify this comment"))
            .when(service).softDelete(eq(commentId), eq(userId));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(403, response.getStatusCode());
        }
    }

    @Test
    void handleRequest_whenCommentNotFound_returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PATCH", "/comments/" + commentId,
            "{\"content\":\"edited\"}", Map.of("commentId", commentId.toString()));

        when(service.update(eq(commentId), eq(userId), any()))
            .thenThrow(new IllegalArgumentException(
                "Comment with id '" + commentId + "' was not found"));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(404, response.getStatusCode());
        }
    }

    @Test
    void handleRequest_whenUnsupportedMethod_returns400() {
        UUID userId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PUT", "/comments/" + UUID.randomUUID(), null, null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(400, response.getStatusCode());
        }
    }

    @Test
    void handleRequest_whenUnexpectedError_returnsRedacted500() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "DELETE", "/comments/" + commentId, null, Map.of("commentId", commentId.toString()));

        doThrow(new RuntimeException("boom-db-secret"))
            .when(service).softDelete(eq(commentId), eq(userId));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(500, response.getStatusCode());
            assertTrue(response.getBody().contains("Internal server error"));
            assertFalse(response.getBody().contains("boom-db-secret"));
        }
    }
}
