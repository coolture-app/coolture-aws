package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.security.SecurityContext;
import com.coolture.dto.CreatePostRequest;
import com.coolture.repository.DuplicateEventException;
import com.coolture.services.PostWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    void handleRequest_whenUnauthenticated_returns401() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/posts", "{}", null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void handleRequest_whenForbidden_returns403() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/posts", "{}", null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                .thenThrow(new SecurityException("Forbidden: insufficient permissions"));

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
                "startsAt": "2030-09-01T12:00:00Z"
            }
        """;

        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/posts", body, null);

        PostDetailDto createdPost = new PostDetailDto(
            postId, null, null, "Cool Event", "A very cool event", null, null,
            Instant.parse("2026-09-01T12:00:00Z"), null, false, 0, 0, 0, 0, 0,
            PostType.ONLINE, PostStatus.ACTIVE, null,
            Instant.now(), null, null, null, List.of(), null, null, null
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

    @ParameterizedTest
    @CsvSource(value = {"PUT|reaction|{\"type\":\"LIKE\"}", "DELETE|reaction|", "PUT|participation|{\"type\":\"INTERESTED\"}",
        "DELETE|participation|"}, delimiter = '|')
    void likesAndStances_return204_andReachTheirOwnCall(String method, String route, String body) throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            method, "/posts/" + postId + "/" + route, body, Map.of("postId", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            assertEquals(204, handler.handleRequest(event, lambdaContext).getStatusCode());
            switch (method + " " + route) {
                case "PUT reaction" -> verify(service).upsertReaction(eq(postId), eq(userId), any());
                case "DELETE reaction" -> verify(service).deleteReaction(postId, userId);
                case "PUT participation" -> verify(service).upsertParticipation(eq(postId), eq(userId), any());
                default -> verify(service).deleteParticipation(postId, userId);
            }
        }
    }

    @Test
    void updatePost_whenValidRequest_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        String body = "{\"title\":\"Updated Title\"}";
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PATCH", "/posts/" + postId, body, Map.of("id", postId.toString())
        );

        PostDetailDto updatedPost = new PostDetailDto(
            postId, null, null, "Updated Title", "Desc", null, null,
            Instant.parse("2026-09-01T12:00:00Z"), null, false, 0, 0, 0, 0, 0,
            PostType.ONLINE, PostStatus.EDITED, null,
            Instant.now(), Instant.now(), null, null, List.of(), null, null, null
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);
            when(service.updatePost(eq(postId), eq(userId), any(), any())).thenReturn(updatedPost);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            verify(service).updatePost(eq(postId), eq(userId), any(), eq(Set.of()));
        }
    }

    @Test
    void updatePost_tellsTheServiceWhichFieldsWereSentAsNull() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        String body = "{\"title\":\"Updated Title\",\"endsAt\":null,\"eventUrl\":null,\"links\":null,\"description\":null}";
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PATCH", "/posts/" + postId, body, Map.of("id", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            handler.handleRequest(event, lambdaContext);

            verify(service).updatePost(eq(postId), eq(userId), any(), eq(Set.of("endsAt", "eventUrl", "links")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"title\":\"   \"}", "{\"description\":\"\"}", "{\"description\":\" \\n \"}"})
    void updatePost_whenATitleOrDescriptionIsBlank_returns400(String body) throws Exception {
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PATCH", "/posts/" + postId, body, Map.of("id", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(UUID.randomUUID());

            assertEquals(400, handler.handleRequest(event, lambdaContext).getStatusCode());
            verify(service, never()).updatePost(any(), any(), any(), any());
        }
    }

    @Test
    void updatePost_whenADescriptionHasSeveralLines_isAccepted() throws Exception {
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PATCH", "/posts/" + postId, "{\"description\":\"One\\nTwo\"}", Map.of("id", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(UUID.randomUUID());

            handler.handleRequest(event, lambdaContext);

            verify(service).updatePost(any(), any(), any(), any());
        }
    }

    private APIGatewayV2HTTPResponse create(String links) {
        String body = """
            {"title": "Koncert", "description": "Opis", "type": "ONLINE", "startsAt": "2030-09-01T12:00:00Z", "links": %s}
            """.formatted(links);
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/posts", body, null);
        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(UUID.randomUUID());
            return handler.handleRequest(event, lambdaContext);
        }
    }

    @Test
    void createPost_withMoreThanThreeLinks_returns400() {
        String link = "{\"url\": \"https://a.example\"}";

        assertEquals(400, create("[" + String.join(",", link, link, link, link) + "]").getStatusCode());
    }

    @Test
    void createPost_withALinkThatIsNotHttp_returns400() {
        assertEquals(400, create("[{\"url\": \"javascript:alert(1)\"}]").getStatusCode());
    }

    @Test
    void createPost_withALabelOverFortyCharacters_returns400() {
        assertEquals(400, create("[{\"url\": \"https://a.example\", \"label\": \"" + "x".repeat(41) + "\"}]").getStatusCode());
    }

    @Test
    void createPost_aSecondPostFromTheSameEventPage_returns409() throws Exception {
        when(service.createPost(any(), any())).thenThrow(new DuplicateEventException(null));

        assertEquals(409, create("[]").getStatusCode());
    }
}
