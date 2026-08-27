package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.json.JsonUtils;
import com.coolture.common.response.ApiResponse;
import com.coolture.common.security.SecurityContext;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.UpdatePostRequest;
import com.coolture.services.PostWriteService;

import java.util.Map;
import java.util.UUID;

public class PostWriteHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final PostWriteService service;

    public PostWriteHandler() {
        this(new PostWriteService());
    }

    PostWriteHandler(PostWriteService service) {
        this.service = service;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext().getHttp().getMethod();
            String path = event.getRawPath() != null ? event.getRawPath() : "";
            UUID userId = SecurityContext.getUserId(event);

            if (path.contains("/event-locations")) {
                return switch (method) {
                    case "POST" -> createLocation(event);
                    case "PATCH" -> updateLocation(event);
                    case "DELETE" -> deleteLocation(event);
                    default -> ApiResponse.badRequest("Unsupported HTTP method: " + method);
                };
            }

            return switch (method) {
                case "POST" -> createPost(event, userId);
                case "PATCH" -> updatePost(event, userId);
                case "DELETE" -> deletePost(event, userId);
                default -> ApiResponse.badRequest("Unsupported HTTP method: " + method);
            };

        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return ApiResponse.notFound(e.getMessage());
            }
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error in PostWriteHandler: " + e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    private APIGatewayV2HTTPResponse createPost(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        CreatePostRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), CreatePostRequest.class);
        PostDetailDto created = service.createPost(userId, request);
        return ApiResponse.created(created);
    }

    private APIGatewayV2HTTPResponse updatePost(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID postId = extractPostId(event);
        UpdatePostRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), UpdatePostRequest.class);
        PostDetailDto updated = service.updatePost(postId, userId, request);
        return ApiResponse.ok(updated);
    }

    private APIGatewayV2HTTPResponse deletePost(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID postId = extractPostId(event);
        service.deletePost(postId, userId);
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse createLocation(APIGatewayV2HTTPEvent event) throws Exception {
        var req = JsonUtils.fromJsonAndValidate(event.getBody(), com.coolture.common.dto.EventLocationDto.class);
        var created = service.createLocation(req);
        return ApiResponse.created(created);
    }

    private APIGatewayV2HTTPResponse updateLocation(APIGatewayV2HTTPEvent event) throws Exception {
        UUID locationId = extractPostId(event);
        var req = JsonUtils.fromJsonAndValidate(event.getBody(), com.coolture.common.dto.EventLocationDto.class);
        var updated = service.updateLocation(locationId, req);
        return ApiResponse.ok(updated);
    }

    private APIGatewayV2HTTPResponse deleteLocation(APIGatewayV2HTTPEvent event) throws Exception {
        UUID locationId = extractPostId(event);
        service.deleteLocation(locationId);
        return ApiResponse.noContent();
    }

    private UUID extractPostId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null) {
            if (pathParams.containsKey("id")) {
                return UUID.fromString(pathParams.get("id"));
            }
            if (pathParams.containsKey("postId")) {
                return UUID.fromString(pathParams.get("postId"));
            }
        }

        String rawPath = event.getRawPath();
        if (rawPath != null) {
            String[] segments = rawPath.split("/");
            if (segments.length > 0) {
                String last = segments[segments.length - 1];
                return UUID.fromString(last);
            }
        }

        throw new IllegalArgumentException("Missing post ID path parameter");
    }
}