package com.coolture;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.dto.CommentSummaryDto;
import com.coolture.services.CommentReadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blocks on the migrated database: who sees which comments. Needs `make up`; run it with `make test-db`. Its rows are
 * removed afterwards.
 */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class CommentReadDbTest {

    private static final UUID ANNA = UUID.fromString("c0000000-0000-0000-0000-000000000001");
    private static final UUID JAN = UUID.fromString("c0000000-0000-0000-0000-000000000002");
    private static final UUID OLA = UUID.fromString("c0000000-0000-0000-0000-000000000003");
    private static final UUID OLAS_POST = UUID.fromString("c0000000-0000-0000-0000-000000000100");
    private static final UUID JANS_POST = UUID.fromString("c0000000-0000-0000-0000-000000000101");

    /** On Ola's post: Ola's root, Jan's reply to it, and Ola's reply to Jan's. */
    private static final UUID OLA_ROOT = UUID.fromString("c0000000-0000-0000-0000-000000000201");
    private static final UUID JAN_REPLY = UUID.fromString("c0000000-0000-0000-0000-000000000202");
    private static final UUID OLA_UNDER_JAN = UUID.fromString("c0000000-0000-0000-0000-000000000203");
    /** Jan's root and Ola's reply to it. */
    private static final UUID JAN_ROOT = UUID.fromString("c0000000-0000-0000-0000-000000000204");
    private static final UUID OLA_REPLY_TO_JAN = UUID.fromString("c0000000-0000-0000-0000-000000000205");
    private static final UUID OLA_OTHER_ROOT = UUID.fromString("c0000000-0000-0000-0000-000000000206");

    private final CommentReadService service = new CommentReadService();

    @BeforeEach
    void addRows() throws SQLException {
        removeRows();
        execute("INSERT INTO users (id, username) VALUES ('" + ANNA + "', 'crdb_anna'), ('" + JAN + "', 'crdb_jan'), ('"
            + OLA + "', 'crdb_ola')");
        execute("INSERT INTO posts (id, author_id, title, starts_at, type) VALUES ('" + OLAS_POST + "', '" + OLA
            + "', 'comments test', now() + interval '2 days', 'ONLINE'), ('" + JANS_POST + "', '" + JAN
            + "', 'jan''s post', now() + interval '2 days', 'ONLINE')");
        comment(OLA_ROOT, OLA, null, "{}", 1);
        comment(JAN_REPLY, JAN, OLA_ROOT, "{" + OLA_ROOT + "}", 2);
        comment(OLA_UNDER_JAN, OLA, JAN_REPLY, "{" + OLA_ROOT + "," + JAN_REPLY + "}", 3);
        comment(JAN_ROOT, JAN, null, "{}", 4);
        comment(OLA_REPLY_TO_JAN, OLA, JAN_ROOT, "{" + JAN_ROOT + "}", 5);
        comment(OLA_OTHER_ROOT, OLA, null, "{}", 6);
    }

    @AfterEach
    void removeRows() throws SQLException {
        execute("DELETE FROM user_relations WHERE source_user_id IN ('" + ANNA + "', '" + JAN + "')");
        // Replies point at their parents, so the deepest go first.
        execute("DELETE FROM comments WHERE post_id = '" + OLAS_POST + "' AND cardinality(ancestor_ids) = 2");
        execute("DELETE FROM comments WHERE post_id = '" + OLAS_POST + "' AND cardinality(ancestor_ids) = 1");
        execute("DELETE FROM comments WHERE post_id = '" + OLAS_POST + "'");
        execute("DELETE FROM posts WHERE id IN ('" + OLAS_POST + "', '" + JANS_POST + "')");
        execute("DELETE FROM users WHERE id IN ('" + ANNA + "', '" + JAN + "', '" + OLA + "')");
    }

    @Test
    void withNoBlock_everyoneSeesEveryThread() throws SQLException {
        assertEquals(List.of(OLA_OTHER_ROOT, JAN_ROOT, OLA_ROOT), roots(ANNA));
        assertEquals(List.of(JAN_REPLY), replies(ANNA, OLA_ROOT));
        assertEquals(List.of(OLA_UNDER_JAN), replies(ANNA, JAN_REPLY));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBlockedPersonsComment_takesItsWholeThreadWithIt(boolean annaBlocked) throws SQLException {
        block(annaBlocked);

        assertEquals(List.of(OLA_OTHER_ROOT, OLA_ROOT), roots(ANNA));
        assertEquals(List.of(), replies(ANNA, OLA_ROOT));
        assertNotFound(() -> replies(ANNA, JAN_REPLY));
        assertNotFound(() -> replies(ANNA, JAN_ROOT));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBlockedPersonsPost_hasNoCommentsToRead(boolean annaBlocked) throws SQLException {
        block(annaBlocked);

        assertNotFound(() -> service.list(JANS_POST, ANNA, null, null, 20));
    }

    @Test
    void aBlock_hidesNothingFromAGuest_orFromSomeoneOutsideIt() throws SQLException {
        block(true);

        assertEquals(List.of(OLA_OTHER_ROOT, JAN_ROOT, OLA_ROOT), roots(null));
        assertEquals(List.of(OLA_OTHER_ROOT, JAN_ROOT, OLA_ROOT), roots(OLA));
        assertEquals(List.of(OLA_UNDER_JAN), replies(OLA, JAN_REPLY));
    }

    @Test
    void unblocking_bringsTheThreadsBack() throws SQLException {
        block(true);
        execute("DELETE FROM user_relations WHERE source_user_id = '" + ANNA + "'");

        assertEquals(List.of(OLA_OTHER_ROOT, JAN_ROOT, OLA_ROOT), roots(ANNA));
    }

    private void block(boolean annaBlocked) throws SQLException {
        UUID source = annaBlocked ? ANNA : JAN;
        UUID target = annaBlocked ? JAN : ANNA;
        execute("INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES ('" + source + "', '" + target + "', 'BLOCK')");
    }

    private List<UUID> roots(UUID viewer) throws SQLException {
        return service.list(OLAS_POST, viewer, null, null, 20).items().stream().map(CommentSummaryDto::id).toList();
    }

    private List<UUID> replies(UUID viewer, UUID parent) throws SQLException {
        return service.list(OLAS_POST, viewer, parent, null, 20).items().stream().map(CommentSummaryDto::id).toList();
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        assertTrue(e.getMessage().toLowerCase().contains("not found"), e.getMessage());
    }

    /** Created a minute apart in order, so the newest-first order is known. */
    private static void comment(UUID id, UUID author, UUID parent, String ancestors, int minute) throws SQLException {
        String root = ancestors.equals("{}") ? "NULL" : "'" + ancestors.substring(1, 37) + "'";
        execute("INSERT INTO comments (id, post_id, author_id, root_comment_id, parent_comment_id, ancestor_ids, content, created_at)"
            + " VALUES ('" + id + "', '" + OLAS_POST + "', '" + author + "', " + root + ", "
            + (parent == null ? "NULL" : "'" + parent + "'") + ", '" + ancestors + "', 'text', now() - interval '1 hour' + interval '"
            + minute + " minutes')");
    }

    private static void execute(String sql) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
}
