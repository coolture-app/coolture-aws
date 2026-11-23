package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.response.ApiResponse;
import com.coolture.common.security.SecurityContext;
import com.coolture.feed.FeedService;
import com.coolture.services.PostReadService;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class PostReadHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Pattern KIND_KEY = Pattern.compile("[a-z_]{1,64}");
    private static final int MAX_KINDS = 32;

    private final PostReadService service;
    private final FeedService feedService;

    public PostReadHandler() {
        this(new PostReadService(), new FeedService());
    }

    PostReadHandler(PostReadService service, FeedService feedService) {
        this.service = service;
        this.feedService = feedService;
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

            if (path.contains("/actuator/health") || path.endsWith("/health")) {
                return ApiResponse.ok(Map.of("status", "UP"));
            } else if (path.endsWith("/posts/map") || path.endsWith("/map")) {
                return getMapMarks(event, callerId);
            } else if (path.endsWith("/friends") && isPostDetailRequest(event)) {
                return ApiResponse.ok(service.getFriends(extractPostId(event), callerId));
            } else if (isPostDetailRequest(event)) {
                return getPostById(event, callerId);
            } else if (path.endsWith("/feed")) {
                return getHomeFeed(event, callerId);
            } else {
                return getFeed(event, callerId);
            }

        } catch (SecurityException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("authenticat")) {
                return ApiResponse.unauthorized(e.getMessage());
            }
            return ApiResponse.forbidden(e.getMessage());
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

    private APIGatewayV2HTTPResponse getHomeFeed(APIGatewayV2HTTPEvent event, UUID callerId) throws Exception {
        // The same filters as /posts, which also check the position. My Events' filters and sortBy don't apply here.
        return ApiResponse.ok(feedService.getFeed(callerId, parseFeedFilters(getQueryParams(event))));
    }

    private APIGatewayV2HTTPResponse getFeed(APIGatewayV2HTTPEvent event, UUID callerId) throws Exception {
        return ApiResponse.ok(service.getFeed(callerId, parseFeedFilters(getQueryParams(event))));
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

        boolean excludeMine = parseBoolean(qp.get("excludeMine"), "excludeMine");

        List<PostMarkDto> marks = service.getMapMarks(callerId, filters, bounds, excludeMine);
        return ApiResponse.ok(marks);
    }

    private boolean isPostDetailRequest(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        return pathParams != null && (pathParams.get("id") != null || pathParams.get("postId") != null);
    }

    private UUID extractPostId(APIGatewayV2HTTPEvent event) {
        Map<String, String> pathParams = event.getPathParameters();
        if (pathParams != null) {
            if (pathParams.get("id") != null) return UUID.fromString(pathParams.get("id"));
            if (pathParams.get("postId") != null) return UUID.fromString(pathParams.get("postId"));
        }
        throw new IllegalArgumentException("Missing post ID path parameter");
    }

    private Map<String, String> getQueryParams(APIGatewayV2HTTPEvent event) {
        return event.getQueryStringParameters() != null ? event.getQueryStringParameters() : Collections.emptyMap();
    }

    private PostFeedFilters parseFeedFilters(Map<String, String> qp) {
        String q = qp.get("q");
        UUID authorId = qp.get("authorId") != null ? UUID.fromString(qp.get("authorId")) : null;
        PostStatus status = qp.get("status") != null ? PostStatus.valueOf(qp.get("status").toUpperCase()) : null;
        Boolean followed = parseBoolean(qp.get("followed"), "followed") ? true : null;
        PostType type = qp.get("type") != null ? PostType.valueOf(qp.get("type").toUpperCase()) : null;

        Instant from = qp.get("from") != null ? Instant.parse(qp.get("from")) : null;
        Instant to = qp.get("to") != null ? Instant.parse(qp.get("to")) : null;

        Double lat = parseCoordinate(qp, "latitude", 90);
        Double lng = parseCoordinate(qp, "longitude", 180);
        requireFullLocation(lat, lng);
        Double radiusKm = parseRadius(qp.get("radiusKm"));

        List<String> partTypes = parseList(qp.get("participationTypes"));
        String reactionType = qp.get("reactionType");
        String cursor = qp.get("cursor");
        int limit = parseLimit(qp.get("limit"), 20);
        String sortBy = qp.get("sortBy");
        boolean includePast = parseBoolean(qp.get("includePast"), "includePast");
        List<String> kinds = parseKinds(qp.get("kinds"));
        boolean kids = parseBoolean(qp.get("kids"), "kids");
        UUID participantId = qp.get("participantId") != null ? UUID.fromString(qp.get("participantId")) : null;

        return new PostFeedFilters(
            q, authorId, status, followed, type,
            from, to, lat, lng, radiusKm,
            partTypes, reactionType, cursor, limit, sortBy, includePast, kinds, kids, participantId
        );
    }

    private Double parseCoordinate(Map<String, String> qp, String name, double maxAbs) {
        String raw = qp.get(name);
        if (raw == null) return null;
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        // Written so that NaN fails too.
        if (!(Math.abs(value) <= maxAbs)) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private void requireFullLocation(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new IllegalArgumentException("latitude and longitude must be sent together");
        }
    }

    private boolean parseBoolean(String raw, String name) {
        if (raw == null || raw.isBlank() || "false".equalsIgnoreCase(raw)) return false;
        if ("true".equalsIgnoreCase(raw)) return true;
        throw new IllegalArgumentException("Invalid " + name);
    }

    private MapBoundsDto parseMapBounds(Map<String, String> qp) {
        Double leftUpperLat = parseCoordinate(qp, "leftUpper.latitude", 90);
        Double leftUpperLng = parseCoordinate(qp, "leftUpper.longitude", 180);
        Double rightBottomLat = parseCoordinate(qp, "rightBottom.latitude", 90);
        Double rightBottomLng = parseCoordinate(qp, "rightBottom.longitude", 180);

        if (leftUpperLat == null || leftUpperLng == null || rightBottomLat == null || rightBottomLng == null) {
            throw new IllegalArgumentException("Map bounds (leftUpper & rightBottom coordinates) are required");
        }

        return new MapBoundsDto(
            new GeoPointDto(leftUpperLat, leftUpperLng),
            new GeoPointDto(rightBottomLat, rightBottomLng)
        );
    }

    private Double parseRadius(String raw) {
        if (raw == null) return null;
        double value;
        try {
            value = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid radiusKm");
        }
        // Written so that NaN fails too.
        if (!(value > 0 && value < Double.POSITIVE_INFINITY)) {
            throw new IllegalArgumentException("Invalid radiusKm");
        }
        return value;
    }

    /** Keys only by their form: a kind no post has matches nothing, like any filter. */
    private List<String> parseKinds(String raw) {
        List<String> kinds = parseList(raw);
        if (kinds != null && (kinds.size() > MAX_KINDS || !kinds.stream().allMatch(k -> KIND_KEY.matcher(k).matches()))) {
            throw new IllegalArgumentException("Invalid kinds");
        }
        return kinds;
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
            throw new IllegalArgumentException("Invalid limit");
        }
    }
}
