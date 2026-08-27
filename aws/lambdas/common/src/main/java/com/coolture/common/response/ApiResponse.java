package com.coolture.common.response;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.json.JsonUtils;

import java.util.Map;

public class ApiResponse {

    private static final Map<String, String> DEFAULT_HEADERS = Map.of(
        "Content-Type", "application/json",
        "Access-Control-Allow-Origin", "*",
        "Access-Control-Allow-Methods", "*",
        "Access-Control-Allow-Headers", "*"
    );

    public static APIGatewayV2HTTPResponse ok(Object body) {
        return build(200, body);
    }

    public static APIGatewayV2HTTPResponse created(Object body) {
        return build(201, body);
    }

    public static APIGatewayV2HTTPResponse noContent() {
        return APIGatewayV2HTTPResponse.builder()
            .withStatusCode(204)
            .withHeaders(DEFAULT_HEADERS)
            .build();
    }

    public static APIGatewayV2HTTPResponse badRequest(String message) {
        return build(400, Map.of("error", message));
    }

    public static APIGatewayV2HTTPResponse unauthorized(String message) {
        return build(401, Map.of("error", message));
    }

    public static APIGatewayV2HTTPResponse forbidden(String message) {
        return build(403, Map.of("error", message));
    }

    public static APIGatewayV2HTTPResponse notFound(String message) {
        return build(404, Map.of("error", message));
    }

    public static APIGatewayV2HTTPResponse error(String message) {
        return build(500, Map.of("error", message));
    }

    public static APIGatewayV2HTTPResponse build(int statusCode, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(DEFAULT_HEADERS)
                .withBody(body instanceof String ? (String) body : JsonUtils.toJson(body))
                .build();
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(500)
                .withHeaders(DEFAULT_HEADERS)
                .withBody("{\"error\":\"Failed to serialize response\"}")
                .build();
        }
    }
}
