package com.coolture;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.ReactionType;
import com.coolture.repository.PostWriteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stances on the migrated database. Needs `make up`; run it with `make test-db`. Its rows are removed afterwards. */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class PostWriteDbTest {

    private static final UUID AUTHOR = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID VIEWER = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final UUID POST = UUID.fromString("b0000000-0000-0000-0000-000000000100");

    private final PostWriteRepository repository = new PostWriteRepository();

    @BeforeEach
    void addPost() throws SQLException {
        removeRows();
        execute("INSERT INTO users (id, username) VALUES ('" + AUTHOR + "', 'pwdb_author'), ('" + VIEWER + "', 'pwdb_viewer')");
        execute("INSERT INTO posts (id, author_id, title, starts_at, type) VALUES ('" + POST + "', '" + AUTHOR
            + "', 'stance test', now() + interval '2 days', 'ONLINE')");
    }

    @AfterEach
    void removeRows() throws SQLException {
        execute("DELETE FROM post_reactions WHERE post_id = '" + POST + "'");
        execute("DELETE FROM post_participations WHERE post_id = '" + POST + "'");
        execute("DELETE FROM posts WHERE id = '" + POST + "'");
        execute("DELETE FROM users WHERE id IN ('" + AUTHOR + "', '" + VIEWER + "')");
    }

    @Test
    void notInterested_isNotCounted_asAParticipant() throws SQLException {
        repository.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);
        assertEquals(0, count("participant_count"));

        repository.upsertParticipation(POST, VIEWER, ParticipationType.INTERESTED);
        assertEquals(1, count("participant_count"));

        repository.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);
        assertEquals(0, count("participant_count"));

        repository.deleteParticipation(POST, VIEWER);
        assertEquals(0, count("participant_count"));
    }

    @Test
    void aMark_takesBackTheLike() throws SQLException {
        repository.upsertReaction(POST, VIEWER, ReactionType.LIKE);
        assertEquals(1, count("positive_reaction_count"));

        repository.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);

        assertEquals(0, count("positive_reaction_count"));
        assertNull(queryString("SELECT type FROM post_reactions WHERE post_id = '" + POST + "'"));
        assertEquals("NOT_INTERESTED", stance());
    }

    @Test
    void aLike_takesBackTheMark_butNotAPositiveStance() throws SQLException {
        repository.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);
        repository.upsertReaction(POST, VIEWER, ReactionType.LIKE);

        assertEquals(1, count("positive_reaction_count"));
        assertNull(stance());

        repository.upsertParticipation(POST, VIEWER, ParticipationType.TAKES_PART);
        repository.deleteReaction(POST, VIEWER);
        repository.upsertReaction(POST, VIEWER, ReactionType.LIKE);

        assertEquals("TAKES_PART", stance());
        assertEquals(1, count("participant_count"));
    }

    @Test
    void aChangedStance_startsItsTimeAgain() throws SQLException {
        repository.upsertParticipation(POST, VIEWER, ParticipationType.INTERESTED);
        execute("UPDATE post_participations SET created_at = now() - interval '3 days' WHERE post_id = '" + POST + "'");
        Instant before = Instant.now().minusSeconds(60);

        repository.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);

        Instant createdAt = Instant.parse(queryString(
            "SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM post_participations WHERE post_id = '" + POST + "'"));
        assertTrue(createdAt.isAfter(before), "the mark kept the old stance's time: " + createdAt);
    }

    private static String stance() throws SQLException {
        return queryString("SELECT type FROM post_participations WHERE post_id = '" + POST + "' AND user_id = '" + VIEWER + "'");
    }

    private static int count(String column) throws SQLException {
        return Integer.parseInt(queryString("SELECT " + column + " FROM posts WHERE id = '" + POST + "'"));
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
}
