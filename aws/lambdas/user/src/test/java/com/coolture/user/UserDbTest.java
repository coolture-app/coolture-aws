package com.coolture.user;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.storage.S3StorageService;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.dto.UserListItemDto;
import com.coolture.user.dto.UserProfileUpdateRequest;
import com.coolture.user.repository.InterestRepository;
import com.coolture.user.repository.UserRepository;
import com.coolture.user.service.InterestService;
import com.coolture.user.service.UserService;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Blocks on the migrated database: profiles, search, follow lists and blocking back. Needs `make up`; run it with `make test-db`. */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class UserDbTest {

    private static final UUID ANNA = UUID.fromString("e0000000-0000-0000-0000-000000000001");
    private static final UUID JAN = UUID.fromString("e0000000-0000-0000-0000-000000000002");
    private static final UUID OLA = UUID.fromString("e0000000-0000-0000-0000-000000000003");
    private static final UUID BARTEK = UUID.fromString("e0000000-0000-0000-0000-000000000004");
    private static final String ALL = "'" + ANNA + "', '" + JAN + "', '" + OLA + "', '" + BARTEK + "'";

    private final UserService users = new UserService(new UserRepository(), new InterestRepository(), mock(S3StorageService.class));
    private final InterestService interests = new InterestService(new InterestRepository());

    @BeforeEach
    void addRows() throws SQLException {
        removeRows();
        execute("INSERT INTO users (id, username, first_name, last_name, bio) VALUES ('" + ANNA + "', 'udb_anna', 'Anna', 'Łódzka', 'bio'), ('"
            + JAN + "', 'udb_jan', 'Jan', 'Kowalski', 'bio'), ('" + OLA + "', 'udb_ola', 'Ola', NULL, 'bio')");
        // Ola follows both, and Anna and Jan are friends.
        follow(OLA, ANNA);
        follow(OLA, JAN);
        follow(ANNA, JAN);
        follow(JAN, ANNA);
    }

    @AfterEach
    void removeRows() throws SQLException {
        execute("DELETE FROM post_participations WHERE user_id IN (" + ALL + ")");
        execute("DELETE FROM posts WHERE author_id IN (" + ALL + ")");
        execute("DELETE FROM user_relations WHERE source_user_id IN (" + ALL + ") OR target_user_id IN (" + ALL + ")");
        execute("DELETE FROM user_interests WHERE user_id IN (" + ALL + ")");
        execute("DELETE FROM user_kinds WHERE user_id IN (" + ALL + ")");
        execute("DELETE FROM users WHERE id IN (" + ALL + ")");
    }

    @Test
    void kindsAndInterests_roundTrip_andAHiddenInterestIsRefused() throws SQLException {
        assertTrue(interests.getCatalog().categories().stream()
            .flatMap(c -> c.interests().stream()).noneMatch(i -> i.key().equals("astrology") || i.key().equals("concerts")));
        execute("INSERT INTO user_interests (user_id, interest_key) VALUES ('" + ANNA + "', 'astrology')");
        assertThrows(IllegalArgumentException.class,
            () -> interests.replaceSelection(ANNA, ANNA, new UserInterestsDto(List.of("stage"), List.of("astrology"))));

        interests.replaceSelection(ANNA, ANNA, new UserInterestsDto(List.of("cinema", "stage"), List.of("jazz")));

        assertEquals(new UserInterestsDto(List.of("stage", "cinema"), List.of("jazz")), interests.getSelection(ANNA, JAN));
        assertEquals(List.of("stage", "cinema"), users.getByIdForCaller(ANNA, ANNA).kinds());
    }

    @Test
    void search_needsEveryWord_inAnyColumn_withoutDiacritics_andWithoutWordForms() throws SQLException {
        assertEquals(List.of("udb_jan"), ours(users.search(OLA, "Jan Kowalski", null, 20)));
        assertEquals(List.of("udb_jan"), ours(users.search(OLA, "kowalski udb", null, 20)));
        assertEquals(List.of("udb_anna"), ours(users.search(OLA, "LODZKA", null, 20)));
        assertEquals(List.of(), ours(users.search(OLA, "Jan Nowak", null, 20)), "a word in no column");
        assertEquals(List.of(), ours(users.search(OLA, "Kowalskiego", null, 20)), "people get no word forms");
        assertEquals(List.of(), ours(users.search(OLA, "udb%", null, 20)), "% is a letter, not a wildcard");
    }

    /** The test's own people, since the database may hold others with the same names. */
    private static List<String> ours(CursorPage<UserListItemDto> page) {
        return usernames(page).stream().filter(u -> u.startsWith("udb_")).toList();
    }

    @Test
    void aBlock_endsFollowsBothWays_andUnblockingDoesntBringThemBack() throws SQLException {
        users.block(ANNA, JAN);
        assertEquals(List.of("BLOCK"), relations(ANNA, JAN));
        assertEquals(List.of(), relations(JAN, ANNA));

        users.unblock(ANNA, JAN);
        assertEquals(List.of(), relations(ANNA, JAN));
        assertEquals(List.of(), relations(JAN, ANNA));
    }

    @Test
    void twoPeople_canBlockEachOther_andOneUnblockLeavesTheOthersBlock() throws SQLException {
        users.block(ANNA, JAN);
        users.block(JAN, ANNA);
        assertEquals(List.of("BLOCK"), relations(ANNA, JAN));
        assertEquals(List.of("BLOCK"), relations(JAN, ANNA));

        users.unblock(ANNA, JAN);

        assertEquals(List.of("BLOCK"), relations(JAN, ANNA));
        assertThrows(IllegalArgumentException.class, () -> users.getByIdForCaller(JAN, ANNA));
        assertTrue(users.getByIdForCaller(ANNA, JAN).isBlocked());
    }

    @Test
    void events_countEveryPost_andStancesOnlyForTheUserAndAFriendWhoShares() throws SQLException {
        execute("INSERT INTO posts (author_id, title, starts_at, ends_at, type) VALUES ('" + ANNA + "', 'udb_soon', now() + interval '1 day', NULL, 'ONLINE'),"
            + " ('" + ANNA + "', 'udb_over', now() - interval '2 days', now() - interval '1 day', 'ONLINE')");
        execute("INSERT INTO post_participations (post_id, user_id, type) SELECT id, '" + JAN + "', 'TAKES_PART' FROM posts WHERE title = 'udb_soon'");
        execute("INSERT INTO post_participations (post_id, user_id, type) SELECT id, '" + JAN + "', 'INTERESTED' FROM posts WHERE title = 'udb_over'");

        var annas = users.getByIdForCaller(ANNA, OLA).events();
        assertEquals(2, annas.posts());
        assertEquals(1, annas.upcomingPosts());

        assertEquals(1, users.getByIdForCaller(JAN, ANNA).events().takesPart());
        assertEquals(0, users.getByIdForCaller(JAN, ANNA).events().interested());
        assertEquals(1, users.getByIdForCaller(JAN, JAN).events().takesPart());
        // Ola follows Jan, but he doesn't follow her back.
        assertNull(users.getByIdForCaller(JAN, OLA).events().takesPart());
        assertNull(users.getByIdForCaller(JAN, null).events().takesPart());

        // As the tab's list, the count leaves out a post whose author is in a block with the viewer.
        execute("INSERT INTO posts (author_id, title, starts_at, ends_at, type) VALUES ('" + OLA + "', 'udb_blocked', now() + interval '1 day', NULL, 'ONLINE')");
        execute("INSERT INTO post_participations (post_id, user_id, type) SELECT id, '" + JAN + "', 'TAKES_PART' FROM posts WHERE title = 'udb_blocked'");
        execute("INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES ('" + ANNA + "', '" + OLA + "', 'BLOCK')");
        assertEquals(1, users.getByIdForCaller(JAN, ANNA).events().takesPart());
        assertEquals(2, users.getByIdForCaller(JAN, JAN).events().takesPart());

        execute("UPDATE users SET share_stances = false WHERE id = '" + JAN + "'");
        assertNull(users.getByIdForCaller(JAN, ANNA).events().takesPart());
        assertEquals(2, users.getByIdForCaller(JAN, JAN).events().takesPart());
    }

    @Test
    void aTakenUsername_isAConflict() {
        assertThrows(IllegalStateException.class, () -> users.update(ANNA, ANNA,
            new UserProfileUpdateRequest("udb_jan", null, null, null, null)));
    }

    @Test
    void theBlocker_getsASlimProfile_andWhoeverWasBlocked_aNotFound() throws SQLException {
        users.block(ANNA, JAN);

        UserProfileDto slim = users.getByIdForCaller(JAN, ANNA);
        assertTrue(slim.isBlocked());
        assertEquals("udb_jan", slim.username());
        assertEquals("Jan", slim.firstName());
        assertNull(slim.bio());
        assertNull(slim.followersCount());
        assertNull(slim.interests());
        assertTrue(users.getByUsernameForCaller("udb_jan", ANNA).isBlocked());

        assertNotFound(() -> users.getByIdForCaller(ANNA, JAN));
        assertNotFound(() -> users.getByUsernameForCaller("udb_anna", JAN));

        UserProfileDto forAGuest = users.getByIdForCaller(ANNA, null);
        assertFalse(forAGuest.isBlocked());
        assertEquals("bio", forAGuest.bio());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBlockedPair_findNeitherTheOther_inSearchOrInSomeoneElsesLists(boolean annaBlocked) throws SQLException {
        users.block(annaBlocked ? ANNA : JAN, annaBlocked ? JAN : ANNA);

        assertEquals(List.of("udb_ola", "udb_anna"), usernames(users.search(ANNA, "udb_", null, 20)));
        assertEquals(List.of("udb_ola", "udb_jan"), usernames(users.search(JAN, "udb_", null, 20)));
        assertEquals(List.of("udb_anna"), usernames(users.getFollowing(OLA, ANNA, null, null, 20)));
        assertEquals(3, users.search(OLA, "udb_", null, 20).items().size(), "someone outside the block sees both");
        assertEquals(2, users.getFollowing(OLA, OLA, null, null, 20).items().size(), "someone outside the block sees both");

        assertNotFound(() -> users.getFollowers(JAN, ANNA, null, null, 20));
        assertNotFound(() -> users.getFollowing(JAN, ANNA, null, null, 20));
        assertNotFound(() -> interests.getSelection(JAN, ANNA));
        assertNotFound(() -> interests.getSelection(ANNA, JAN));
    }

    @Test
    void followLists_goByNameFromAToZ_elseByUsername_andPageInThatOrder() throws SQLException {
        // Bartek has a last name but no first, so "udb_bartek" places him, after every name here.
        execute("INSERT INTO users (id, username, last_name) VALUES ('" + BARTEK + "', 'udb_bartek', 'Adamski')");
        follow(BARTEK, ANNA);
        follow(OLA, BARTEK);

        assertEquals(List.of("udb_jan", "udb_ola", "udb_bartek"), usernames(users.getFollowers(ANNA, ANNA, null, null, 20)));
        assertEquals(List.of("udb_anna", "udb_jan", "udb_bartek"), usernames(users.getFollowing(OLA, OLA, null, null, 20)));

        CursorPage<UserListItemDto> first = users.getFollowing(OLA, OLA, null, null, 2);
        assertEquals(List.of("udb_anna", "udb_jan"), usernames(first));
        assertEquals(List.of("udb_bartek"), usernames(users.getFollowing(OLA, OLA, null, first.page().nextCursor(), 2)));
    }

    @Test
    void followLists_searchAsPeopleSearchDoes() throws SQLException {
        assertEquals(List.of("udb_jan"), usernames(users.getFollowing(OLA, OLA, "KOWALSKI", null, 20)));
        assertEquals(List.of("udb_anna"), usernames(users.getFollowing(OLA, OLA, "lodzka anna", null, 20)));
        assertEquals(List.of(), usernames(users.getFollowers(JAN, JAN, "ola nowak", null, 20)), "a word in no column");
    }

    @Test
    void theBlockList_stillListsWhomTheCallerBlocked() throws SQLException {
        users.block(ANNA, JAN);

        assertEquals(List.of("udb_jan"), usernames(users.getBlocking(ANNA, ANNA, null, 20)));
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        assertTrue(e.getMessage().toLowerCase().contains("not found"), e.getMessage());
    }

    private static List<String> usernames(CursorPage<UserListItemDto> page) {
        return page.items().stream().map(item -> item.summary().username()).toList();
    }

    private static void follow(UUID source, UUID target) throws SQLException {
        execute("INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES ('" + source + "', '" + target + "', 'FOLLOW')");
    }

    private static List<String> relations(UUID source, UUID target) throws SQLException {
        String sql = "SELECT type FROM user_relations WHERE source_user_id = '" + source + "' AND target_user_id = '" + target + "'";
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? List.of(rs.getString(1)) : List.of();
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }
}
