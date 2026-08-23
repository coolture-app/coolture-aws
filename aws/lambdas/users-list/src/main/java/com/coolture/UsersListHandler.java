package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class UsersListHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static HikariDataSource dataSource;

    static {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(String.format("jdbc:postgresql://%s:%s/%s",
            System.getenv("DB_HOST"),
            System.getenv("DB_PORT"),
            System.getenv("DB_NAME")));
        config.setUsername(System.getenv("DB_USERNAME"));
        config.setPassword(System.getenv("DB_PASSWORD"));
        config.setMaximumPoolSize(2);
        config.setConnectionTimeout(5000);
        dataSource = new HikariDataSource(config);
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            int limit = getQueryParam(event, "limit", 10);
            int offset = getQueryParam(event, "offset", 0);

            List<Map<String, Object>> users = fetchUsers(limit, offset);
            
            Map<String, Object> response = new HashMap<>();
            response.put("users", users);
            response.put("count", users.size());
            response.put("limit", limit);
            response.put("offset", offset);

            return buildResponse(200, response);
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return buildResponse(500, Map.of("error", e.getMessage()));
        }
    }

    private List<Map<String, Object>> fetchUsers(int limit, int offset) throws SQLException {
        String sql = "SELECT id, username, first_name, last_name, bio, created_at FROM users ORDER BY created_at DESC LIMIT ? OFFSET ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, limit);
            stmt.setInt(2, offset);

            List<Map<String, Object>> users = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> user = new HashMap<>();
                    user.put("id", rs.getString("id"));
                    user.put("username", rs.getString("username"));
                    user.put("firstName", rs.getString("first_name"));
                    user.put("lastName", rs.getString("last_name"));
                    user.put("bio", rs.getString("bio"));
                    user.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    users.add(user);
                }
            }
            return users;
        }
    }

    private int getQueryParam(APIGatewayV2HTTPEvent event, String key, int defaultValue) {
        try {
            Map<String, String> params = event.getQueryStringParameters();
            if (params != null && params.containsKey(key)) {
                return Integer.parseInt(params.get(key));
            }
        } catch (NumberFormatException ignored) {}
        return defaultValue;
    }

    private APIGatewayV2HTTPResponse buildResponse(int statusCode, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(objectMapper.writeValueAsString(body))
                .build();
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(500)
                .withBody("{\"error\":\"Failed to serialize response\"}")
                .build();
        }
    }
}
