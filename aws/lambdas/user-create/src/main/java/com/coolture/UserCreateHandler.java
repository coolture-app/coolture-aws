package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.*;

public class UserCreateHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

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
            JsonNode body = objectMapper.readTree(event.getBody());

            String username = body.get("username").asText();
            String firstName = body.has("firstName") ? body.get("firstName").asText() : null;
            String lastName = body.has("lastName") ? body.get("lastName").asText() : null;
            String bio = body.has("bio") ? body.get("bio").asText() : null;

            if (username == null || username.isBlank()) {
                return buildResponse(400, Map.of("error", "username is required"));
            }

            Map<String, Object> user = createUser(username, firstName, lastName, bio);
            return buildResponse(201, user);

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return buildResponse(500, Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> createUser(String username, String firstName, String lastName, String bio) throws SQLException {
        String sql = "INSERT INTO users (id, username, first_name, last_name, bio, created_at) VALUES (gen_random_uuid(), ?, ?, ?, ?, now()) RETURNING id, username, first_name, last_name, bio, created_at";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, username);
            stmt.setString(2, firstName);
            stmt.setString(3, lastName);
            stmt.setString(4, bio);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> user = new HashMap<>();
                    user.put("id", rs.getString("id"));
                    user.put("username", rs.getString("username"));
                    user.put("firstName", rs.getString("first_name"));
                    user.put("lastName", rs.getString("last_name"));
                    user.put("bio", rs.getString("bio"));
                    user.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return user;
                }
            }
            throw new SQLException("Failed to create user");
        }
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
