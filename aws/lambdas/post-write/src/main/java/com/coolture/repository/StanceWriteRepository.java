package com.coolture.repository;

import com.coolture.common.db.PostQueries;
import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.ReactionType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static com.coolture.repository.Transactions.inTransaction;

/** Likes too: a like and "Nie dla mnie" take each other back in one transaction. */
public class StanceWriteRepository {

    /** A like takes back the viewer's "Nie dla mnie" on the post, which has no count. */
    public void upsertReaction(UUID postId, UUID userId, ReactionType newType) throws SQLException {
        inTransaction(conn -> {
            if (!PostQueries.isPostVisible(conn, postId, userId)) {
                throw new IllegalArgumentException("Post not found: " + postId);
            }
            if (findReaction(conn, postId, userId).isEmpty()) {
                insertReaction(conn, postId, userId, newType);
                incrementLikeCount(conn, postId);
            }
            if (findParticipation(conn, postId, userId).orElse(null) == ParticipationType.NOT_INTERESTED) {
                deleteParticipationRow(conn, postId, userId);
            }
        });
    }

    public void deleteReaction(UUID postId, UUID userId) throws SQLException {
        inTransaction(conn -> {
            if (!PostQueries.exists(conn, postId)) {
                throw new IllegalArgumentException("Post not found: " + postId);
            }
            if (findReaction(conn, postId, userId).isPresent()) {
                deleteReactionRow(conn, postId, userId);
                decrementLikeCount(conn, postId);
            }
        });
    }

    /**
     * "Nie dla mnie" isn't counted as a participant and takes back the viewer's like. After the event has ended only it
     * can be set, since interested and going mean nothing then. A changed stance restarts its time: the feed reads
     * stances up to a session's start, and a changed one must not act on the session it was changed in.
     */
    public void upsertParticipation(UUID postId, UUID userId, ParticipationType newType) throws SQLException {
        inTransaction(conn -> {
            if (!PostQueries.isPostVisible(conn, postId, userId)) {
                throw new IllegalArgumentException("Post not found: " + postId);
            }
            if (counted(newType) && PostQueries.hasEnded(conn, postId)) {
                throw new IllegalArgumentException("The event has ended");
            }
            Optional<ParticipationType> oldTypeOpt = findParticipation(conn, postId, userId);
            if (oldTypeOpt.isEmpty()) {
                insertParticipation(conn, postId, userId, newType);
            } else if (oldTypeOpt.get() != newType) {
                updateParticipation(conn, postId, userId, newType);
            }
            boolean wasCounted = oldTypeOpt.map(StanceWriteRepository::counted).orElse(false);
            int countChange = (counted(newType) ? 1 : 0) - (wasCounted ? 1 : 0);
            changeParticipantCount(conn, postId, countChange);
            if (newType == ParticipationType.NOT_INTERESTED && findReaction(conn, postId, userId).isPresent()) {
                deleteReactionRow(conn, postId, userId);
                decrementLikeCount(conn, postId);
            }
        });
    }

    public void deleteParticipation(UUID postId, UUID userId) throws SQLException {
        inTransaction(conn -> {
            if (!PostQueries.exists(conn, postId)) {
                throw new IllegalArgumentException("Post not found: " + postId);
            }
            Optional<ParticipationType> oldTypeOpt = findParticipation(conn, postId, userId);
            if (oldTypeOpt.isPresent()) {
                deleteParticipationRow(conn, postId, userId);
                changeParticipantCount(conn, postId, counted(oldTypeOpt.get()) ? -1 : 0);
            }
        });
    }

    private static boolean counted(ParticipationType type) {
        return type != ParticipationType.NOT_INTERESTED;
    }

    private Optional<ReactionType> findReaction(Connection conn, UUID postId, UUID userId) throws SQLException {
        String sql = "SELECT type FROM post_reactions WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(ReactionType.valueOf(rs.getString("type")));
                }
            }
        }
        return Optional.empty();
    }

    private void insertReaction(Connection conn, UUID postId, UUID userId, ReactionType type) throws SQLException {
        String sql = "INSERT INTO post_reactions (post_id, user_id, type, created_at) VALUES (?, ?, ?, now())";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            stmt.setString(3, type.name());
            stmt.executeUpdate();
        }
    }

    private void deleteReactionRow(Connection conn, UUID postId, UUID userId) throws SQLException {
        String sql = "DELETE FROM post_reactions WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            stmt.executeUpdate();
        }
    }

    private void incrementLikeCount(Connection conn, UUID postId) throws SQLException {
        String sql = "UPDATE posts SET positive_reaction_count = positive_reaction_count + 1 WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.executeUpdate();
        }
    }

    private void decrementLikeCount(Connection conn, UUID postId) throws SQLException {
        String sql = "UPDATE posts SET positive_reaction_count = GREATEST(0, positive_reaction_count - 1) WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.executeUpdate();
        }
    }

    private Optional<ParticipationType> findParticipation(Connection conn, UUID postId, UUID userId)
            throws SQLException {
        String sql = "SELECT type FROM post_participations WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(ParticipationType.valueOf(rs.getString("type")));
                }
            }
        }
        return Optional.empty();
    }

    private void insertParticipation(Connection conn, UUID postId, UUID userId, ParticipationType type)
            throws SQLException {
        String sql = "INSERT INTO post_participations (post_id, user_id, type, created_at) VALUES (?, ?, ?, now())";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            stmt.setString(3, type.name());
            stmt.executeUpdate();
        }
    }

    private void updateParticipation(Connection conn, UUID postId, UUID userId, ParticipationType type)
            throws SQLException {
        String sql = "UPDATE post_participations SET type = ?, created_at = now() WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, type.name());
            stmt.setObject(2, postId);
            stmt.setObject(3, userId);
            stmt.executeUpdate();
        }
    }

    private void deleteParticipationRow(Connection conn, UUID postId, UUID userId) throws SQLException {
        String sql = "DELETE FROM post_participations WHERE post_id = ? AND user_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, postId);
            stmt.setObject(2, userId);
            stmt.executeUpdate();
        }
    }

    private void changeParticipantCount(Connection conn, UUID postId, int change) throws SQLException {
        if (change == 0) {
            return;
        }
        String sql = "UPDATE posts SET participant_count = GREATEST(0, participant_count + ?) WHERE id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, change);
            stmt.setObject(2, postId);
            stmt.executeUpdate();
        }
    }
}
