package com.coolture.common.security;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SecurityContextTest {

    @BeforeEach
    void setUp() {
        SecurityContext.setDevFallbackOverride(null);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.setDevFallbackOverride(null);
    }

    @Test
    void getUserId_whenDevFallbackEnabledAndNoHeaders_returnsDefaultDevUserId() {
        SecurityContext.setDevFallbackOverride(true);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();

        UUID actual = SecurityContext.getUserId(event);
        assertEquals(SecurityContext.DEV_USER_ID, actual);
    }

    @Test
    void getEmail_whenDevFallbackEnabledAndNoHeaders_returnsDefaultDevEmail() {
        SecurityContext.setDevFallbackOverride(true);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();

        Optional<String> email = SecurityContext.getEmail(event);
        assertTrue(email.isPresent());
        assertEquals(SecurityContext.DEV_USER_EMAIL, email.get());
    }

    @Test
    void getUsername_whenDevFallbackEnabledAndNoHeaders_returnsDefaultDevUsername() {
        SecurityContext.setDevFallbackOverride(true);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();

        Optional<String> username = SecurityContext.getUsername(event);
        assertTrue(username.isPresent());
        assertEquals(SecurityContext.DEV_USER_NAME, username.get());
    }

    @Test
    void getClaim_whenDevFallbackEnabled_returnsAdminRole() {
        SecurityContext.setDevFallbackOverride(true);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();

        Optional<String> groups = SecurityContext.getClaim(event, "cognito:groups");
        assertTrue(groups.isPresent());
        assertEquals("COOLTURE_ADMIN", groups.get());
    }

    @Test
    void getUserId_whenDevFallbackEnabledWithCustomHeader_returnsCustomUUID() {
        SecurityContext.setDevFallbackOverride(true);
        UUID customUser = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("X-User-Id", customUser.toString()))
            .build();

        UUID actual = SecurityContext.getUserId(event);
        assertEquals(customUser, actual);
    }

    @Test
    void getUserId_whenDevFallbackEnabledAndExplicitAnonymous_throwsSecurityException() {
        SecurityContext.setDevFallbackOverride(true);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("X-Anonymous", "true"))
            .build();

        SecurityException ex = assertThrows(SecurityException.class, () -> SecurityContext.getUserId(event));
        assertTrue(ex.getMessage().contains("User not authenticated"));
    }

    @Test
    void getUserId_whenFallbackDisabledAndNoJwt_throwsSecurityException() {
        SecurityContext.setDevFallbackOverride(false);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();

        SecurityException ex = assertThrows(SecurityException.class, () -> SecurityContext.getUserId(event));
        assertTrue(ex.getMessage().contains("User not authenticated"));
    }

    @Test
    void getUserId_whenFallbackDisabled_ignoresXUserIdHeader() {
        SecurityContext.setDevFallbackOverride(false);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("X-User-Id", UUID.randomUUID().toString()))
            .build();

        SecurityException ex = assertThrows(SecurityException.class, () -> SecurityContext.getUserId(event));
        assertTrue(ex.getMessage().contains("User not authenticated"));
    }

    @Test
    void getUserId_fromJwtClaims_returnsUUID_evenWhenFallbackDisabled() {
        SecurityContext.setDevFallbackOverride(false);
        UUID expected = UUID.randomUUID();
        var jwt = APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
            .withClaims(Map.of("sub", expected.toString()))
            .build();
        var authorizer = APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
            .withJwt(jwt)
            .build();
        var requestContext = APIGatewayV2HTTPEvent.RequestContext.builder()
            .withAuthorizer(authorizer)
            .build();
        var event = APIGatewayV2HTTPEvent.builder()
            .withRequestContext(requestContext)
            .build();

        UUID actual = SecurityContext.getUserId(event);
        assertEquals(expected, actual);
    }

    @Test
    void getUserId_whenInvalidUUID_throwsSecurityException() {
        SecurityContext.setDevFallbackOverride(true);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("x-user-id", "not-a-uuid"))
            .build();

        SecurityException ex = assertThrows(SecurityException.class, () -> SecurityContext.getUserId(event));
        assertTrue(ex.getMessage().contains("Invalid 'sub' claim format"));
    }

    @Test
    void getUserIdOptional_whenPresent_returnsOptionalUUID() {
        SecurityContext.setDevFallbackOverride(true);
        UUID expected = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("x-user-id", expected.toString()))
            .build();

        Optional<UUID> opt = SecurityContext.getUserIdOptional(event);
        assertTrue(opt.isPresent());
        assertEquals(expected, opt.get());
    }

    @Test
    void getUserIdOptional_whenFallbackDisabledAndNoJwt_returnsEmpty() {
        SecurityContext.setDevFallbackOverride(false);
        APIGatewayV2HTTPEvent emptyEvent = APIGatewayV2HTTPEvent.builder().build();
        assertTrue(SecurityContext.getUserIdOptional(emptyEvent).isEmpty());
    }

    @Test
    void getAllClaims_fromJwt_returnsMap() {
        SecurityContext.setDevFallbackOverride(false);
        var jwt = APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
            .withClaims(Map.of("sub", "123", "custom", "val"))
            .build();
        var authorizer = APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
            .withJwt(jwt)
            .build();
        var requestContext = APIGatewayV2HTTPEvent.RequestContext.builder()
            .withAuthorizer(authorizer)
            .build();
        var event = APIGatewayV2HTTPEvent.builder()
            .withRequestContext(requestContext)
            .build();

        Map<String, String> claims = SecurityContext.getAllClaims(event);
        assertEquals(2, claims.size());
        assertEquals("123", claims.get("sub"));
    }

    @Test
    void requireAdmin_whenAdminGroupPresent_doesNotThrow() {
        SecurityContext.setDevFallbackOverride(false);
        var jwt = APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
            .withClaims(Map.of("sub", UUID.randomUUID().toString(), "cognito:groups", "[COOLTURE_ADMIN]"))
            .build();
        var authorizer = APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
            .withJwt(jwt)
            .build();
        var requestContext = APIGatewayV2HTTPEvent.RequestContext.builder()
            .withAuthorizer(authorizer)
            .build();
        var event = APIGatewayV2HTTPEvent.builder()
            .withRequestContext(requestContext)
            .build();

        assertDoesNotThrow(() -> SecurityContext.requireAdmin(event));
    }

    @Test
    void requireAdmin_whenNotAdmin_throwsSecurityException() {
        SecurityContext.setDevFallbackOverride(false);
        var jwt = APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.builder()
            .withClaims(Map.of("sub", UUID.randomUUID().toString(), "cognito:groups", "[USER]"))
            .build();
        var authorizer = APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
            .withJwt(jwt)
            .build();
        var requestContext = APIGatewayV2HTTPEvent.RequestContext.builder()
            .withAuthorizer(authorizer)
            .build();
        var event = APIGatewayV2HTTPEvent.builder()
            .withRequestContext(requestContext)
            .build();

        SecurityException ex = assertThrows(SecurityException.class, () -> SecurityContext.requireAdmin(event));
        assertEquals("Admin access required", ex.getMessage());
    }

    @Test
    void requireAdmin_whenDevFallbackEnabled_doesNotThrow() {
        SecurityContext.setDevFallbackOverride(true);
        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder().build();
        assertDoesNotThrow(() -> SecurityContext.requireAdmin(event));
    }

    @Test
    void getUserId_fromAuthorizationHeader_whenDevFallbackEnabled() {
        SecurityContext.setDevFallbackOverride(true);
        UUID expected = UUID.randomUUID();
        long futureExp = java.time.Instant.now().getEpochSecond() + 3600;
        String payloadJson = "{\"sub\":\"" + expected + "\",\"exp\":" + futureExp + "}";
        String encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "header." + encodedPayload + ".sig";

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", "Bearer " + token))
            .build();

        UUID actual = SecurityContext.getUserId(event);
        assertEquals(expected, actual);
    }

    @Test
    void getUserIdOptional_fromAuthorizationHeader_whenExpired_returnsEmpty() {
        SecurityContext.setDevFallbackOverride(false);
        UUID expected = UUID.randomUUID();
        long pastExp = java.time.Instant.now().getEpochSecond() - 3600;
        String payloadJson = "{\"sub\":\"" + expected + "\",\"exp\":" + pastExp + "}";
        String encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "header." + encodedPayload + ".sig";

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", "Bearer " + token))
            .build();

        Optional<UUID> opt = SecurityContext.getUserIdOptional(event);
        assertTrue(opt.isEmpty());
    }

    @Test
    void getUserIdOptional_fromUnverifiedAuthorizationHeader_whenFallbackDisabled_returnsEmpty() {
        SecurityContext.setDevFallbackOverride(false);
        long futureExp = java.time.Instant.now().getEpochSecond() + 3600;
        String payloadJson = "{\"sub\":\"" + UUID.randomUUID() + "\",\"exp\":" + futureExp + "}";
        String encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "header." + encodedPayload + ".sig";

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", "Bearer " + token))
            .build();

        assertTrue(SecurityContext.getUserIdOptional(event).isEmpty());
    }

    @Test
    void requireAdmin_fromForgedAuthorizationHeader_whenFallbackDisabled_throwsSecurityException() {
        SecurityContext.setDevFallbackOverride(false);
        long futureExp = java.time.Instant.now().getEpochSecond() + 3600;
        String payloadJson = "{\"sub\":\"" + UUID.randomUUID() + "\",\"cognito:groups\":[\"COOLTURE_ADMIN\"],\"exp\":" + futureExp + "}";
        String encodedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "header." + encodedPayload + ".sig";

        APIGatewayV2HTTPEvent event = APIGatewayV2HTTPEvent.builder()
            .withHeaders(Map.of("Authorization", "Bearer " + token))
            .build();

        SecurityException ex = assertThrows(SecurityException.class, () -> SecurityContext.requireAdmin(event));
        assertEquals("Admin access required", ex.getMessage());
    }

    @Test
    void getUserIdOptional_fromLambdaAuthorizerContext_returnsUUID() {
        SecurityContext.setDevFallbackOverride(false);
        UUID expected = UUID.randomUUID();
        APIGatewayV2HTTPEvent event = lambdaAuthorizerEvent(Map.of("sub", expected.toString(), "username", "jan", "groups", ""));

        Optional<UUID> actual = SecurityContext.getUserIdOptional(event);
        assertEquals(Optional.of(expected), actual);
        assertEquals(Optional.of("jan"), SecurityContext.getUsername(event));
        assertTrue(SecurityContext.getGroups(event).isEmpty());
    }

    @Test
    void getUserIdOptional_fromAnonymousLambdaAuthorizerContext_returnsEmpty() {
        SecurityContext.setDevFallbackOverride(false);
        APIGatewayV2HTTPEvent event = lambdaAuthorizerEvent(Map.of());

        assertTrue(SecurityContext.getUserIdOptional(event).isEmpty());
    }

    @Test
    void requireAdmin_fromLambdaAuthorizerContext_whenAdminGroupPresent_doesNotThrow() {
        SecurityContext.setDevFallbackOverride(false);
        APIGatewayV2HTTPEvent event = lambdaAuthorizerEvent(
            Map.of("sub", UUID.randomUUID().toString(), "groups", "USER,COOLTURE_ADMIN"));

        assertDoesNotThrow(() -> SecurityContext.requireAdmin(event));
    }

    private static APIGatewayV2HTTPEvent lambdaAuthorizerEvent(Map<String, Object> context) {
        var authorizer = APIGatewayV2HTTPEvent.RequestContext.Authorizer.builder()
            .withLambda(context)
            .build();
        var requestContext = APIGatewayV2HTTPEvent.RequestContext.builder()
            .withAuthorizer(authorizer)
            .build();
        return APIGatewayV2HTTPEvent.builder()
            .withRequestContext(requestContext)
            .build();
    }
}
