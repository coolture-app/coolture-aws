package com.coolture.user;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.json.JsonUtils;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.response.ApiResponse;
import com.coolture.common.security.SecurityContext;
import com.coolture.user.dto.HomeDto;
import com.coolture.user.dto.ProfileImageResponse;
import com.coolture.user.dto.SetProfileImageRequest;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.dto.UserListItemDto;
import com.coolture.user.dto.UserProfileUpdateRequest;
import com.coolture.user.service.InterestService;
import com.coolture.user.service.UserService;

import java.util.Map;
import java.util.UUID;

public class UserHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final UserService service;
    private final InterestService interestService;

    public UserHandler() {
        this(new UserService(), new InterestService());
    }

    public UserHandler(UserService service, InterestService interestService) {
        this.service = service;
        this.interestService = interestService;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext() != null && event.getRequestContext().getHttp() != null
                    ? event.getRequestContext().getHttp().getMethod()
                    : "GET";
            String path = event.getRawPath() != null ? event.getRawPath() : "";

            // API Gateway passes on only the methods backend.yaml declares, one or two per path.
            boolean get = "GET".equalsIgnoreCase(method);
            boolean put = "PUT".equalsIgnoreCase(method);
            boolean post = "POST".equalsIgnoreCase(method);

            if (path.contains("/auth/me")) return getMe(event);
            // A username can be any route's last word, such as home or follow, so this comes before them.
            if (path.contains("/by-username/")) return getByUsername(event);
            if (path.endsWith("/dicts/interests")) return ApiResponse.ok(interestService.getCatalog());
            if (path.contains("/profile-image")) return put ? setProfileImage(event) : removeProfileImage(event);
            if (path.endsWith("/home")) return put ? setHome(event) : clearHome(event);
            if (path.contains("/followers")) return getFollowers(event);
            if (path.contains("/following")) return getFollowing(event);
            if (path.endsWith("/follow")) return post ? follow(event) : unfollow(event);
            if (path.contains("/blocking")) return getBlocking(event);
            if (path.endsWith("/block")) return post ? block(event) : unblock(event);
            if (path.endsWith("/interests")) return get ? getInterests(event) : replaceInterests(event);
            if (isRootUsersPath(path)) return searchUsers(event);
            return get ? getById(event) : update(event);

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

        CursorPage<UserListItemDto> page = service.search(callerId, q, cursor, limit);
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
        if (request.bio() == null && sentBioAsNull(event.getBody())) {
            // A record can't tell null from a field left out. A blank bio is what clears it.
            request = new UserProfileUpdateRequest(
                    request.username(), request.firstName(), request.lastName(), "", request.shareStances());
        }
        UserProfileDto updated = service.update(userId, callerId, request);
        return ApiResponse.ok(updated);
    }

    private static boolean sentBioAsNull(String body) {
        JsonNode tree = JsonUtils.readTree(body);
        return tree != null && tree.isObject() && tree.has("bio") && tree.get("bio").isNull();
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

    private APIGatewayV2HTTPResponse getInterests(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        return ApiResponse.ok(interestService.getSelection(extractUserId(event), callerId));
    }

    private APIGatewayV2HTTPResponse replaceInterests(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID userId = extractUserId(event);
        if (event.getBody() == null || event.getBody().isBlank()) {
            return ApiResponse.badRequest("Request body is required");
        }
        UserInterestsDto request = JsonUtils.fromJsonAndValidate(event.getBody(), UserInterestsDto.class);
        return ApiResponse.ok(interestService.replaceSelection(userId, callerId, request));
    }

    private APIGatewayV2HTTPResponse getMe(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        String username = SecurityContext.getClaim(event, "preferred_username")
            .or(() -> SecurityContext.getUsername(event).filter(name -> !isUuid(name)))
            .or(() -> SecurityContext.getEmail(event).map(email -> email.split("@", 2)[0]))
            .orElse(null);
        String firstName = SecurityContext.getClaim(event, "given_name").orElse(null);
        String lastName = SecurityContext.getClaim(event, "family_name").orElse(null);
        return ApiResponse.ok(service.getMe(callerId, username, firstName, lastName));
    }

    private APIGatewayV2HTTPResponse setHome(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID userId = extractUserId(event);
        if (event.getBody() == null || event.getBody().isBlank()) {
            return ApiResponse.badRequest("Request body is required");
        }
        HomeDto request = JsonUtils.fromJsonAndValidate(event.getBody(), HomeDto.class);
        return ApiResponse.ok(service.setHome(userId, callerId, request));
    }

    private APIGatewayV2HTTPResponse clearHome(APIGatewayV2HTTPEvent event) throws Exception {
        service.clearHome(extractUserId(event), SecurityContext.getUserId(event));
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse getFollowers(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserIdOptional(event).orElse(null);
        UUID userId = extractUserId(event);
        String cursor = getQueryParam(event, "cursor");
        String limitStr = getQueryParam(event, "limit");
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;

        CursorPage<UserListItemDto> page = service.getFollowers(
            userId, callerId, getQueryParam(event, "q"), cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse getFollowing(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserIdOptional(event).orElse(null);
        UUID userId = extractUserId(event);
        String cursor = getQueryParam(event, "cursor");
        String limitStr = getQueryParam(event, "limit");
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;

        CursorPage<UserListItemDto> page = service.getFollowing(
            userId, callerId, getQueryParam(event, "q"), cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse getBlocking(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID userId = extractUserId(event);
        String cursor = getQueryParam(event, "cursor");
        String limitStr = getQueryParam(event, "limit");
        int limit = limitStr != null ? Integer.parseInt(limitStr) : 20;

        CursorPage<UserListItemDto> page = service.getBlocking(userId, callerId, cursor, limit);
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
