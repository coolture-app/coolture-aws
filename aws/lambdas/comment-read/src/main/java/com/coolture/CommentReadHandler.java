package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.response.ApiResponse;
import com.coolture.services.CommentReadService;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public class CommentReadHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final CommentReadService service;

    public CommentReadHandler() {
        this(new CommentReadService());
    }

    CommentReadHandler(CommentReadService service) {
        this.service = service;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext() != null
                && event.getRequestContext().getHttp() != null
                ? event.getRequestContext().getHttp().getMethod() : null;
            if (!"GET".equalsIgnoreCase(method)) {
                return ApiResponse.badRequest("Only GET method is supported by CommentReadHandler");
            }

            UUID postId = extractPostId(event);
            Map<String, String> qp = getQueryParams(event);
            UUID parentCommentId = qp.get("parentCommentId") != null && !qp.get("parentCommentId").isBlank()
                ? UUID.fromString(qp.get("parentCommentId"))
                : null;
            String cursor = qp.get("cursor");
            int limit = parseLimit(qp.get("limit"));

            CursorPage<CommentSummaryDto> page = service.list(postId, parentCommentId, cursor, limit);
            return ApiResponse.ok(page);

        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return ApiResponse.notFound(e.getMessage());
            }
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error in CommentReadHandler: " + e.getMessage());
            return ApiResponse.error("Internal server error");
        }
    }

    private UUID extractPostId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null) {
            if (pathParams.containsKey("postId")) return UUID.fromString(pathParams.get("postId"));
            if (pathParams.containsKey("id")) return UUID.fromString(pathParams.get("id"));
        }

        String rawPath = event.getRawPath();
        if (rawPath != null) {
            for (String segment : rawPath.split("/")) {
                try {
                    return UUID.fromString(segment);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        throw new IllegalArgumentException("Missing post ID path parameter");
    }

    private Map<String, String> getQueryParams(APIGatewayV2HTTPEvent event) {
        return event.getQueryStringParameters() != null ? event.getQueryStringParameters() : Collections.emptyMap();
    }

    private int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) return 20;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 20;
        }
    }
}
