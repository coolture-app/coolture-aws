package com.coolture.user;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.PaginationMeta;
import com.coolture.user.dto.HomeDto;
import com.coolture.user.dto.InterestCatalogDto;
import com.coolture.user.dto.ProfileImageResponse;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.dto.UserListItemDto;
import com.coolture.user.service.InterestService;
import com.coolture.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Sends handler responses through contracts/users-api.yaml, which also fails on undocumented fields. */
class ContractTest {

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
        .createForSpecificationUrl(Path.of("contracts", "users-api.yaml").toUri().toString())
        .build();

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final UserService users = mock(UserService.class);
    private final InterestService interests = mock(InterestService.class);
    private final Context context = mock(Context.class);
    private final UserHandler handler = new UserHandler(users, interests);

    @BeforeEach
    void stubLambdaLogger() {
        lenient().when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    private static MediaResourceDto avatar() {
        return new MediaResourceDto(UUID.randomUUID(), MediaPurpose.PROFILE_IMAGE, "image/png", 10L, MediaStatus.ATTACHED,
            "http://localhost/a.png", Instant.now(), null, 400, 400);
    }

    private static UserProfileDto minimalProfile(UUID id) {
        return new UserProfileDto(id, "jan", null, null, null, 0, 0, null, null, false, false, false, List.of(), List.of());
    }

    private static UserProfileDto fullProfile(UUID id) {
        return new UserProfileDto(id, "jan", "Jan", "Demo", avatar(), 1, 2, "Teatr", Instant.now(), true, false, true,
            List.of("theatre", "cinema"), List.of("stage", "cinema"));
    }

    private static CursorPage<UserListItemDto> page() {
        return new CursorPage<>(List.of(
            new UserListItemDto(new UserSummaryDto(UUID.randomUUID(), "anna", null, null, null, null, 0, 0), false),
            new UserListItemDto(new UserSummaryDto(UUID.randomUUID(), "ola", "Ola", "Demo", avatar(), Instant.now(), 3, 4), true)),
            new PaginationMeta(2, true, "abc"));
    }

    /** A signed-in caller, as API Gateway's JWT authorizer passes it. */
    private static APIGatewayV2HTTPEvent signedIn(String method, String path, String body, Map<String, String> pathParams,
                                                  Map<String, String> query) {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(method, path, body, pathParams, query);
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt = new APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT();
        jwt.setClaims(Map.of("sub", CALLER.toString(), "preferred_username", "jan"));
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer = new APIGatewayV2HTTPEvent.RequestContext.Authorizer();
        authorizer.setJwt(jwt);
        event.getRequestContext().setAuthorizer(authorizer);
        return event;
    }

    private static APIGatewayV2HTTPEvent signedIn(String method, String path, String body, UUID userId) {
        return signedIn(method, path, body, userId == null ? null : Map.of("userId", userId.toString()), Map.of());
    }

    private ValidationReport report(String path, Request.Method method, APIGatewayV2HTTPEvent event) {
        APIGatewayV2HTTPResponse response = handler.handleRequest(event, context);
        SimpleResponse.Builder built = SimpleResponse.Builder.status(response.getStatusCode());
        if (response.getBody() != null) {
            built.withContentType("application/json").withBody(response.getBody());
        }
        return VALIDATOR.validateResponse(path, method, built.build());
    }

    private static void assertMatches(ValidationReport report) {
        assertFalse(report.hasErrors(), report.toString());
    }

    @Test
    void me_withAndWithoutAHome_matches() throws Exception {
        when(users.getMe(any(), any(), any(), any())).thenReturn(fullProfile(CALLER));
        when(users.getHome(CALLER)).thenReturn(Optional.of(new HomeDto("Gdynia", new GeoPointDto(54.52, 18.54))));
        assertMatches(report("/auth/me", Request.Method.GET, signedIn("GET", "/auth/me", null, null)));

        when(users.getMe(any(), any(), any(), any())).thenReturn(minimalProfile(CALLER));
        when(users.getHome(CALLER)).thenReturn(Optional.empty());
        assertMatches(report("/auth/me", Request.Method.GET, signedIn("GET", "/auth/me", null, null)));
    }

    @Test
    void profiles_match() throws Exception {
        when(users.getByIdForCaller(eq(OTHER), any())).thenReturn(fullProfile(OTHER));
        when(users.getByUsernameForCaller(eq("jan"), any())).thenReturn(minimalProfile(OTHER));
        when(users.update(eq(CALLER), eq(CALLER), any())).thenReturn(fullProfile(CALLER));

        assertMatches(report("/users/" + OTHER, Request.Method.GET,
            ApiGatewayEvents.request("GET", "/users/" + OTHER, null, Map.of("userId", OTHER.toString()))));
        assertMatches(report("/users/by-username/jan", Request.Method.GET,
            ApiGatewayEvents.request("GET", "/users/by-username/jan", null, Map.of("username", "jan"))));
        assertMatches(report("/users/" + CALLER, Request.Method.PATCH,
            signedIn("PATCH", "/users/" + CALLER, "{\"bio\":\"Teatr\"}", CALLER)));
    }

    @Test
    void theBlockersSlimProfile_matches() throws Exception {
        when(users.getByIdForCaller(OTHER, CALLER)).thenReturn(
            new UserProfileDto(OTHER, "jan", "Jan", "Demo", avatar(), null, null, null, null, false, true, false, null, null));

        assertMatches(report("/users/" + OTHER, Request.Method.GET, signedIn("GET", "/users/" + OTHER, null, OTHER)));
    }

    @Test
    void pagesOfPeople_match() throws Exception {
        when(users.search(any(), any(), any(), anyInt())).thenReturn(page());
        when(users.getFollowers(any(), any(), any(), anyInt())).thenReturn(page());
        when(users.getFollowing(any(), any(), any(), anyInt())).thenReturn(page());
        when(users.getBlocking(any(), any(), any(), anyInt())).thenReturn(page());

        assertMatches(report("/users", Request.Method.GET,
            signedIn("GET", "/users", null, null, Map.of("q", "an", "limit", "2"))));
        for (String list : List.of("followers", "following", "blocking")) {
            String path = "/users/" + CALLER + "/" + list;
            assertMatches(report(path, Request.Method.GET, signedIn("GET", path, null, CALLER)));
        }
    }

    @Test
    void home_setAndCleared_matches() throws Exception {
        when(users.setHome(eq(CALLER), eq(CALLER), any())).thenReturn(new HomeDto("Oliwa, Gdańsk", new GeoPointDto(54.41, 18.56)));
        String path = "/users/" + CALLER + "/home";
        String body = "{\"name\":\"Oliwa, Gdańsk\",\"coordinates\":{\"latitude\":54.4112,\"longitude\":18.5601}}";

        assertMatches(report(path, Request.Method.PUT, signedIn("PUT", path, body, CALLER)));
        assertMatches(report(path, Request.Method.DELETE, signedIn("DELETE", path, null, CALLER)));
    }

    @Test
    void home_refusals_match() throws Exception {
        when(users.setHome(eq(OTHER), eq(CALLER), any())).thenThrow(new SecurityException("You can only set your own home"));
        String others = "/users/" + OTHER + "/home";
        String mine = "/users/" + CALLER + "/home";

        APIGatewayV2HTTPEvent forbidden = signedIn("PUT", others, "{\"name\":\"Gdynia\",\"coordinates\":{\"latitude\":54.5,\"longitude\":18.5}}", OTHER);
        APIGatewayV2HTTPEvent noName = signedIn("PUT", mine, "{\"name\":\" \",\"coordinates\":{\"latitude\":54.5,\"longitude\":18.5}}", CALLER);
        APIGatewayV2HTTPEvent offTheMap = signedIn("PUT", mine, "{\"name\":\"Gdynia\",\"coordinates\":{\"latitude\":91,\"longitude\":18.5}}", CALLER);

        assertEquals(403, handler.handleRequest(forbidden, context).getStatusCode());
        assertEquals(400, handler.handleRequest(noName, context).getStatusCode());
        assertEquals(400, handler.handleRequest(offTheMap, context).getStatusCode());
        assertMatches(report(others, Request.Method.PUT, forbidden));
        assertMatches(report(mine, Request.Method.PUT, noName));
    }

    @Test
    void profileImage_matches() throws Exception {
        UUID full = UUID.randomUUID();
        UUID thumbnail = UUID.randomUUID();
        when(users.setProfileImage(eq(CALLER), eq(CALLER), any())).thenReturn(new ProfileImageResponse(full, thumbnail, Instant.now()));
        String path = "/users/" + CALLER + "/profile-image";

        assertMatches(report(path, Request.Method.PUT, signedIn("PUT", path,
            "{\"fullMediaId\":\"" + full + "\",\"thumbnailMediaId\":\"" + thumbnail + "\"}", CALLER)));
        assertMatches(report(path, Request.Method.DELETE, signedIn("DELETE", path, null, CALLER)));
    }

    @Test
    void interests_match() throws Exception {
        when(interests.getCatalog()).thenReturn(new InterestCatalogDto(1, 0, 6,
            List.of(new InterestCatalogDto.Kind("stage", "Scena", "Stage")),
            List.of(new InterestCatalogDto.Category("culture", "Kultura", "Culture",
                List.of(new InterestCatalogDto.Interest("theatre", "Teatr", "Theatre"))))));
        UUID nobody = UUID.randomUUID();
        when(interests.getSelection(eq(OTHER), any())).thenReturn(new UserInterestsDto(List.of("stage"), List.of("theatre")));
        when(interests.getSelection(eq(nobody), any())).thenThrow(new IllegalArgumentException("User " + nobody + " not found"));
        when(interests.replaceSelection(eq(CALLER), eq(CALLER), any())).thenReturn(new UserInterestsDto(List.of("stage"), List.of("theatre", "cinema", "jazz")));
        String path = "/users/" + CALLER + "/interests";

        String others = "/users/" + OTHER + "/interests";
        String nobodys = "/users/" + nobody + "/interests";

        assertMatches(report("/dicts/interests", Request.Method.GET, signedIn("GET", "/dicts/interests", null, null)));
        assertMatches(report(others, Request.Method.GET, signedIn("GET", others, null, OTHER)));
        assertMatches(report(nobodys, Request.Method.GET, signedIn("GET", nobodys, null, nobody)));
        assertMatches(report(path, Request.Method.PUT, signedIn("PUT", path, "{\"kinds\":[\"stage\"],\"interests\":[\"theatre\",\"cinema\",\"jazz\"]}", CALLER)));
    }

    @Test
    void relations_andTheirConflicts_match() throws Exception {
        String follow = "/users/" + OTHER + "/follow";
        String block = "/users/" + OTHER + "/block";

        assertMatches(report(follow, Request.Method.POST, signedIn("POST", follow, null, OTHER)));
        assertMatches(report(follow, Request.Method.DELETE, signedIn("DELETE", follow, null, OTHER)));
        assertMatches(report(block, Request.Method.POST, signedIn("POST", block, null, OTHER)));
        assertMatches(report(block, Request.Method.DELETE, signedIn("DELETE", block, null, OTHER)));

        doThrow(new IllegalStateException("Already following this user")).when(users).follow(CALLER, OTHER);
        APIGatewayV2HTTPEvent again = signedIn("POST", follow, null, OTHER);
        assertEquals(409, handler.handleRequest(again, context).getStatusCode());
        assertMatches(report(follow, Request.Method.POST, again));
    }

    @Test
    void theRequestsTheContractDescribes_areAccepted_andAHomeWithoutAPlaceIsNot() {
        String home = "/users/" + CALLER + "/home";

        assertFalse(VALIDATOR.validateRequest(SimpleRequest.Builder.put(home).withContentType("application/json")
            .withBody("{\"name\":\"Banino\",\"coordinates\":{\"latitude\":54.39,\"longitude\":18.41}}").build()).hasErrors());
        assertTrue(VALIDATOR.validateRequest(SimpleRequest.Builder.put(home).withContentType("application/json")
            .withBody("{\"name\":\"Banino\"}").build()).hasErrors());
        assertTrue(VALIDATOR.validateRequest(SimpleRequest.Builder.get("/users").withQueryParam("limit", "101").build()).hasErrors());
    }

    @Test
    void anUndocumentedField_failsTheCheck() {
        String extraOnMe = "{\"id\":\"" + CALLER + "\",\"username\":\"jan\",\"followersCount\":0,\"followingCount\":0,"
            + "\"isFollowing\":false,\"isBlocked\":false,\"followsYou\":false,\"interests\":[],\"kinds\":[],\"onboarded\":true,\"email\":\"jan@example.com\"}";
        String homeOnAPublicProfile = "{\"id\":\"" + OTHER + "\",\"username\":\"jan\",\"followersCount\":0,\"followingCount\":0,"
            + "\"isFollowing\":false,\"isBlocked\":false,\"followsYou\":false,\"interests\":[],\"home\":{\"name\":\"Gdynia\",\"coordinates\":{\"latitude\":54.5,\"longitude\":18.5}}}";

        assertTrue(VALIDATOR.validateResponse("/auth/me", Request.Method.GET,
            SimpleResponse.Builder.ok().withContentType("application/json").withBody(extraOnMe).build()).hasErrors());
        assertTrue(VALIDATOR.validateResponse("/users/" + OTHER, Request.Method.GET,
            SimpleResponse.Builder.ok().withContentType("application/json").withBody(homeOnAPublicProfile).build()).hasErrors());
    }

    @Test
    void meWithoutInterests_failsTheCheck() {
        String withoutInterests = "{\"id\":\"" + CALLER + "\",\"username\":\"jan\",\"followersCount\":0,\"followingCount\":0,"
            + "\"isFollowing\":false,\"isBlocked\":false,\"followsYou\":false,\"kinds\":[],\"onboarded\":false,\"shareStances\":true}";

        assertTrue(VALIDATOR.validateResponse("/auth/me", Request.Method.GET,
            SimpleResponse.Builder.ok().withContentType("application/json").withBody(withoutInterests).build()).hasErrors());
    }

    @Test
    void aProfileOrAPersonInAListWithoutFollowsYou_failsTheCheck() {
        String profile = "{\"id\":\"" + OTHER + "\",\"username\":\"jan\",\"followersCount\":0,\"followingCount\":0,"
            + "\"isFollowing\":false,\"isBlocked\":false,\"interests\":[]}";
        String list = "{\"items\":[{\"id\":\"" + OTHER + "\",\"username\":\"jan\",\"followersCount\":0,\"followingCount\":0}],"
            + "\"page\":{\"limit\":20,\"hasMore\":false}}";
        String path = "/users/" + OTHER + "/followers";

        assertTrue(VALIDATOR.validateResponse("/users/" + OTHER, Request.Method.GET,
            SimpleResponse.Builder.ok().withContentType("application/json").withBody(profile).build()).hasErrors());
        assertTrue(VALIDATOR.validateResponse(path, Request.Method.GET,
            SimpleResponse.Builder.ok().withContentType("application/json").withBody(list).build()).hasErrors());
        assertFalse(VALIDATOR.validateResponse(path, Request.Method.GET, SimpleResponse.Builder.ok()
            .withContentType("application/json").withBody(list.replace("\"followingCount\":0}", "\"followingCount\":0,\"followsYou\":true}"))
            .build()).hasErrors());
    }
}
