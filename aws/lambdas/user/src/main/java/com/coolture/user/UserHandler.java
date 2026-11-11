package com.coolture.user;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.json.JsonUtils;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.response.ApiResponse;
import com.coolture.common.security.SecurityContext;
import com.coolture.user.dto.ProfileImageResponse;
import com.coolture.user.dto.SetProfileImageRequest;
import com.coolture.user.dto.UserProfileUpdateRequest;
import com.coolture.user.repository.UserRepository.UserEntity;
import com.coolture.user.service.UserService;

import java.util.Map;
import java.util.UUID;

public class UserHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final UserService service;

    public UserHandler() {
        this(new UserService());
    }

    public UserHandler(UserService service) {
        this.service = service;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext() != null && event.getRequestContext().getHttp() != null
                    ? event.getRequestContext().getHttp().getMethod()
                    : "GET";
            String path = event.getRawPath() != null ? event.getRawPath() : "";

            if (path.contains("/auth/me")) {
                if ("GET".equalsIgnoreCase(method)) {
                    return getMe(event);
                }
                return ApiResponse.badRequest("Unsupported method for /auth/me: " + method);
            }

            if (path.contains("/profile-image")) {
                return switch (method.toUpperCase()) {
                    case "PUT" -> setProfileImage(event);
                    case "DELETE" -> removeProfileImage(event);
                    default -> ApiResponse.badRequest("Unsupported method for profile-image: " + method);
                };
            }

            if (path.contains("/followers")) {
                if ("GET".equalsIgnoreCase(method)) {
                    return getFollowers(event);
                }
                return ApiResponse.badRequest("Unsupported method for followers: " + method);
            }

            if (path.contains("/following")) {
                if ("GET".equalsIgnoreCase(method)) {
                    return getFollowing(event);
                }
                return ApiResponse.badRequest("Unsupported method for following: " + method);
            }

            if (path.endsWith("/follow")) {
                return switch (method.toUpperCase()) {
                    case "POST" -> follow(event);
                    case "DELETE" -> unfollow(event);
                    default -> ApiResponse.badRequest("Unsupported method for follow: " + method);
                };
            }

            if (path.contains("/blocking")) {
                if ("GET".equalsIgnoreCase(method)) {
                    return getBlocking(event);
                }
                return ApiResponse.badRequest("Unsupported method for blocking: " + method);
            }

            if (path.endsWith("/block")) {
                return switch (method.toUpperCase()) {
                    case "POST" -> block(event);
                    case "DELETE" -> unblock(event);
                    default -> ApiResponse.badRequest("Unsupported method for block: " + method);
                };
            }

            if (path.contains("/by-username/")) {
                if ("GET".equalsIgnoreCase(method)) {
                    return getByUsername(event);
                }
                return ApiResponse.badRequest("Unsupported method for by-username: " + method);
            }

            if (isRootUsersPath(path)) {
                return switch (method.toUpperCase()) {
                    case "GET" -> searchUsers(event);
                    default -> ApiResponse.badRequest("Unsupported method for /users: " + method);
                };
            }

            return switch (method.toUpperCase()) {
                case "GET" -> getById(event);
                case "PATCH" -> update(event);
                default -> ApiResponse.badRequest("Unsupported method for user: " + method);
            };

        } catch (SecurityException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("authenticat")) {
                return ApiResponse.unauthorized(e.getMessage());
            }
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.conflict(e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return ApiResponse.notFound(e.getMessage());
            }
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            if (context != null && context.getLogger() != null) {
                context.getLogger().log("Error in UserHandler: " + e.getMessage());
            }
            return ApiResponse.error("Internal server error");
        }
    }

    private APIGatewayV2HTTPResponse searchUsers(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        String q = getQueryParam(event, "q");
        String cursor = getQueryParam(event, "cursor");
        String limitStr = getQueryParam(event, "limit");
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;

        CursorPage<UserSummaryDto> page = service.search(callerId, q, cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse getById(APIGatewayV2HTTPEvent event) throws Exception {
        UUID userId = extractUserId(event);
        UUID callerId = SecurityContext.getUserIdOptional(event).orElse(null);
        UserProfileDto profile = service.getByIdForCaller(userId, callerId);
        return ApiResponse.ok(profile);
    }

    private APIGatewayV2HTTPResponse getByUsername(APIGatewayV2HTTPEvent event) throws Exception {
        String username = extractUsername(event);
        UUID callerId = SecurityContext.getUserIdOptional(event).orElse(null);
        UserProfileDto profile = service.getByUsernameForCaller(username, callerId);
        return ApiResponse.ok(profile);
    }

    private APIGatewayV2HTTPResponse update(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID userId = extractUserId(event);
        if (event.getBody() == null || event.getBody().isBlank()) {
            return ApiResponse.badRequest("Request body is required");
        }
        UserProfileUpdateRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), UserProfileUpdateRequest.class);
        UserProfileDto updated = service.update(userId, callerId, request);
        return ApiResponse.ok(updated);
    }

    private APIGatewayV2HTTPResponse setProfileImage(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID userId = extractUserId(event);
        if (event.getBody() == null || event.getBody().isBlank()) {
            return ApiResponse.badRequest("Request body is required");
        }
        SetProfileImageRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), SetProfileImageRequest.class);
        ProfileImageResponse response = service.setProfileImage(userId, callerId, request);
        return ApiResponse.ok(response);
    }

    private APIGatewayV2HTTPResponse removeProfileImage(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID userId = extractUserId(event);
        service.removeProfileImage(userId, callerId);
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse getMe(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        String username = SecurityContext.getClaim(event, "preferred_username")
            .or(() -> SecurityContext.getUsername(event).filter(name -> !isUuid(name)))
            .or(() -> SecurityContext.getEmail(event).map(email -> email.split("@", 2)[0]))
            .orElse(null);
        String firstName = SecurityContext.getClaim(event, "given_name").orElse(null);
        String lastName = SecurityContext.getClaim(event, "family_name").orElse(null);
        UserProfileDto me = service.getMe(callerId, username, firstName, lastName);
        return ApiResponse.ok(me);
    }

    private APIGatewayV2HTTPResponse getFollowers(APIGatewayV2HTTPEvent event) throws Exception {
        UUID userId = extractUserId(event);
        String cursor = getQueryParam(event, "cursor");
        String limitStr = getQueryParam(event, "limit");
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;

        CursorPage<UserSummaryDto> page = service.getFollowers(userId, cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse getFollowing(APIGatewayV2HTTPEvent event) throws Exception {
        UUID userId = extractUserId(event);
        String cursor = getQueryParam(event, "cursor");
        String limitStr = getQueryParam(event, "limit");
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;

        CursorPage<UserSummaryDto> page = service.getFollowing(userId, cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse getBlocking(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID userId = extractUserId(event);
        String cursor = getQueryParam(event, "cursor");
        String limitStr = getQueryParam(event, "limit");
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;

        CursorPage<UserSummaryDto> page = service.getBlocking(userId, callerId, cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse follow(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID targetId = extractUserId(event);
        service.follow(callerId, targetId);
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse unfollow(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID targetId = extractUserId(event);
        service.unfollow(callerId, targetId);
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse block(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID targetId = extractUserId(event);
        service.block(callerId, targetId);
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse unblock(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID targetId = extractUserId(event);
        service.unblock(callerId, targetId);
        return ApiResponse.noContent();
    }

    private boolean isRootUsersPath(String path) {
        if (path == null) return false;
        String clean = path.replaceAll("/+$", "");
        return clean.endsWith("/users") || clean.isEmpty();
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private UUID extractUserId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null) {
            if (pathParams.containsKey("userId")) {
                return UUID.fromString(pathParams.get("userId"));
            }
            if (pathParams.containsKey("id")) {
                return UUID.fromString(pathParams.get("id"));
            }
        }

        String rawPath = event.getRawPath();
        if (rawPath != null) {
            String[] parts = rawPath.split("/");
            for (String part : parts) {
                try {
                    return UUID.fromString(part);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        throw new IllegalArgumentException("Missing or invalid userId path parameter");
    }

    private String extractUsername(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null && pathParams.containsKey("username")) {
            return pathParams.get("username");
        }

        String rawPath = event.getRawPath();
        if (rawPath != null) {
            int idx = rawPath.lastIndexOf("/by-username/");
            if (idx != -1) {
                return rawPath.substring(idx + "/by-username/".length());
            }
        }
        throw new IllegalArgumentException("Missing username path parameter");
    }

    private String getQueryParam(APIGatewayV2HTTPEvent event, String param) {
        if (event.getQueryStringParameters() != null) {
            return event.getQueryStringParameters().get(param);
        }
        return null;
    }
}
