package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.response.ApiResponse;
import com.coolture.common.security.SecurityContext;
import com.coolture.services.PostReadService;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PostReadHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final PostReadService service;

    public PostReadHandler() {
        this(new PostReadService());
    }

    PostReadHandler(PostReadService service) {
        this.service = service;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            String method = event.getRequestContext().getHttp().getMethod();
            if (!"GET".equalsIgnoreCase(method)) {
                return ApiResponse.badRequest("Only GET method is supported by PostReadHandler");
            }

            String path = event.getRawPath() != null ? event.getRawPath() : "";
            UUID callerId = SecurityContext.getUserIdOptional(event).orElse(null);

            if (path.contains("/event-locations")) {
                return getEventLocations(event);
            } else if (path.endsWith("/posts/recommendations") || path.endsWith("/recommendations")) {
                return getRecommendations(event, callerId);
            } else if (path.endsWith("/posts/map") || path.endsWith("/map")) {
                return getMapMarks(event, callerId);
            } else if (isPostDetailRequest(event, path)) {
                return getPostById(event, callerId);
            } else {
                return getFeed(event, callerId);
            }

        } catch (SecurityException e) {
            return ApiResponse.unauthorized(e.getMessage());
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("not found")) {
                return ApiResponse.notFound(e.getMessage());
            }
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("Error in PostReadHandler: " + e.getMessage());
            return ApiResponse.error("Internal server error");
        }
    }

    private APIGatewayV2HTTPResponse getEventLocations(APIGatewayV2HTTPEvent event) throws Exception {
        Map<String, String> qp = getQueryParams(event);
        String cursor = qp.get("cursor");
        int limit = parseLimit(qp.get("limit"), 20);

        var page = service.listEventLocations(cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse getRecommendations(APIGatewayV2HTTPEvent event, UUID callerId) throws Exception {
        Map<String, String> qp = getQueryParams(event);
        String cursor = qp.get("cursor");
        int limit = parseLimit(qp.get("limit"), 20);

        CursorPage<PostCardDto> page = service.getRecommendations(callerId, cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse getFeed(APIGatewayV2HTTPEvent event, UUID callerId) throws Exception {
        Map<String, String> qp = getQueryParams(event);
        PostFeedFilters filters = parseFeedFilters(qp);
        String cursor = qp.get("cursor");
        int limit = parseLimit(qp.get("limit"), 20);

        CursorPage<PostCardDto> page = service.getFeed(callerId, filters, cursor, limit);
        return ApiResponse.ok(page);
    }

    private APIGatewayV2HTTPResponse getPostById(APIGatewayV2HTTPEvent event, UUID callerId) throws Exception {
        UUID postId = extractPostId(event);
        PostDetailDto detail = service.getById(postId, callerId);
        return ApiResponse.ok(detail);
    }

    private APIGatewayV2HTTPResponse getMapMarks(APIGatewayV2HTTPEvent event, UUID callerId) throws Exception {
        Map<String, String> qp = getQueryParams(event);
        PostFeedFilters filters = parseFeedFilters(qp);
        MapBoundsDto bounds = parseMapBounds(qp);

        List<PostMarkDto> marks = service.getMapMarks(callerId, filters, bounds);
        return ApiResponse.ok(marks);
    }

    private boolean isPostDetailRequest(APIGatewayV2HTTPEvent event, String path) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null && (pathParams.containsKey("id") || pathParams.containsKey("postId"))) {
            return true;
        }

        String[] segments = path.split("/");
        if (segments.length > 0) {
            String last = segments[segments.length - 1];
            try {
                UUID.fromString(last);
                return true;
            } catch (IllegalArgumentException ignored) {}
        }
        return false;
    }

    private UUID extractPostId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null) {
            if (pathParams.containsKey("id")) return UUID.fromString(pathParams.get("id"));
            if (pathParams.containsKey("postId")) return UUID.fromString(pathParams.get("postId"));
        }

        String rawPath = event.getRawPath();
        if (rawPath != null) {
            String[] segments = rawPath.split("/");
            if (segments.length > 0) {
                return UUID.fromString(segments[segments.length - 1]);
            }
        }
        throw new IllegalArgumentException("Missing post ID path parameter");
    }

    private Map<String, String> getQueryParams(APIGatewayV2HTTPEvent event) {
        return event.getQueryStringParameters() != null ? event.getQueryStringParameters() : Collections.emptyMap();
    }

    private PostFeedFilters parseFeedFilters(Map<String, String> qp) {
        String q = qp.get("q");
        List<String> tags = parseList(qp.get("tags"));
        UUID authorId = qp.get("authorId") != null ? UUID.fromString(qp.get("authorId")) : null;
        PostStatus status = qp.get("status") != null ? PostStatus.valueOf(qp.get("status").toUpperCase()) : null;
        PostVisibility visibility = qp.get("visibility") != null ? PostVisibility.valueOf(qp.get("visibility").toUpperCase()) : null;
        PostType type = qp.get("type") != null ? PostType.valueOf(qp.get("type").toUpperCase()) : null;

        Instant startsFrom = qp.get("startsFrom") != null ? Instant.parse(qp.get("startsFrom")) : null;
        Instant startsTo = qp.get("startsTo") != null ? Instant.parse(qp.get("startsTo")) : null;

        Double lat = qp.get("latitude") != null ? Double.parseDouble(qp.get("latitude")) : null;
        Double lng = qp.get("longitude") != null ? Double.parseDouble(qp.get("longitude")) : null;
        Double radiusKm = qp.get("radiusKm") != null ? Double.parseDouble(qp.get("radiusKm")) : null;

        List<String> partTypes = parseList(qp.get("participationTypes"));
        String reactionType = qp.get("reactionType");
        String cursor = qp.get("cursor");
        int limit = parseLimit(qp.get("limit"), 20);
        String sortBy = qp.get("sortBy");

        return new PostFeedFilters(
            q, tags, authorId, status, visibility, type,
            startsFrom, startsTo, lat, lng, radiusKm,
            partTypes, reactionType, cursor, limit, sortBy
        );
    }

    private MapBoundsDto parseMapBounds(Map<String, String> qp) {
        Double leftUpperLat = getFirstDouble(qp, "leftUpper.latitude", "leftUpperLat", "maxLat");
        Double leftUpperLng = getFirstDouble(qp, "leftUpper.longitude", "leftUpperLng", "minLng");
        Double rightBottomLat = getFirstDouble(qp, "rightBottom.latitude", "rightBottomLat", "minLat");
        Double rightBottomLng = getFirstDouble(qp, "rightBottom.longitude", "rightBottomLng", "maxLng");

        if (leftUpperLat == null || leftUpperLng == null || rightBottomLat == null || rightBottomLng == null) {
            throw new IllegalArgumentException("Map bounds (leftUpper & rightBottom coordinates) are required");
        }

        return new MapBoundsDto(
            new GeoPointDto(leftUpperLat, leftUpperLng),
            new GeoPointDto(rightBottomLat, rightBottomLng)
        );
    }

    private Double getFirstDouble(Map<String, String> map, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k) && map.get(k) != null && !map.get(k).isBlank()) {
                return Double.parseDouble(map.get(k));
            }
        }
        return null;
    }

    private List<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private int parseLimit(String raw, int defaultLimit) {
        if (raw == null || raw.isBlank()) return defaultLimit;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return defaultLimit;
        }
    }
}
