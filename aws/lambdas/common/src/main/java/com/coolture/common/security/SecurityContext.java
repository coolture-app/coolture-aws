package com.coolture.common.security;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.coolture.common.json.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SecurityContext {

    public static final UUID DEV_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String DEV_USER_EMAIL = "dev@coolture.local";
    public static final String DEV_USER_NAME = "local_dev";
    public static final String DEV_USER_ROLE = "COOLTURE_ADMIN";

    private static volatile Boolean devFallbackOverride = null;

    public static void setDevFallbackOverride(Boolean override) {
        devFallbackOverride = override;
    }

    public static boolean isDevFallbackEnabled() {
        if (devFallbackOverride != null) {
            return devFallbackOverride;
        }
        String env = System.getenv("ENVIRONMENT");
        return env == null || env.isBlank() || "local".equalsIgnoreCase(env);
    }

    public static UUID getUserId(APIGatewayV2HTTPEvent event) {
        String sub = getClaim(event, "sub")
            .orElseThrow(() -> new SecurityException("User not authenticated: missing 'sub' claim"));
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Invalid 'sub' claim format (expected UUID): " + sub);
        }
    }

    public static List<String> getGroups(APIGatewayV2HTTPEvent event) {
        return getClaim(event, "cognito:groups")
            .map(raw -> Arrays.stream(raw.replaceAll("[\\[\\]\"]", "").split("[,\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList())
            .orElse(Collections.emptyList());
    }

    public static void requireAdmin(APIGatewayV2HTTPEvent event) {
        if (!getGroups(event).contains("COOLTURE_ADMIN")) {
            throw new SecurityException("Admin access required");
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
            Optional<String> lambdaClaim = getLambdaAuthorizerClaim(event.getRequestContext().getAuthorizer().getLambda(), claimName);
            if (lambdaClaim.isPresent()) {
                return lambdaClaim;
            }
        }

        // 2. Fallback: Parse claims directly from Authorization: Bearer <jwt> header
        // Supports optionally-authenticated routes where API Gateway authorizer is NONE
        if (isDevFallbackEnabled()) {
            Optional<String> headerClaim = getClaimFromAuthorizationHeader(event, claimName);
            if (headerClaim.isPresent()) {
                return headerClaim;
            }
        }

        // 3. Local development fallback. A user named by X-User-Id gets claims only from the headers, as with Cognito,
        // so they are a regular user unless X-User-Role says otherwise. Without X-User-Id it's the local_dev admin.
        if (isDevFallbackEnabled()) {
            Map<String, String> headers = event.getHeaders();
            String userId = getHeaderCaseInsensitive(headers, "x-user-id");
            if ("true".equalsIgnoreCase(getHeaderCaseInsensitive(headers, "x-anonymous")) || "anonymous".equalsIgnoreCase(userId)) {
                return Optional.empty();
            }
            Optional<String> fromHeader = Optional.ofNullable(devHeaderFor(claimName))
                .map(header -> getHeaderCaseInsensitive(headers, header))
                .filter(value -> !value.isBlank());
            boolean namedUser = userId != null && !userId.isBlank();
            return namedUser ? fromHeader : fromHeader.or(() -> Optional.ofNullable(devDefaultFor(claimName)));
        }

        return Optional.empty();
    }

    private static String devHeaderFor(String claimName) {
        return switch (claimName) {
            case "sub" -> "x-user-id";
            case "email" -> "x-user-email";
            case "cognito:username", "username" -> "x-user-name";
            case "cognito:groups" -> "x-user-role";
            default -> null;
        };
    }

    private static String devDefaultFor(String claimName) {
        return switch (claimName) {
            case "sub" -> DEV_USER_ID.toString();
            case "email" -> DEV_USER_EMAIL;
            case "cognito:username", "username" -> DEV_USER_NAME;
            case "cognito:groups" -> DEV_USER_ROLE;
            default -> null;
        };
    }

    private static Optional<String> getLambdaAuthorizerClaim(Map<String, Object> context, String claimName) {
        if (context == null || context.isEmpty()) {
            return Optional.empty();
        }
        String key = switch (claimName) {
            case "cognito:username" -> "username";
            case "cognito:groups" -> "groups";
            default -> claimName;
        };
        Object value = context.get(key);
        if (value == null || value.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.toString());
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

    private static Optional<String> getClaimFromAuthorizationHeader(APIGatewayV2HTTPEvent event, String claimName) {
        if (event == null || event.getHeaders() == null) {
            return Optional.empty();
        }
        String authHeader = getHeaderCaseInsensitive(event.getHeaders(), "authorization");
        if (authHeader == null || !authHeader.toLowerCase().startsWith("bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode jsonNode = JsonUtils.readTree(decoded);
            if (jsonNode.has("exp")) {
                long exp = jsonNode.get("exp").asLong();
                if (Instant.now().getEpochSecond() > exp) {
                    return Optional.empty();
                }
            }
            if (jsonNode.has(claimName)) {
                JsonNode val = jsonNode.get(claimName);
                if (val.isArray()) {
                    return Optional.of(val.toString());
                } else {
                    return Optional.ofNullable(val.asText());
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static Map<String, String> getAllClaimsFromAuthorizationHeader(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getHeaders() == null) {
            return Collections.emptyMap();
        }
        String authHeader = getHeaderCaseInsensitive(event.getHeaders(), "authorization");
        if (authHeader == null || !authHeader.toLowerCase().startsWith("bearer ")) {
            return Collections.emptyMap();
        }
        String token = authHeader.substring(7).trim();
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return Collections.emptyMap();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode jsonNode = JsonUtils.readTree(decoded);
            if (jsonNode.has("exp")) {
                long exp = jsonNode.get("exp").asLong();
                if (Instant.now().getEpochSecond() > exp) {
                    return Collections.emptyMap();
                }
            }
            Map<String, String> map = new HashMap<>();
            jsonNode.fields().forEachRemaining(entry -> {
                JsonNode v = entry.getValue();
                map.put(entry.getKey(), v.isArray() ? v.toString() : v.asText());
            });
            return map;
        } catch (Exception ignored) {
        }
        return Collections.emptyMap();
    }

    public static Map<String, String> getAllClaims(APIGatewayV2HTTPEvent event) {
        if (event == null) {
            return Collections.emptyMap();
        }
        if (event.getRequestContext() != null && event.getRequestContext().getAuthorizer() != null) {
            var authorizer = event.getRequestContext().getAuthorizer();
            if (authorizer != null && authorizer.getJwt() != null) {
                Map<String, String> claims = authorizer.getJwt().getClaims();
                if (claims != null && !claims.isEmpty()) {
                    return claims;
                }
            }
            if (authorizer != null && authorizer.getLambda() != null && !authorizer.getLambda().isEmpty()) {
                Map<String, String> lambdaClaims = new HashMap<>();
                authorizer.getLambda().forEach((k, v) -> {
                    if (v != null && !v.toString().isBlank()) {
                        lambdaClaims.put(k, v.toString());
                    }
                });
                return lambdaClaims;
            }
        }
        if (isDevFallbackEnabled()) {
            Map<String, String> headerClaims = getAllClaimsFromAuthorizationHeader(event);
            if (!headerClaims.isEmpty()) {
                return headerClaims;
            }
        }
        if (isDevFallbackEnabled()) {
            Map<String, String> devClaims = new HashMap<>();
            getClaim(event, "sub").ifPresent(v -> devClaims.put("sub", v));
            getClaim(event, "email").ifPresent(v -> devClaims.put("email", v));
            getClaim(event, "cognito:username").ifPresent(v -> devClaims.put("cognito:username", v));
            getClaim(event, "cognito:groups").ifPresent(v -> devClaims.put("cognito:groups", v));
            return devClaims;
        }
        return Collections.emptyMap();
    }
}
