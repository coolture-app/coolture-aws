package com.coolture.user.repository;

import com.coolture.common.db.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class InterestRepository {

    public record CategoryEntity(String key, String labelPl, String labelEn) {}

    public record InterestEntity(String key, String categoryKey, String labelPl, String labelEn) {}

    public List<CategoryEntity> findCategories() throws SQLException {
        String sql = "SELECT key, label_pl, label_en FROM interest_categories ORDER BY position, key";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<CategoryEntity> categories = new ArrayList<>();
            while (rs.next()) {
                categories.add(new CategoryEntity(
                        rs.getString("key"),
                        rs.getString("label_pl"),
                        rs.getString("label_en")
                ));
            }
            return categories;
        }
    }

    public List<InterestEntity> findInterests() throws SQLException {
        String sql = "SELECT key, category_key, label_pl, label_en FROM interests ORDER BY position, key";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<InterestEntity> interests = new ArrayList<>();
            while (rs.next()) {
                interests.add(new InterestEntity(
                        rs.getString("key"),
                        rs.getString("category_key"),
                        rs.getString("label_pl"),
                        rs.getString("label_en")
                ));
            }
            return interests;
        }
    }

    public List<String> findSelectedKeys(UUID userId) throws SQLException {
        String sql = "SELECT interest_key FROM user_interests WHERE user_id = ? ORDER BY interest_key";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (rs.next()) {
                    keys.add(rs.getString("interest_key"));
                }
                return keys;
            }
        }
    }

    public boolean hasSelection(UUID userId) throws SQLException {
        String sql = "SELECT 1 FROM user_interests WHERE user_id = ? LIMIT 1";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void replaceSelection(UUID userId, Collection<String> keys) throws SQLException {
        String lockSql = "SELECT 1 FROM users WHERE id = ? FOR NO KEY UPDATE";
        String deleteSql = "DELETE FROM user_interests WHERE user_id = ?";
        String insertSql = "INSERT INTO user_interests (user_id, interest_key) VALUES (?, ?)";

        try (Connection conn = DatabaseConfig.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement(lockSql)) {
                    lock.setObject(1, userId);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("User " + userId + " not found");
                        }
                    }
                }
                try (PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                    delete.setObject(1, userId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                    for (String key : keys) {
                        insert.setObject(1, userId);
                        insert.setString(2, key);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }
}
