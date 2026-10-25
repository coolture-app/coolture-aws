package com.coolture.user;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.PaginationMeta;
import com.coolture.common.security.SecurityContext;
import com.coolture.user.dto.ProfileImageResponse;
import com.coolture.user.dto.SetProfileImageRequest;
import com.coolture.user.dto.UserProfileUpdateRequest;
import com.coolture.user.repository.UserRepository.UserEntity;
import com.coolture.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserHandlerTest {

    private final UserService service = mock(UserService.class);
    private final Context lambdaContext = mock(Context.class);
    private final UserHandler handler = new UserHandler(service);

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID FULL_ID = UUID.randomUUID();
    private static final UUID THUMBNAIL_ID = UUID.randomUUID();
    private static final String FAKE_USERNAME = "ziomek123";

    @BeforeEach
    void setUp() {
        lenient().when(lambdaContext.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    // --- Search Tests ---

    @Test
    void search_returns401_whenNotAuthenticated() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users", null, null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                    .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void search_returns200WithPage_whenAuthenticated() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users", null, null);

        var summary = new UserSummaryDto(USER_ID, FAKE_USERNAME, "Kunegunda", "Kowalska", null, Instant.now(), 0, 0);
        var page = new CursorPage<>(List.of(summary), new PaginationMeta(20, false, null));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.search(eq(CALLER_ID), eq(null), eq(null), eq(20))).thenReturn(page);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            assertTrue(response.getBody().contains(FAKE_USERNAME));
        }
    }

    @Test
    void search_passesQueryParam() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users", null, null, Map.of("q", "someone"));

        var page = new CursorPage<UserSummaryDto>(List.of(), new PaginationMeta(20, false, null));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.search(eq(CALLER_ID), eq("someone"), any(), eq(20))).thenReturn(page);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            verify(service).search(eq(CALLER_ID), eq("someone"), any(), eq(20));
        }
    }

    @Test
    void search_returns400_whenLimitExceedsMax() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users", null, null, Map.of("limit", "999"));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.search(any(), any(), any(), eq(999)))
                    .thenThrow(new IllegalArgumentException("Limit must be between 1 and 100"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(400, response.getStatusCode());
        }
    }

    // --- GetById Tests ---

    @Test
    void getById_returns200_whenUserExists() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users/" + USER_ID, null, Map.of("userId", USER_ID.toString()));
        var profile = profile(USER_ID, FAKE_USERNAME);

        when(service.getByIdForCaller(eq(USER_ID), any())).thenReturn(profile);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains(FAKE_USERNAME));
    }

    @Test
    void getById_returns404_whenUserMissing() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users/" + USER_ID, null, Map.of("userId", USER_ID.toString()));

        when(service.getByIdForCaller(eq(USER_ID), any()))
                .thenThrow(new IllegalArgumentException("User " + USER_ID + " not found"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(404, response.getStatusCode());
    }

    // --- GetByUsername Tests ---

    @Test
    void getByUsername_returns200_whenFound() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users/by-username/" + FAKE_USERNAME, null, Map.of("username", FAKE_USERNAME));
        var profile = profile(USER_ID, FAKE_USERNAME);

        when(service.getByUsernameForCaller(eq(FAKE_USERNAME), any())).thenReturn(profile);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains(FAKE_USERNAME));
    }

    @Test
    void getByUsername_returns404_whenNotFound() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users/by-username/" + FAKE_USERNAME, null, Map.of("username", FAKE_USERNAME));

        when(service.getByUsernameForCaller(eq(FAKE_USERNAME), any()))
                .thenThrow(new IllegalArgumentException("User " + FAKE_USERNAME + " not found"));

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(404, response.getStatusCode());
    }

    // --- Update Tests ---

    @Test
    void update_returns401_whenNotAuthenticated() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("PATCH", "/users/" + USER_ID, "{\"username\":\"new_name\"}", Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                    .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void update_returns200_whenUpdatingOwnProfile() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("PATCH", "/users/" + USER_ID, "{\"username\":\"new_name\"}", Map.of("userId", USER_ID.toString()));
        var updated = profile(USER_ID, "new_name");

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(USER_ID);
            when(service.update(eq(USER_ID), eq(USER_ID), any(UserProfileUpdateRequest.class))).thenReturn(updated);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            assertTrue(response.getBody().contains("new_name"));
        }
    }

    @Test
    void update_returns403_whenUpdatingAnotherUsersProfile() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("PATCH", "/users/" + USER_ID, "{\"username\":\"hacked\"}", Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.update(eq(USER_ID), eq(CALLER_ID), any()))
                    .thenThrow(new SecurityException("You can only update your own profile"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(403, response.getStatusCode());
        }
    }

    @Test
    void update_returns400_whenUsernameExceedsMaxLength() {
        String body = "{\"username\":\"" + "x".repeat(33) + "\"}";
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("PATCH", "/users/" + USER_ID, body, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(USER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(400, response.getStatusCode());
        }
    }

    // --- Profile Image Tests ---

    @Test
    void setProfileImage_returns401_whenNotAuthenticated() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "PUT", "/users/" + USER_ID + "/profile-image", validSetBody(), Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                    .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void setProfileImage_returns200_whenSettingOwnImage() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "PUT", "/users/" + USER_ID + "/profile-image", validSetBody(), Map.of("userId", USER_ID.toString()));

        var profileResponse = new ProfileImageResponse(FULL_ID, THUMBNAIL_ID, Instant.now());

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(USER_ID);
            when(service.setProfileImage(eq(USER_ID), eq(USER_ID), any(SetProfileImageRequest.class)))
                    .thenReturn(profileResponse);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            assertTrue(response.getBody().contains(FULL_ID.toString()));
            assertTrue(response.getBody().contains(THUMBNAIL_ID.toString()));
        }
    }

    @Test
    void setProfileImage_returns403_whenSettingAnotherUsersImage() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "PUT", "/users/" + USER_ID + "/profile-image", validSetBody(), Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(OTHER_USER_ID);
            when(service.setProfileImage(eq(USER_ID), eq(OTHER_USER_ID), any()))
                    .thenThrow(new SecurityException("You can only manage your own profile image"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(403, response.getStatusCode());
        }
    }

    @Test
    void setProfileImage_returns400_whenBodyMissingFields() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "PUT", "/users/" + USER_ID + "/profile-image", "{}", Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(USER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(400, response.getStatusCode());
        }
    }

    @Test
    void setProfileImage_returns404_whenMediaNotFound() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "PUT", "/users/" + USER_ID + "/profile-image", validSetBody(), Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(USER_ID);
            when(service.setProfileImage(eq(USER_ID), eq(USER_ID), any()))
                    .thenThrow(new IllegalArgumentException("Media " + FULL_ID + " not found"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(404, response.getStatusCode());
        }
    }

    @Test
    void removeProfileImage_returns401_whenNotAuthenticated() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "DELETE", "/users/" + USER_ID + "/profile-image", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                    .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void removeProfileImage_returns204_whenSuccessful() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "DELETE", "/users/" + USER_ID + "/profile-image", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(USER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
        }
    }

    @Test
    void removeProfileImage_returns403_whenRemovingAnotherUsersImage() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request(
                "DELETE", "/users/" + USER_ID + "/profile-image", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(OTHER_USER_ID);
            doThrow(new SecurityException("You can only manage your own profile image"))
                    .when(service).removeProfileImage(USER_ID, OTHER_USER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(403, response.getStatusCode());
        }
    }

    @Test
    void getMe_returns200_whenAuthenticated() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/auth/me", null, null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            security.when(() -> SecurityContext.getUsername(event)).thenReturn(java.util.Optional.of(FAKE_USERNAME));
            security.when(() -> SecurityContext.getClaim(event, "given_name")).thenReturn(java.util.Optional.of("Jan"));
            security.when(() -> SecurityContext.getClaim(event, "family_name")).thenReturn(java.util.Optional.of("Kowalski"));

            when(service.getMe(eq(CALLER_ID), eq(FAKE_USERNAME), eq("Jan"), eq("Kowalski")))
                    .thenReturn(profile(CALLER_ID, FAKE_USERNAME));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
            assertTrue(response.getBody().contains(FAKE_USERNAME));
        }
    }

    @Test
    void getMe_returns401_whenNotAuthenticated() {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/auth/me", null, null);

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event))
                    .thenThrow(new SecurityException("User not authenticated: missing 'sub' claim"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(401, response.getStatusCode());
        }
    }

    @Test
    void getFollowers_returns200() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users/" + USER_ID + "/followers", null, Map.of("userId", USER_ID.toString()));
        var page = new CursorPage<UserSummaryDto>(List.of(), new PaginationMeta(20, false, null));
        when(service.getFollowers(eq(USER_ID), any(), eq(20))).thenReturn(page);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        verify(service).getFollowers(eq(USER_ID), any(), eq(20));
    }

    @Test
    void getFollowing_returns200() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users/" + USER_ID + "/following", null, Map.of("userId", USER_ID.toString()));
        var page = new CursorPage<UserSummaryDto>(List.of(), new PaginationMeta(20, false, null));
        when(service.getFollowing(eq(USER_ID), any(), eq(20))).thenReturn(page);

        APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

        assertEquals(200, response.getStatusCode());
        verify(service).getFollowing(eq(USER_ID), any(), eq(20));
    }

    @Test
    void getBlocking_returns200_whenCallerIsSelf() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users/" + CALLER_ID + "/blocking", null, Map.of("userId", CALLER_ID.toString()));
        var page = new CursorPage<UserSummaryDto>(List.of(), new PaginationMeta(20, false, null));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.getBlocking(eq(CALLER_ID), eq(CALLER_ID), any(), eq(20))).thenReturn(page);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(200, response.getStatusCode());
        }
    }

    @Test
    void getBlocking_returns403_whenCallerIsNotUser() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("GET", "/users/" + USER_ID + "/blocking", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            when(service.getBlocking(eq(USER_ID), eq(CALLER_ID), any(), eq(20)))
                    .thenThrow(new SecurityException("You can only view your own block list"));

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(403, response.getStatusCode());
        }
    }

    @Test
    void follow_returns204_whenSuccessful() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/users/" + USER_ID + "/follow", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).follow(CALLER_ID, USER_ID);
        }
    }

    @Test
    void follow_returns409_whenConflict() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/users/" + USER_ID + "/follow", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);
            doThrow(new IllegalStateException("Already following this user")).when(service).follow(CALLER_ID, USER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(409, response.getStatusCode());
        }
    }

    @Test
    void unfollow_returns204_whenSuccessful() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("DELETE", "/users/" + USER_ID + "/follow", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).unfollow(CALLER_ID, USER_ID);
        }
    }

    @Test
    void block_returns204_whenSuccessful() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("POST", "/users/" + USER_ID + "/block", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).block(CALLER_ID, USER_ID);
        }
    }

    @Test
    void unblock_returns204_whenSuccessful() throws Exception {
        APIGatewayV2HTTPEvent event = ApiGatewayEvents.request("DELETE", "/users/" + USER_ID + "/block", null, Map.of("userId", USER_ID.toString()));

        try (MockedStatic<SecurityContext> security = mockStatic(SecurityContext.class)) {
            security.when(() -> SecurityContext.getUserId(event)).thenReturn(CALLER_ID);

            APIGatewayV2HTTPResponse response = handler.handleRequest(event, lambdaContext);

            assertEquals(204, response.getStatusCode());
            verify(service).unblock(CALLER_ID, USER_ID);
        }
    }

    private static String validSetBody() {
        return """
                {
                  "fullMediaId": "%s",
                  "thumbnailMediaId": "%s"
                }
                """.formatted(FULL_ID, THUMBNAIL_ID);
    }

    private static UserProfileDto profile(UUID id, String username) {
        return new UserProfileDto(
                id, username, "Kunegunda", "Kowalska", null,
                0, 0, "Bio", Instant.now(), false, false);
    }
}
