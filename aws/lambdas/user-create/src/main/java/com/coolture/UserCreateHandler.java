package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.json.JsonUtils;
import com.coolture.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class UserCreateHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        try {
            if (event.getBody() == null || event.getBody().isBlank()) {
                return ApiResponse.badRequest("Request body is required");
            }

            JsonNode body = JsonUtils.readTree(event.getBody());

            String username = body.has("username") ? body.get("username").asText() : null;
            String firstName = body.has("firstName") ? body.get("firstName").asText() : null;
            String lastName = body.has("lastName") ? body.get("lastName").asText() : null;
            String bio = body.has("bio") ? body.get("bio").asText() : null;

            if (username == null || username.isBlank()) {
                return ApiResponse.badRequest("username is required");
            }

            Map<String, Object> user = createUser(username, firstName, lastName, bio);
            return ApiResponse.created(user);

        } catch (Exception e) {
            context.getLogger().log("Error in UserCreateHandler: " + e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    private Map<String, Object> createUser(String username, String firstName, String lastName, String bio) throws SQLException {
        String sql = "INSERT INTO users (id, username, first_name, last_name, bio, created_at) " +
                     "VALUES (gen_random_uuid(), ?, ?, ?, ?, now()) " +
                     "RETURNING id, username, first_name, last_name, bio, created_at";

        try (Connection conn = DatabaseConfig.getConnection();
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
            throw new SQLException("Failed to create user: no rows returned");
        }
    }
}
