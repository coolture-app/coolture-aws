package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.common.security.SecurityContext;
import com.coolture.dto.CreatePostRequest;
import com.coolture.services.PostWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PostWriteHandlerTest {

    private final PostWriteService service = mock(PostWriteService.class);
    private final Context lambdaContext = mock(Context.class);
    private final PostWriteHandler handler = new PostWriteHandler(service);

    @BeforeEach
    void setUp() {
        lenient().when(lambdaContext.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @Test
    void handleRequest_whenUnauthorized_returns403() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/posts", "{}", null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                .thenThrow(new SecurityException("Missing sub claim in JWT"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(403, response.getStatusCode());
        }
    }

    @Test
    void createPost_whenValidRequest_returns201() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();

        String body = """
            {
                "title": "Cool Event",
                "description": "A very cool event",
                "type": "ONLINE",
                "visibility": "PUBLIC",
                "startsAt": "2026-09-01T12:00:00Z"
            }
        """;

        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/posts", body, null);

        PostDetailDto createdPost = new PostDetailDto(
            postId, null, null, "Cool Event", "A very cool event", null,
            Instant.parse("2026-09-01T12:00:00Z"), null, List.of(), 0, 0, 0, 0,
            PostType.ONLINE, PostStatus.ACTIVE, PostVisibility.PUBLIC,
            Instant.now(), null, null, null, List.of(), null, null
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);
            when(service.createPost(eq(userId), any(CreatePostRequest.class))).thenReturn(createdPost);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(201, response.getStatusCode());
            verify(service).createPost(eq(userId), any(CreatePostRequest.class));
        }
    }

    @Test
    void deletePost_whenValidRequest_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();

        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("DELETE", "/posts/" + postId, null, Map.of("id", postId.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).deletePost(postId, userId);
        }
    }

    @Test
    void handleRequest_whenUnsupportedMethod_returns400() {
        UUID userId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("PUT", "/posts", null, null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(400, response.getStatusCode());
        }
    }
}
