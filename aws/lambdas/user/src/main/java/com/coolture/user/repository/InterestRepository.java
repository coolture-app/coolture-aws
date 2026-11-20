package com.coolture.user.repository;

import com.coolture.common.db.Blocks;
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

    public record KindEntity(String key, String labelPl, String labelEn) {}

    /** Not offered: hidden from onboarding, though whoever holds it keeps it. */
    public record InterestEntity(String key, String categoryKey, String labelPl, String labelEn, boolean offered) {}

    public List<KindEntity> findKinds() throws SQLException {
        String sql = "SELECT key, label_pl, label_en FROM event_kinds ORDER BY position, key";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<KindEntity> kinds = new ArrayList<>();
            while (rs.next()) {
                kinds.add(new KindEntity(rs.getString("key"), rs.getString("label_pl"), rs.getString("label_en")));
            }
            return kinds;
        }
    }

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
        String sql = "SELECT key, category_key, label_pl, label_en, offered FROM interests ORDER BY position, key";

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<InterestEntity> interests = new ArrayList<>();
            while (rs.next()) {
                interests.add(new InterestEntity(
                        rs.getString("key"),
                        rs.getString("category_key"),
                        rs.getString("label_pl"),
                        rs.getString("label_en"),
                        rs.getBoolean("offered")
                ));
            }
            return interests;
        }
    }

    public List<String> findSelectedKeys(UUID userId) throws SQLException {
        return findKeys("SELECT interest_key FROM user_interests WHERE user_id = ? ORDER BY interest_key", userId);
    }

    public List<String> findSelectedKinds(UUID userId) throws SQLException {
        return findKeys("""
                SELECT uk.kind_key
                FROM user_kinds uk
                JOIN event_kinds k ON k.key = uk.kind_key
                WHERE uk.user_id = ?
                ORDER BY k.position, k.key
                """, userId);
    }

    private List<String> findKeys(String sql, UUID userId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
                return keys;
            }
        }
    }

    public boolean isBlockedPair(UUID userId, UUID otherUserId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection()) {
            return Blocks.between(conn, userId, otherUserId);
        }
    }

    public boolean userExists(UUID userId) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
            stmt.setObject(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void replaceSelection(UUID userId, Collection<String> kinds, Collection<String> interests) throws SQLException {
        String lockSql = "SELECT 1 FROM users WHERE id = ? FOR NO KEY UPDATE";

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
                replaceKeys(conn, "user_kinds", "kind_key", userId, kinds);
                replaceKeys(conn, "user_interests", "interest_key", userId, interests);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private static void replaceKeys(Connection conn, String table, String column, UUID userId, Collection<String> keys)
            throws SQLException {
        try (PreparedStatement delete = conn.prepareStatement("DELETE FROM " + table + " WHERE user_id = ?")) {
            delete.setObject(1, userId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO " + table + " (user_id, " + column + ") VALUES (?, ?)")) {
            for (String key : keys) {
                insert.setObject(1, userId);
                insert.setString(2, key);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
