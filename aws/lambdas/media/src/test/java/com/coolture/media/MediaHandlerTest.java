package com.coolture.media;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.security.SecurityContext;
import com.coolture.media.dto.MediaUploadInitRequest;
import com.coolture.media.dto.MediaUploadInitResponse;
import com.coolture.media.service.MediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MediaHandlerTest {

    private final MediaService service = mock(MediaService.class);
    private final Context lambdaContext = mock(Context.class);
    private final MediaHandler handler = new MediaHandler(service);

    private static final UUID MEDIA_ID = UUID.randomUUID();
    private static final UUID CALLER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(lambdaContext.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @Test
    void initUpload_returns401_whenNotAuthenticated() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/media/uploads/init", validInitBody(), null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                    .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void initUpload_returns201_withPresignedUrl() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/media/uploads/init", validInitBody(), null);

        var initResponse = new MediaUploadInitResponse(
                MEDIA_ID,
                "EVENT_MEDIA/id/file.jpg",
                "http://localhost:3900/bucket/key?sig=abc",
                "PUT",
                Instant.now().plusSeconds(900),
                Map.of("Content-Type", "image/jpeg")
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.initUpload(eq(CALLER_ID), any(MediaUploadInitRequest.class))).thenReturn(initResponse);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(201, response.getStatusCode());
            assertTrue(response.getBody().contains(MEDIA_ID.toString()));
            assertTrue(response.getBody().contains("uploadUrl"));
        }
    }

    @Test
    void initUpload_returns400_whenBodyInvalid() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/media/uploads/init", "{}", null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(400, response.getStatusCode());
        }
    }

    @Test
    void completeUpload_returns401_whenNotAuthenticated() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "POST", "/media/uploads/" + MEDIA_ID + "/complete", "{}", Map.of("mediaId", MEDIA_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                    .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void completeUpload_returns200_withMediaDto() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "POST", "/media/uploads/" + MEDIA_ID + "/complete", "{}", Map.of("mediaId", MEDIA_ID.toString()));

        MediaResourceDto dto = mediaDto(MEDIA_ID, MediaStatus.UPLOADED);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.completeUpload(eq(MEDIA_ID), eq(CALLER_ID), any())).thenReturn(dto);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            assertTrue(response.getBody().contains(MEDIA_ID.toString()));
            assertTrue(response.getBody().contains("UPLOADED"));
        }
    }

    @Test
    void completeUpload_returns409_whenAlreadyCompleted() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "POST", "/media/uploads/" + MEDIA_ID + "/complete", "{}", Map.of("mediaId", MEDIA_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.completeUpload(eq(MEDIA_ID), eq(CALLER_ID), any()))
                    .thenThrow(new IllegalStateException("Media upload is already completed or invalid"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(409, response.getStatusCode());
        }
    }

    @Test
    void getById_returns200_whenFound() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "GET", "/media/" + MEDIA_ID, null, Map.of("mediaId", MEDIA_ID.toString()));

        MediaResourceDto dto = mediaDto(MEDIA_ID, MediaStatus.UPLOADED);
        when(service.getById(MEDIA_ID)).thenReturn(dto);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains(MEDIA_ID.toString()));
    }

    @Test
    void getById_returns404_whenNotFound() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "GET", "/media/" + MEDIA_ID, null, Map.of("mediaId", MEDIA_ID.toString()));

        when(service.getById(MEDIA_ID))
                .thenThrow(new IllegalArgumentException("Media " + MEDIA_ID + " not found"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(404, response.getStatusCode());
    }

    @Test
    void delete_returns401_whenNotAuthenticated() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "DELETE", "/media/" + MEDIA_ID, null, Map.of("mediaId", MEDIA_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                    .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void delete_returns204_whenSuccessful() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "DELETE", "/media/" + MEDIA_ID, null, Map.of("mediaId", MEDIA_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
        }
    }

    @Test
    void delete_returns409_whenMediaInUse() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "DELETE", "/media/" + MEDIA_ID, null, Map.of("mediaId", MEDIA_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            doThrow(new IllegalStateException("Media is currently in use and cannot be deleted"))
                    .when(service).delete(MEDIA_ID, CALLER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(409, response.getStatusCode());
        }
    }

    @Test
    void delete_returns403_whenNotOwner() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "DELETE", "/media/" + MEDIA_ID, null, Map.of("mediaId", MEDIA_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            doThrow(new SecurityException("You do not own this media"))
                    .when(service).delete(MEDIA_ID, CALLER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(403, response.getStatusCode());
        }
    }

    private static String validInitBody() {
        return """
                {
                  "purpose": "EVENT_MEDIA",
                  "mimeType": "image/jpeg",
                  "sizeBytes": 204800,
                  "fileName": "photo.jpg"
                }
                """;
    }

    private static MediaResourceDto mediaDto(UUID id, MediaStatus status) {
        return new MediaResourceDto(
                id,
                MediaPurpose.EVENT_MEDIA,
                "image/jpeg",
                204800L,
                status,
                "http://localhost:3900/bucket/key?sig=abc",
                Instant.now(),
                null
        );
    }
}
