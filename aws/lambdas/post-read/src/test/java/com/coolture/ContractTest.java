package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.common.dto.enums.ReactionType;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.PaginationMeta;
import com.coolture.feed.FeedMode;
import com.coolture.feed.FeedPage;
import com.coolture.feed.FeedService;
import com.coolture.services.PostReadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sends handler responses through contracts/feed-api.yaml, which also fails on undocumented fields. */
class ContractTest {

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
        .createForSpecificationUrl(Path.of("contracts", "feed-api.yaml").toUri().toString())
        .build();

    private final PostReadService posts = mock(PostReadService.class);
    private final FeedService feed = mock(FeedService.class);
    private final Context context = mock(Context.class);
    private final PostReadHandler handler = new PostReadHandler(posts, feed);

    @BeforeEach
    void stubLambdaLogger() {
        lenient().when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    private static PostCardDto minimalCard() {
        return new PostCardDto(
            UUID.randomUUID(),
            new UserSummaryDto(UUID.randomUUID(), "anna", null, null, null, Instant.now(), 0, 0),
            null, "Koncert", null, null, Instant.now(), null, List.of(),
            0, 0, 0, 0, PostType.ONLINE, PostStatus.ACTIVE, PostVisibility.PUBLIC,
            Instant.now(), null, null, null, null, null, null
        );
    }

    private static PostCardDto fullCard() {
        Instant now = Instant.now();
        MediaResourceDto avatar = new MediaResourceDto(UUID.randomUUID(), MediaPurpose.PROFILE_IMAGE_THUMBNAIL, "image/png", 10L,
            MediaStatus.ATTACHED, "http://localhost/a.png", now, null);
        MediaResourceDto cover = new MediaResourceDto(UUID.randomUUID(), MediaPurpose.EVENT_MEDIA, "image/png", 10L,
            MediaStatus.ATTACHED, "http://localhost/c.png", now, null, 1080, 1350);
        EventLocationDto location = new EventLocationDto(UUID.randomUUID(), "POL", "Hala", "1", "Długa", "80-001", "Gdańsk",
            new GeoPointDto(54.35, 18.65), null);
        return new PostCardDto(
            UUID.randomUUID(),
            new UserSummaryDto(UUID.randomUUID(), "anna", "Anna", "Nowak", avatar, now, 1, 2),
            location, "Koncert", "Opis", "https://example.com", now, now.plusSeconds(60), List.of("jazz"),
            1, 2, 3, 4, PostType.OFFLINE, PostStatus.EDITED, PostVisibility.FRIENDS,
            now, now, null, cover, ReactionType.LIKE, ParticipationType.TAKES_PART, List.of(cover)
        );
    }

    private ValidationReport responseReport(String path, APIGatewayV2HTTPEvent event) {
        APIGatewayV2HTTPResponse response = handler.handleRequest(event, context);
        return VALIDATOR.validateResponse(path, Request.Method.GET, SimpleResponse.Builder
            .status(response.getStatusCode())
            .withContentType("application/json")
            .withBody(response.getBody())
            .build());
    }

    private static void assertMatches(ValidationReport report) {
        assertFalse(report.hasErrors(), report.toString());
    }

    @Test
    void feed_firstPageWithMore_matches() throws Exception {
        when(feed.getFeed(any(), any(), anyInt(), any(), any(), any()))
            .thenReturn(new FeedPage(FeedMode.RANKED, List.of(minimalCard(), fullCard()), new PaginationMeta(2, true, "abc")));

        assertMatches(responseReport("/feed", ApiGatewayEvents.get("/feed", Map.of("limit", "2", "latitude", "54.35", "longitude", "18.65"))));
    }

    @Test
    void feed_laterPageInUpcomingMode_matches() throws Exception {
        when(feed.getFeed(any(), any(), anyInt(), any(), any(), any()))
            .thenReturn(new FeedPage(FeedMode.UPCOMING, List.of(), new PaginationMeta(20, false, null)));

        assertMatches(responseReport("/feed", ApiGatewayEvents.get("/feed", Map.of("cursor", "abc"))));
    }

    @Test
    void posts_firstPageWithMore_matches() throws Exception {
        when(posts.getFeed(any(), any(), any(), anyInt()))
            .thenReturn(new CursorPage<>(List.of(minimalCard(), fullCard()), new PaginationMeta(2, true, "abc")));

        assertMatches(responseReport("/posts", ApiGatewayEvents.get("/posts", Map.of("limit", "2"))));
    }

    @Test
    void posts_lastPageWithCursor_matches() throws Exception {
        when(posts.getFeed(any(), any(), any(), anyInt()))
            .thenReturn(new CursorPage<>(List.of(minimalCard()), new PaginationMeta(20, false, null)));

        assertMatches(responseReport("/posts", ApiGatewayEvents.get("/posts", Map.of("cursor", "abc", "sortBy", "POPULAR"))));
    }

    @Test
    void postMap_matches() throws Exception {
        when(posts.getMapMarks(any(), any(), any(), anyBoolean())).thenReturn(List.of(
            new PostMarkDto(UUID.randomUUID(), "Koncert", "Opis", "http://localhost/c.png", 3, new GeoPointDto(54.35, 18.65)),
            new PostMarkDto(UUID.randomUUID(), "Koncert", null, null, 0, new GeoPointDto(54.35, 18.65))));

        assertMatches(responseReport("/posts/map", ApiGatewayEvents.get("/posts/map", Map.of(
            "leftUpper.latitude", "54.5", "leftUpper.longitude", "18.0",
            "rightBottom.latitude", "54.0", "rightBottom.longitude", "19.0"))));
    }

    @Test
    void errorResponse_matches() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.get("/posts", Map.of("limit", "abc"));

        assertEquals(400, handler.handleRequest(event, context).getStatusCode());
        assertMatches(responseReport("/posts", event));
    }

