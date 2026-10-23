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
                "visibility": "PUBLIC",
                "startsAt": "2027-09-01T12:00:00Z"
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

    @Test
    void upsertReaction_whenValidRequest_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        String body = "{\"type\":\"LIKE\"}";
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PUT", "/posts/" + postId + "/reaction", body, Map.of("postId", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).upsertReaction(eq(postId), eq(userId), any());
        }
    }

    @Test
    void deleteReaction_whenValidRequest_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "DELETE", "/posts/" + postId + "/reaction", null, Map.of("postId", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).deleteReaction(eq(postId), eq(userId));
        }
    }

    @Test
    void upsertParticipation_whenValidRequest_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        String body = "{\"type\":\"INTERESTED\"}";
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PUT", "/posts/" + postId + "/participation", body, Map.of("postId", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).upsertParticipation(eq(postId), eq(userId), any());
        }
    }

    @Test
    void deleteParticipation_whenValidRequest_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "DELETE", "/posts/" + postId + "/participation", null, Map.of("postId", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).deleteParticipation(eq(postId), eq(userId));
        }
    }

    @Test
    void createCountryCode_whenValidRequest_returns201() throws Exception {
        UUID userId = UUID.randomUUID();
        String body = "{\"code\":\"POL\"}";
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/dicts/admin/country-codes", body, null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);
            when(service.createCountryCode(any())).thenReturn(new com.coolture.common.dto.CountryCodeDto("POL"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(201, response.getStatusCode());
            org.junit.jupiter.api.Assertions.assertTrue(response.getBody().contains("POL"));
            verify(service).createCountryCode(any());
        }
    }

    @Test
    void updateCountryCode_whenValidRequest_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        String body = "{\"code\":\"POL\"}";
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("PUT", "/dicts/admin/country-codes/PLN", body, Map.of("code", "PLN"));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);
            when(service.updateCountryCode(eq("PLN"), any())).thenReturn(new com.coolture.common.dto.CountryCodeDto("POL"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            org.junit.jupiter.api.Assertions.assertTrue(response.getBody().contains("POL"));
            verify(service).updateCountryCode(eq("PLN"), any());
        }
    }

    @Test
    void deleteCountryCode_whenValidRequest_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("DELETE", "/dicts/admin/country-codes/PLN", null, Map.of("code", "PLN"));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).deleteCountryCode("PLN");
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
            postId, null, null, "Updated Title", "Desc", null,
            Instant.parse("2026-09-01T12:00:00Z"), null, List.of(), 0, 0, 0, 0,
            PostType.ONLINE, PostStatus.EDITED, PostVisibility.PUBLIC,
            Instant.now(), Instant.now(), null, null, List.of(), null, null
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);
            when(service.updatePost(eq(postId), eq(userId), any())).thenReturn(updatedPost);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            verify(service).updatePost(eq(postId), eq(userId), any());
        }
    }

    @Test
    void createLocation_whenValidRequest_returns201() throws Exception {
        UUID userId = UUID.randomUUID();
        String body = """
            {
                "countryCode": "POL",
                "venueName": "Opera",
                "street": "Glowna",
                "buildingNum": "10",
                "postalCode": "80-001",
                "city": "Gdansk",
                "coordinates": {
                    "latitude": 54.35,
                    "longitude": 18.64
                }
            }
        """;
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/event-locations", body, null);

        var loc = new com.coolture.common.dto.EventLocationDto(
            UUID.randomUUID(), "POL", "Opera", "10", "Glowna", "80-001", "Gdansk",
            new com.coolture.common.dto.GeoPointDto(54.35, 18.64), Instant.now()
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);
            when(service.createLocation(any())).thenReturn(loc);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(201, response.getStatusCode());
            verify(service).createLocation(any());
        }
    }

    @Test
    void updateLocation_whenValidRequest_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID locId = UUID.randomUUID();
        String body = """
            {
                "countryCode": "POL",
                "venueName": "Teatr",
                "street": "Morska",
                "buildingNum": "5",
                "postalCode": "81-001",
                "city": "Gdynia",
                "coordinates": {
                    "latitude": 54.51,
                    "longitude": 18.53
                }
            }
        """;
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "PATCH", "/event-locations/" + locId, body, Map.of("id", locId.toString())
        );

        var loc = new com.coolture.common.dto.EventLocationDto(
            locId, "POL", "Teatr", "5", "Morska", "81-001", "Gdynia",
            new com.coolture.common.dto.GeoPointDto(54.51, 18.53), Instant.now()
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);
            when(service.updateLocation(eq(locId), any())).thenReturn(loc);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            verify(service).updateLocation(eq(locId), any());
        }
    }

    @Test
    void deleteLocation_whenValidRequest_returns204() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID locId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
            "DELETE", "/event-locations/" + locId, null, Map.of("id", locId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(userId);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).deleteLocation(locId);
        }
    }
}

