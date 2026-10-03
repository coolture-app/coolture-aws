package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.common.json.JsonUtils;
import com.coolture.common.response.ApiResponse;
import com.coolture.common.security.SecurityContext;
import com.coolture.dto.CommentCreateRequest;
import com.coolture.dto.CommentUpdateRequest;
import com.coolture.services.CommentWriteService;

import java.util.Map;
import java.util.UUID;

public class CommentWriteHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final CommentWriteService service;

    public CommentWriteHandler() {
        this(new CommentWriteService());
    }

    CommentWriteHandler(CommentWriteService service) {
        this.service = service;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        UUID userId;
        try {
            userId = SecurityContext.getUserId(event);
        } catch (SecurityException e) {
            return ApiResponse.unauthorized("Authentication is required");
        }

        try {
            String method = event.getRequestContext() != null
                && event.getRequestContext().getHttp() != null
                ? event.getRequestContext().getHttp().getMethod() : null;
            String path = event.getRawPath() != null ? event.getRawPath() : "";
            if (method == null) {
                return ApiResponse.badRequest("Missing HTTP method");
            }

            if ("POST".equalsIgnoreCase(method) && path.contains("/posts/")
                    && path.contains("/comments")) {
                return createComment(event, userId);
            } else if ("PATCH".equalsIgnoreCase(method) && path.contains("/comments")) {
                return updateComment(event, userId);
            } else if ("DELETE".equalsIgnoreCase(method) && path.contains("/comments")) {
                return deleteComment(event, userId);
            }
            return ApiResponse.badRequest("Unsupported method/path: " + method + " " + path);

        } catch (SecurityException e) {
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return ApiResponse.notFound(e.getMessage());
            }
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error in CommentWriteHandler: " + e.getMessage());
            return ApiResponse.error("Internal server error");
        }
    }

    private APIGatewayV2HTTPResponse createComment(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID postId = extractPostId(event);
        CommentCreateRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), CommentCreateRequest.class);
        CommentSummaryDto created = service.create(postId, userId, request);
        return ApiResponse.created(created);
    }

    private APIGatewayV2HTTPResponse updateComment(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID commentId = extractCommentId(event);
        CommentUpdateRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), CommentUpdateRequest.class);
        CommentSummaryDto updated = service.update(commentId, userId, request);
        return ApiResponse.ok(updated);
    }

    private APIGatewayV2HTTPResponse deleteComment(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID commentId = extractCommentId(event);
        service.softDelete(commentId, userId);
        return ApiResponse.noContent();
    }

    private UUID extractPostId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null && pathParams.containsKey("postId")) {
            return UUID.fromString(pathParams.get("postId"));
        }
        String rawPath = event.getRawPath();
        if (rawPath != null) {
            String[] segments = rawPath.split("/");
            for (int i = 0; i < segments.length; i++) {
                if ("posts".equals(segments[i]) && i + 1 < segments.length) {
                    return UUID.fromString(segments[i + 1]);
                }
            }
        }
        throw new IllegalArgumentException("Missing post ID path parameter");
    }

    private UUID extractCommentId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null) {
            if (pathParams.containsKey("commentId")) return UUID.fromString(pathParams.get("commentId"));
            if (pathParams.containsKey("id")) return UUID.fromString(pathParams.get("id"));
        }
        String rawPath = event.getRawPath();
        if (rawPath != null) {
            String[] segments = rawPath.split("/");
            if (segments.length > 0) {
                return UUID.fromString(segments[segments.length - 1]);
            }
        }
        throw new IllegalArgumentException("Missing comment ID path parameter");
    }
}
