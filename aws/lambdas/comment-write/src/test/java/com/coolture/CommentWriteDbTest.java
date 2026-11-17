package com.coolture;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.dto.CommentCreateRequest;
import com.coolture.dto.CommentUpdateRequest;
import com.coolture.services.CommentWriteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Blocks on the migrated database: who may comment where. Needs `make up`; run it with `make test-db`. Its rows are removed afterwards. */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class CommentWriteDbTest {

    private static final UUID ANNA = UUID.fromString("d0000000-0000-0000-0000-000000000001");
    private static final UUID JAN = UUID.fromString("d0000000-0000-0000-0000-000000000002");
    private static final UUID OLA = UUID.fromString("d0000000-0000-0000-0000-000000000003");
    private static final UUID OLAS_POST = UUID.fromString("d0000000-0000-0000-0000-000000000100");
    private static final UUID JANS_POST = UUID.fromString("d0000000-0000-0000-0000-000000000101");
    private static final UUID JANS_COMMENT = UUID.fromString("d0000000-0000-0000-0000-000000000201");
    private static final UUID OLAS_REPLY_TO_JAN = UUID.fromString("d0000000-0000-0000-0000-000000000202");
    private static final UUID ANNAS_COMMENT_ON_JANS_POST = UUID.fromString("d0000000-0000-0000-0000-000000000203");

    private final CommentWriteService service = new CommentWriteService();

    @BeforeEach
    void addRows() throws SQLException {
        removeRows();
        execute("INSERT INTO users (id, username) VALUES ('" + ANNA + "', 'cwdb_anna'), ('" + JAN + "', 'cwdb_jan'), ('"
            + OLA + "', 'cwdb_ola')");
        execute("INSERT INTO posts (id, author_id, title, starts_at, type) VALUES ('" + OLAS_POST + "', '" + OLA
            + "', 'comments test', now() + interval '2 days', 'ONLINE'), ('" + JANS_POST + "', '" + JAN
            + "', 'jan''s post', now() + interval '2 days', 'ONLINE')");
        execute("INSERT INTO comments (id, post_id, author_id, ancestor_ids, content) VALUES ('" + JANS_COMMENT + "', '"
            + OLAS_POST + "', '" + JAN + "', '{}', 'jan'), ('" + ANNAS_COMMENT_ON_JANS_POST + "', '" + JANS_POST + "', '"
            + ANNA + "', '{}', 'anna, before the block')");
        execute("INSERT INTO comments (id, post_id, author_id, root_comment_id, parent_comment_id, ancestor_ids, content) VALUES ('"
            + OLAS_REPLY_TO_JAN + "', '" + OLAS_POST + "', '" + OLA + "', '" + JANS_COMMENT + "', '" + JANS_COMMENT + "', '{"
            + JANS_COMMENT + "}', 'ola')");
    }

    @AfterEach
    void removeRows() throws SQLException {
        execute("DELETE FROM user_relations WHERE source_user_id IN ('" + ANNA + "', '" + JAN + "')");
        // Replies point at their parents, so the deepest go first.
        for (int depth = 2; depth >= 0; depth--) {
            execute("DELETE FROM comments WHERE post_id IN ('" + OLAS_POST + "', '" + JANS_POST + "') AND COALESCE(cardinality(ancestor_ids), 0) = " + depth);
        }
        execute("DELETE FROM posts WHERE id IN ('" + OLAS_POST + "', '" + JANS_POST + "')");
        execute("DELETE FROM users WHERE id IN ('" + ANNA + "', '" + JAN + "', '" + OLA + "')");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBlockedPair_cantCommentOnTheOthersPost_orReplyUnderTheOthersComment(boolean annaBlocked) throws SQLException {
        block(annaBlocked);

        assertNotFound(() -> service.create(JANS_POST, ANNA, new CommentCreateRequest("hi", null)));
        assertNotFound(() -> service.create(OLAS_POST, ANNA, new CommentCreateRequest("hi", JANS_COMMENT)));
        assertNotFound(() -> service.create(OLAS_POST, ANNA, new CommentCreateRequest("hi", OLAS_REPLY_TO_JAN)));
        assertEquals(0, commentsBy(ANNA, OLAS_POST));

        service.create(OLAS_POST, ANNA, new CommentCreateRequest("a thread of my own", null));
        assertEquals(1, commentsBy(ANNA, OLAS_POST));
    }

    @Test
    void aBlock_keepsYourOwnCommentEditableAndDeletable() throws SQLException {
        block(false);

        service.update(ANNAS_COMMENT_ON_JANS_POST, ANNA, new CommentUpdateRequest("edited"));
        service.softDelete(ANNAS_COMMENT_ON_JANS_POST, ANNA);

        assertEquals("DELETED", queryString("SELECT status FROM comments WHERE id = '" + ANNAS_COMMENT_ON_JANS_POST + "'"));
    }

    @Test
    void someoneOutsideTheBlock_canStillReplyToEither() throws SQLException {
        block(true);

        service.create(OLAS_POST, OLA, new CommentCreateRequest("still here", JANS_COMMENT));

        assertEquals(2, commentsBy(OLA, OLAS_POST));
    }

    private void block(boolean annaBlocked) throws SQLException {
        UUID source = annaBlocked ? ANNA : JAN;
        UUID target = annaBlocked ? JAN : ANNA;
        execute("INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES ('" + source + "', '" + target + "', 'BLOCK')");
    }

    private static int commentsBy(UUID author, UUID post) throws SQLException {
        return Integer.parseInt(queryString("SELECT count(*) FROM comments WHERE author_id = '" + author + "' AND post_id = '" + post + "'"));
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        assertTrue(e.getMessage().toLowerCase().contains("not found"), e.getMessage());
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
