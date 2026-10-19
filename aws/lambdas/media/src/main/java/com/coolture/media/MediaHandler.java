package com.coolture.media;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.json.JsonUtils;
import com.coolture.common.response.ApiResponse;
import com.coolture.common.security.SecurityContext;
import com.coolture.media.dto.MediaCompleteRequest;
import com.coolture.media.dto.MediaUploadInitRequest;
import com.coolture.media.dto.MediaUploadInitResponse;
import com.coolture.media.service.MediaService;

import java.util.Map;
import java.util.UUID;

public class MediaHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final MediaService service;

    public MediaHandler() {
        this(new MediaService());
    }

    public MediaHandler(MediaService service) {
        this.service = service;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext() != null && event.getRequestContext().getHttp() != null
                    ? event.getRequestContext().getHttp().getMethod()
                    : "GET";
            String path = event.getRawPath() != null ? event.getRawPath() : "";

            if ("POST".equalsIgnoreCase(method)) {
                if (path.endsWith("/uploads/init") || path.endsWith("/init")) {
                    return initUpload(event);
                } else if (path.endsWith("/complete")) {
                    return completeUpload(event);
                }
            } else if ("GET".equalsIgnoreCase(method)) {
                return getById(event);
            } else if ("DELETE".equalsIgnoreCase(method)) {
                return delete(event);
            }

            return ApiResponse.badRequest("Unsupported method or route: " + method + " " + path);

        } catch (SecurityException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not authenticated")) {
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
                context.getLogger().log("Error in MediaHandler: " + e.getMessage());
            }
            return ApiResponse.error(e.getMessage());
        }
    }

    private APIGatewayV2HTTPResponse initUpload(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        if (event.getBody() == null || event.getBody().isBlank()) {
            return ApiResponse.badRequest("Request body is required");
        }
        MediaUploadInitRequest request = JsonUtils.fromJsonAndValidate(event.getBody(), MediaUploadInitRequest.class);
        MediaUploadInitResponse response = service.initUpload(callerId, request);
        return ApiResponse.created(response);
    }

    private APIGatewayV2HTTPResponse completeUpload(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID mediaId = extractMediaId(event);
        MediaCompleteRequest request = null;
        if (event.getBody() != null && !event.getBody().isBlank()) {
            request = JsonUtils.fromJson(event.getBody(), MediaCompleteRequest.class);
        }
        MediaResourceDto response = service.completeUpload(mediaId, callerId, request);
        return ApiResponse.ok(response);
    }

    private APIGatewayV2HTTPResponse getById(APIGatewayV2HTTPEvent event) throws Exception {
        UUID mediaId = extractMediaId(event);
        MediaResourceDto response = service.getById(mediaId);
        return ApiResponse.ok(response);
    }

    private APIGatewayV2HTTPResponse delete(APIGatewayV2HTTPEvent event) throws Exception {
        UUID callerId = SecurityContext.getUserId(event);
        UUID mediaId = extractMediaId(event);
        service.delete(mediaId, callerId);
        return ApiResponse.noContent();
    }

    private UUID extractMediaId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null) {
            if (pathParams.containsKey("mediaId")) {
                return UUID.fromString(pathParams.get("mediaId"));
            }
            if (pathParams.containsKey("id")) {
                return UUID.fromString(pathParams.get("id"));
            }
        }

        String rawPath = event.getRawPath();
        if (rawPath != null) {
            String[] parts = rawPath.split("/");
            for (int i = 0; i < parts.length; i++) {
                if ("uploads".equals(parts[i]) && i + 1 < parts.length) {
                    try {
                        return UUID.fromString(parts[i + 1]);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            for (int i = parts.length - 1; i >= 0; i--) {
                try {
                    return UUID.fromString(parts[i]);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        throw new IllegalArgumentException("Missing or invalid mediaId parameter");
    }
}
