package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.security.SecurityContext;
import com.coolture.services.PostReadService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PostReadHandlerTest {

    private final PostReadService postReadService = mock(PostReadService.class);
    private final Context lambdaContext = mock(Context.class);
    private final PostReadHandler handler = new PostReadHandler(postReadService);

    @BeforeEach
    void stubLambdaLogger() {
        lenient().when(lambdaContext.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @Test
    void handleRequest_whenMethodIsNotGET_thenReturns400AndDoesNotCallService() {
        APIGatewayV2HTTPEvent event =
            ApiGatewayEvents.request("POST", "/posts", null, null);

        APIGatewayV2HTTPResponse response =
            handler.handleRequest(event, lambdaContext);

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(postReadService);
    }

    @Test
    void getFeed_whenRequestIsValid_thenReturns200AndPassesParsedFiltersToService() throws Exception {
        UUID callerId = UUID.randomUUID();

        Map<String, String> query = Map.of(
            "q", "jazz",
            "tags", "music,outdoor",
            "limit", "10",
            "cursor", "abc123"
        );

        APIGatewayV2HTTPEvent event =
            ApiGatewayEvents.get("/posts", query);

        CursorPage<PostCardDto> emptyPage =
            CursorPage.of(
                List.of(),
                10,
                PostCardDto::id,
                PostCardDto::createdAt
            );

        ArgumentCaptor<PostFeedFilters> filtersCaptor =
            ArgumentCaptor.forClass(PostFeedFilters.class);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security
                .when(() -> SecurityContext.getUserIdOptional(event))
                .thenReturn(Optional.of(callerId));

            when(postReadService.getFeed(
                eq(callerId),
                filtersCaptor.capture(),
                eq("abc123"),
                eq(10)
            )).thenReturn(emptyPage);

            APIGatewayV2HTTPResponse response =
                handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
        }

        PostFeedFilters filters = filtersCaptor.getValue();

        assertEquals("jazz", filters.q());
        assertEquals(List.of("music", "outdoor"), filters.tags());
    }

    @Test
    void getRecommendations_whenRequestMatchesRecommendationsPath_thenCallsRecommendationsAndNotFeed()
        throws Exception {

        UUID callerId = UUID.randomUUID();

        APIGatewayV2HTTPEvent event =
            ApiGatewayEvents.get("/posts/recommendations");

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security
                .when(() -> SecurityContext.getUserIdOptional(event))
                .thenReturn(Optional.of(callerId));

            when(postReadService.getRecommendations(
                eq(callerId),
                isNull(),
                eq(20)
            )).thenReturn(
                CursorPage.of(
                    List.of(),
                    20,
                    PostCardDto::id,
                    PostCardDto::createdAt
                )
            );

            handler.handleRequest(event, lambdaContext);

            verify(postReadService)
                .getRecommendations(callerId, null, 20);

            verify(postReadService, never())
                .getFeed(any(), any(), any(), anyInt());
        }
    }

    @Test
    void getPostById_whenRequestContainsPostIdPathParameter_thenCallsGetByIdAndNotFeed()
        throws Exception {

        UUID postId = UUID.randomUUID();

        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get(
            "/posts/" + postId,
            null,
            Map.of("id", postId.toString())
        );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security
                .when(() -> SecurityContext.getUserIdOptional(event))
                .thenReturn(Optional.empty());

            handler.handleRequest(event, lambdaContext);

            verify(postReadService)
                .getById(postId, null);

            verify(postReadService, never())
                .getFeed(any(), any(), any(), anyInt());
        }
    }

    @Test
    void getFeed_whenStartsFromIsInvalid_thenReturns400() {
        APIGatewayV2HTTPEvent event =
            ApiGatewayEvents.get(
                "/posts",
                Map.of("startsFrom", "not-a-date")
            );

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security
                .when(() -> SecurityContext.getUserIdOptional(event))
                .thenReturn(Optional.empty());

            APIGatewayV2HTTPResponse response =
                handler.handleRequest(event, lambdaContext);

            assertEquals(400, response.getStatusCode());
        }
    }

    @Test
    void handleRequest_whenServiceThrowsUnexpectedException_thenReturns500WithoutLeakingExceptionMessage()
        throws Exception {

        APIGatewayV2HTTPEvent event =
            ApiGatewayEvents.get("/posts");

        String internalDetail =
            "connection to db-primary.internal:5432 refused";

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security
                .when(() -> SecurityContext.getUserIdOptional(event))
                .thenReturn(Optional.empty());

            when(postReadService.getFeed(
                any(),
                any(),
                any(),
                anyInt()
            )).thenThrow(new RuntimeException(internalDetail));

            APIGatewayV2HTTPResponse response =
                handler.handleRequest(event, lambdaContext);

            assertEquals(500, response.getStatusCode());

            assertFalse(
                response.getBody() != null &&
                response.getBody().contains(internalDetail),
                "Response body must not echo raw internal exception text to the client."
            );
        }
    }

    @Test
    void handleRequest_whenServiceThrowsSecurityException_thenReturns401()
        throws Exception {

        APIGatewayV2HTTPEvent event =
            ApiGatewayEvents.get("/posts/recommendations");

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security
                .when(() -> SecurityContext.getUserIdOptional(event))
                .thenReturn(Optional.empty());

            when(postReadService.getRecommendations(
                isNull(),
                isNull(),
                anyInt()
            )).thenThrow(
                new SecurityException(
                    "Authentication is required for recommendations"
                )
            );

            APIGatewayV2HTTPResponse response =
                handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @ParameterizedTest(name = "\"{0}\" → HTTP {1}")
    @CsvSource({
        "Post not found: abc, 404",
        "Unknown participation type: xyz, 400"
    })
    void handleRequest_whenServiceThrowsIllegalArgumentException_thenReturnsStatusBasedOnExceptionMessage(
        String message,
        int expectedStatus
    ) throws Exception {

        APIGatewayV2HTTPEvent event =
            ApiGatewayEvents.get("/posts");

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security
                .when(() -> SecurityContext.getUserIdOptional(event))
                .thenReturn(Optional.empty());

            when(postReadService.getFeed(
                any(),
                any(),
                any(),
                anyInt()
            )).thenThrow(new IllegalArgumentException(message));

            APIGatewayV2HTTPResponse response =
                handler.handleRequest(event, lambdaContext);

            assertEquals(expectedStatus, response.getStatusCode());
        }
    }

    @Test
    void handleRequest_whenHealthCheck_returns200WithUP() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get("/actuator/health");
        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(response.getBody().contains("UP"));
    }

    @Test
    void handleRequest_whenCountryCodes_returns200WithList() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get("/dicts/country-codes");
        when(postReadService.listCountryCodes()).thenReturn(List.of(new com.coolture.common.dto.CountryCodeDto("POL")));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        org.junit.jupiter.api.Assertions.assertTrue(response.getBody().contains("POL"));
        verify(postReadService).listCountryCodes();
    }

    @Test
    void handleRequest_whenPostById_returns200WithDetail() throws Exception {
        UUID postId = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get("/posts/" + postId, null, Map.of("id", postId.toString()));

        var detail = mock(com.coolture.common.dto.PostDetailDto.class);
        when(postReadService.getById(eq(postId), any())).thenReturn(detail);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserIdOptional(event)).thenReturn(Optional.empty());

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            verify(postReadService).getById(eq(postId), any());
        }
    }

    @Test
    void handleRequest_whenMap_returns200WithMarks() throws Exception {
        Map<String, String> query = Map.of(
            "leftUpper.latitude", "54.5",
            "leftUpper.longitude", "18.0",
            "rightBottom.latitude", "54.0",
            "rightBottom.longitude", "19.0"
        );
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get("/posts/map", query);

        when(postReadService.getMapMarks(any(), any(), any())).thenReturn(List.of());

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserIdOptional(event)).thenReturn(Optional.empty());

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            verify(postReadService).getMapMarks(any(), any(), any());
        }
    }

    @Test
    void handleRequest_whenEventLocations_returns200WithPage() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get("/event-locations", Map.of("limit", "10"));

        var emptyPage = CursorPage.<com.coolture.common.dto.EventLocationDto>of(
            List.of(), 10, com.coolture.common.dto.EventLocationDto::id, com.coolture.common.dto.EventLocationDto::createdAt
        );
        when(postReadService.listEventLocations(any(), eq(10))).thenReturn(emptyPage);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        verify(postReadService).listEventLocations(any(), eq(10));
    }
}