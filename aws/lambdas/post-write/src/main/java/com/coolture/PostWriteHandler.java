package com.coolture;

import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.json.JsonUtils;
import com.coolture.common.response.ApiResponse;
import com.coolture.common.security.SecurityContext;
import com.coolture.dto.CreatePostRequest;
import com.coolture.dto.ParticipationRequest;
import com.coolture.dto.ReactionRequest;
import com.coolture.dto.UpdatePostRequest;
import com.coolture.services.PostWriteService;

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

            if (path.contains("/reaction")) {
                return switch (method) {
                    case "PUT" -> upsertReaction(event, userId);
                    case "DELETE" -> deleteReaction(event, userId);
                    default -> ApiResponse.badRequest("Unsupported HTTP method: " + method);
                };
            }

            if (path.contains("/participation")) {
                return switch (method) {
                    case "PUT" -> upsertParticipation(event, userId);
                    case "DELETE" -> deleteParticipation(event, userId);
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
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("authenticat")) {
                return ApiResponse.unauthorized(e.getMessage());
            }
            return ApiResponse.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return ApiResponse.notFound(e.getMessage());
            }
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error in PostWriteHandler: " + e.getMessage());
            return ApiResponse.error("Internal server error");
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

    private APIGatewayV2HTTPResponse upsertReaction(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID postId = extractPostId(event);
        ReactionRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), ReactionRequest.class);
        service.upsertReaction(postId, userId, request);
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse deleteReaction(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID postId = extractPostId(event);
        service.deleteReaction(postId, userId);
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse upsertParticipation(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID postId = extractPostId(event);
        ParticipationRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), ParticipationRequest.class);
        service.upsertParticipation(postId, userId, request);
        return ApiResponse.noContent();
    }

    private APIGatewayV2HTTPResponse deleteParticipation(APIGatewayV2HTTPEvent event, UUID userId) throws Exception {
        UUID postId = extractPostId(event);
        service.deleteParticipation(postId, userId);
        return ApiResponse.noContent();
    }

    private UUID extractPostId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null) {
            if (pathParams.containsKey("postId")) {
                return UUID.fromString(pathParams.get("postId"));
            }
            if (pathParams.containsKey("id")) {
                return UUID.fromString(pathParams.get("id"));
            }
        }

        String rawPath = event.getRawPath();
        if (rawPath != null) {
            String[] segments = rawPath.split("/");
            for (String segment : segments) {
                try {
                    return UUID.fromString(segment);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        throw new IllegalArgumentException("Missing post ID path parameter");
    }
}