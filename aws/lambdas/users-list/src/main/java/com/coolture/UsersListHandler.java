package com.coolture;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.response.ApiResponse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UsersListHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

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

            return ApiResponse.ok(response);
        } catch (Exception e) {
            context.getLogger().log("Error in UsersListHandler: " + e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    private List<Map<String, Object>> fetchUsers(int limit, int offset) throws SQLException {
        String sql = "SELECT id, username, first_name, last_name, bio, created_at " +
                     "FROM users ORDER BY created_at DESC LIMIT ? OFFSET ?";

        try (Connection conn = DatabaseConfig.getConnection();
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
        if (event == null) return defaultValue;
        try {
            Map<String, String> params = event.getQueryStringParameters();
            if (params != null && params.containsKey(key)) {
                return Integer.parseInt(params.get(key));
            }
        } catch (NumberFormatException ignored) {}
        return defaultValue;
    }
}
