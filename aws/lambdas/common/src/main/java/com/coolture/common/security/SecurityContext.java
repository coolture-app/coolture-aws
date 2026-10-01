package com.coolture.common.security;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SecurityContext {

    public static UUID getUserId(APIGatewayV2HTTPEvent event) {
        String sub = getClaim(event, "sub")
            .orElseThrow(() -> new SecurityException("User not authenticated: missing 'sub' claim"));
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Invalid 'sub' claim format (expected UUID): " + sub);
        }
    }

    public static Optional<UUID> getUserIdOptional(APIGatewayV2HTTPEvent event) {
        return getClaim(event, "sub").flatMap(sub -> {
            try {
                return Optional.of(UUID.fromString(sub));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }

    public static Optional<String> getEmail(APIGatewayV2HTTPEvent event) {
        return getClaim(event, "email");
    }

    public static Optional<String> getUsername(APIGatewayV2HTTPEvent event) {
        return getClaim(event, "cognito:username")
            .or(() -> getClaim(event, "username"));
    }

    public static Optional<String> getClaim(APIGatewayV2HTTPEvent event, String claimName) {
        if (event == null) {
            return Optional.empty();
        }

        // 1. Check Cognito JWT authorizer claims (standard AWS Cloud path)
        if (event.getRequestContext() != null && event.getRequestContext().getAuthorizer() != null) {
            var jwt = event.getRequestContext().getAuthorizer().getJwt();
            if (jwt != null && jwt.getClaims() != null && jwt.getClaims().containsKey(claimName)) {
                return Optional.ofNullable(jwt.getClaims().get(claimName));
            }
        }

        // 2. Fallback for local testing (via X-User-Id / X-User-Email headers)
        if (event.getHeaders() != null) {
            if ("sub".equals(claimName)) {
                String val = getHeaderCaseInsensitive(event.getHeaders(), "x-user-id");
                if (val != null && !val.isBlank()) return Optional.of(val);
            } else if ("email".equals(claimName)) {
                String val = getHeaderCaseInsensitive(event.getHeaders(), "x-user-email");
                if (val != null && !val.isBlank()) return Optional.of(val);
            } else if ("cognito:username".equals(claimName) || "username".equals(claimName)) {
                String val = getHeaderCaseInsensitive(event.getHeaders(), "x-user-name");
                if (val != null && !val.isBlank()) return Optional.of(val);
            }
        }

        return Optional.empty();
    }

    private static String getHeaderCaseInsensitive(Map<String, String> headers, String targetKey) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(targetKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static Map<String, String> getAllClaims(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getRequestContext() == null) {
            return Collections.emptyMap();
        }
        var authorizer = event.getRequestContext().getAuthorizer();
        if (authorizer == null || authorizer.getJwt() == null) {
            return Collections.emptyMap();
        }
        Map<String, String> claims = authorizer.getJwt().getClaims();
        return claims != null ? claims : Collections.emptyMap();
    }
}
