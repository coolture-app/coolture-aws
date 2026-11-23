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
        return error(400, message, "Bad request");
    }

    public static APIGatewayV2HTTPResponse unauthorized(String message) {
        return error(401, message, "Unauthorized");
    }

    public static APIGatewayV2HTTPResponse forbidden(String message) {
        return error(403, message, "Forbidden");
    }

    public static APIGatewayV2HTTPResponse notFound(String message) {
        return error(404, message, "Not found");
    }

    public static APIGatewayV2HTTPResponse conflict(String message) {
        return error(409, message, "Conflict");
    }

    public static APIGatewayV2HTTPResponse error(String message) {
        return error(500, message, "Internal server error");
    }

    /** An exception's getMessage() can be null, and Map.of rejects that. */
    private static APIGatewayV2HTTPResponse error(int status, String message, String fallback) {
        return build(status, Map.of("error", message != null ? message : fallback));
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
