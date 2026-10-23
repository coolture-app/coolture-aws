package com.coolture.common.response;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    void ok_returns200WithBody() {
        APIGatewayV2HTTPResponse response = ApiResponse.ok(Map.of("message", "success"));
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("success"));
        assertEquals("application/json", response.getHeaders().get("Content-Type"));
    }

    @Test
    void created_returns201WithBody() {
        APIGatewayV2HTTPResponse response = ApiResponse.created(Map.of("id", "123"));
        assertEquals(201, response.getStatusCode());
        assertTrue(response.getBody().contains("123"));
    }

    @Test
    void noContent_returns204WithoutBody() {
        APIGatewayV2HTTPResponse response = ApiResponse.noContent();
        assertEquals(204, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void badRequest_returns400WithError() {
        APIGatewayV2HTTPResponse response = ApiResponse.badRequest("Invalid input");
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid input"));
    }

    @Test
    void unauthorized_returns401WithError() {
        APIGatewayV2HTTPResponse response = ApiResponse.unauthorized("Auth required");
        assertEquals(401, response.getStatusCode());
        assertTrue(response.getBody().contains("Auth required"));
    }

    @Test
    void forbidden_returns403WithError() {
        APIGatewayV2HTTPResponse response = ApiResponse.forbidden("Forbidden");
        assertEquals(403, response.getStatusCode());
        assertTrue(response.getBody().contains("Forbidden"));
    }

    @Test
    void notFound_returns404WithError() {
        APIGatewayV2HTTPResponse response = ApiResponse.notFound("Not found");
        assertEquals(404, response.getStatusCode());
        assertTrue(response.getBody().contains("Not found"));
    }

    @Test
    void conflict_returns409WithError() {
        APIGatewayV2HTTPResponse response = ApiResponse.conflict("Already exists");
        assertEquals(409, response.getStatusCode());
        assertTrue(response.getBody().contains("Already exists"));
    }

    @Test
    void error_returns500WithError() {
        APIGatewayV2HTTPResponse response = ApiResponse.error("Internal error");
        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("Internal error"));
    }

    @Test
    void build_withRawString_keepsStringAsBody() {
        APIGatewayV2HTTPResponse response = ApiResponse.build(200, "raw-text");
        assertEquals(200, response.getStatusCode());
        assertEquals("raw-text", response.getBody());
    }
}