    @Test
    void theRequestsTheContractDescribes_areAccepted() {
        assertFalse(VALIDATOR.validateRequest(SimpleRequest.Builder.get("/feed")
            .withQueryParam("limit", "20").withQueryParam("latitude", "54.35").withQueryParam("longitude", "18.65")
            .build()).hasErrors());
        assertFalse(VALIDATOR.validateRequest(SimpleRequest.Builder.get("/posts")
            .withQueryParam("sortBy", "UPCOMING").withQueryParam("includePast", "true")
            .build()).hasErrors());
        assertTrue(VALIDATOR.validateRequest(SimpleRequest.Builder.get("/posts")
            .withQueryParam("sortBy", "RECOMMENDED")
            .build()).hasErrors());
    }

    private static ValidationReport bodyReport(String path, String body) {
        return VALIDATOR.validateResponse(path, Request.Method.GET,
            SimpleResponse.Builder.ok().withContentType("application/json").withBody(body).build());
    }

    @Test
    void anUndocumentedField_failsTheCheck() {
        String extraOnPage = "{\"mode\":\"RANKED\",\"items\":[],\"page\":{\"limit\":20,\"hasMore\":false},\"extra\":1}";
        String extraInPageInfo = "{\"items\":[],\"page\":{\"limit\":20,\"hasMore\":false,\"total\":3}}";

        assertTrue(bodyReport("/feed", extraOnPage).hasErrors());
        assertTrue(bodyReport("/posts", extraInPageInfo).hasErrors());
    }

    @Test
    void aMissingModeOrUnknownMode_failsTheCheck() {
        assertTrue(bodyReport("/feed", "{\"items\":[],\"page\":{\"limit\":20,\"hasMore\":false}}").hasErrors());
        assertTrue(bodyReport("/feed", "{\"mode\":\"X\",\"items\":[],\"page\":{\"limit\":20,\"hasMore\":false}}").hasErrors());
    }
}
