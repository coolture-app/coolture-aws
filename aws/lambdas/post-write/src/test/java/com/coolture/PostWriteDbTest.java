package com.coolture;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.ReactionType;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostLinkDto;
import com.coolture.common.dto.enums.PostType;
import com.coolture.dto.CreatePostRequest;
import com.coolture.repository.DuplicateEventException;
import com.coolture.repository.PostWriteRepository;
import com.coolture.repository.StanceWriteRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stances and edits on the migrated database. Needs `make up`; run it with `make test-db`. Its rows are removed afterwards. */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class PostWriteDbTest {

    private static final UUID AUTHOR = UUID.fromString("b0000000-0000-0000-0000-000000000001");
    private static final UUID VIEWER = UUID.fromString("b0000000-0000-0000-0000-000000000002");
    private static final UUID POST = UUID.fromString("b0000000-0000-0000-0000-000000000100");

    private final PostWriteRepository repository = new PostWriteRepository();
    private final StanceWriteRepository stances = new StanceWriteRepository();

    @BeforeEach
    void addPost() throws SQLException {
        removeRows();
        execute("INSERT INTO users (id, username) VALUES ('" + AUTHOR + "', 'pwdb_author'), ('" + VIEWER + "', 'pwdb_viewer')");
        execute("INSERT INTO posts (id, author_id, title, starts_at, type) VALUES ('" + POST + "', '" + AUTHOR
            + "', 'stance test', now() + interval '2 days', 'ONLINE')");
    }

    @AfterEach
    void removeRows() throws SQLException {
        execute("DELETE FROM user_relations WHERE source_user_id IN ('" + AUTHOR + "', '" + VIEWER + "')");
        execute("DELETE FROM post_reactions WHERE post_id = '" + POST + "'");
        execute("DELETE FROM post_participations WHERE post_id = '" + POST + "'");
        execute("DELETE FROM post_media WHERE post_id IN (SELECT id FROM posts WHERE author_id IN ('" + AUTHOR + "', '" + VIEWER + "'))");
        execute("DELETE FROM media WHERE id::text LIKE 'b0000000-0000-0000-0000-0000000002%'");
        execute("DELETE FROM posts WHERE author_id = '" + AUTHOR + "'");
        execute("DELETE FROM users WHERE id IN ('" + AUTHOR + "', '" + VIEWER + "')");
    }

    @Test
    void notInterested_isNotCounted_asAParticipant() throws SQLException {
        stances.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);
        assertEquals(0, count("participant_count"));

        stances.upsertParticipation(POST, VIEWER, ParticipationType.INTERESTED);
        assertEquals(1, count("participant_count"));

        stances.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);
        assertEquals(0, count("participant_count"));

        stances.deleteParticipation(POST, VIEWER);
        assertEquals(0, count("participant_count"));
    }

    @Test
    void aMark_takesBackTheLike() throws SQLException {
        stances.upsertReaction(POST, VIEWER, ReactionType.LIKE);
        assertEquals(1, count("positive_reaction_count"));

        stances.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);

        assertEquals(0, count("positive_reaction_count"));
        assertNull(queryString("SELECT type FROM post_reactions WHERE post_id = '" + POST + "'"));
        assertEquals("NOT_INTERESTED", stance());
    }

    @Test
    void aLike_takesBackTheMark_butNotAPositiveStance() throws SQLException {
        stances.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);
        stances.upsertReaction(POST, VIEWER, ReactionType.LIKE);

        assertEquals(1, count("positive_reaction_count"));
        assertNull(stance());

        stances.upsertParticipation(POST, VIEWER, ParticipationType.TAKES_PART);
        stances.deleteReaction(POST, VIEWER);
        stances.upsertReaction(POST, VIEWER, ReactionType.LIKE);

        assertEquals("TAKES_PART", stance());
        assertEquals(1, count("participant_count"));
    }

    @Test
    void aChangedStance_startsItsTimeAgain() throws SQLException {
        stances.upsertParticipation(POST, VIEWER, ParticipationType.INTERESTED);
        execute("UPDATE post_participations SET created_at = now() - interval '3 days' WHERE post_id = '" + POST + "'");
        Instant before = Instant.now().minusSeconds(60);

        stances.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);

        Instant createdAt = Instant.parse(queryString(
            "SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM post_participations WHERE post_id = '" + POST + "'"));
        assertTrue(createdAt.isAfter(before), "the mark kept the old stance's time: " + createdAt);
    }

    @Test
    void aBlockedPair_cantLikeOrTakeAStance_eitherWay_butCanTakeBackWhatTheyHad() throws SQLException {
        stances.upsertReaction(POST, VIEWER, ReactionType.LIKE);
        stances.upsertParticipation(POST, VIEWER, ParticipationType.INTERESTED);

        for (String block : List.of(AUTHOR + "', '" + VIEWER, VIEWER + "', '" + AUTHOR)) {
            execute("INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES ('" + block + "', 'BLOCK')");

            assertNotFound(() -> stances.upsertReaction(POST, VIEWER, ReactionType.LIKE));
            assertNotFound(() -> stances.upsertParticipation(POST, VIEWER, ParticipationType.TAKES_PART));
            assertNotFound(() -> stances.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED));
            assertEquals("INTERESTED", stance());

            execute("DELETE FROM user_relations WHERE source_user_id IN ('" + AUTHOR + "', '" + VIEWER + "')");
        }

        execute("INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES ('" + AUTHOR + "', '" + VIEWER + "', 'BLOCK')");
        stances.deleteReaction(POST, VIEWER);
        stances.deleteParticipation(POST, VIEWER);
        assertEquals(0, count("positive_reaction_count"));
        assertEquals(0, count("participant_count"));
    }

    @Test
    void anEndedEvent_refusesBothStances_butTakesALike_NieDlaMnie_andRemoval() throws SQLException {
        stances.upsertParticipation(POST, VIEWER, ParticipationType.INTERESTED);
        // No end time: it ends 3 hours after the start.
        execute("UPDATE posts SET starts_at = now() - interval '3 hours 1 minute', ends_at = NULL WHERE id = '" + POST + "'");

        IllegalArgumentException ended = assertThrows(IllegalArgumentException.class,
            () -> stances.upsertParticipation(POST, VIEWER, ParticipationType.TAKES_PART));
        assertFalse(ended.getMessage().toLowerCase().contains("not found"), "an ended event is a 400, not a 404");
        assertThrows(IllegalArgumentException.class, () -> stances.upsertParticipation(POST, VIEWER, ParticipationType.INTERESTED));
        assertEquals("INTERESTED", stance());

        stances.upsertReaction(POST, VIEWER, ReactionType.LIKE);
        assertEquals(1, count("positive_reaction_count"));
        stances.deleteParticipation(POST, VIEWER);
        assertNull(stance());
        stances.upsertParticipation(POST, VIEWER, ParticipationType.NOT_INTERESTED);
        assertEquals("NOT_INTERESTED", stance());
    }

    @Test
    void anEventRunning_withAnEndInTheFuture_stillTakesAStance() throws SQLException {
        execute("UPDATE posts SET starts_at = now() - interval '5 hours', ends_at = now() + interval '1 hour' WHERE id = '" + POST + "'");

        stances.upsertParticipation(POST, VIEWER, ParticipationType.TAKES_PART);

        assertEquals("TAKES_PART", stance());
    }

    @Test
    void aPostWithNoEndDate_neverEnds_soItStillTakesAStance() throws SQLException {
        execute("UPDATE posts SET starts_at = now() - interval '30 days', ends_at = NULL, open_ended = true WHERE id = '" + POST + "'");

        stances.upsertParticipation(POST, VIEWER, ParticipationType.INTERESTED);

        assertEquals("INTERESTED", stance());
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call);
        assertTrue(e.getMessage().toLowerCase().contains("not found"), e.getMessage());
    }

    @Test
    void aPostWithNoEndDate_cantHaveAnEnd() {
        SQLException e = assertThrows(SQLException.class, () -> execute(
            "UPDATE posts SET open_ended = true, ends_at = starts_at + interval '1 day' WHERE id = '" + POST + "'"));
        assertTrue(e.getMessage().contains("posts_open_ended_has_no_end"), e.getMessage());
    }

    @Test
    void anEdit_removesTheEndAndTheLink_andSetsNoEndDate_andBack() throws SQLException {
        execute("UPDATE posts SET ends_at = starts_at + interval '2 hours', event_url = 'https://example.com' WHERE id = '" + POST + "'");
        PostWriteRepository.ExistingPost existing = repository.findById(POST).orElseThrow();
        PostDetailDto standing = repository.updatePost(POST, null, ending(existing, null, true));
        assertTrue(standing.openEnded());
        assertNull(standing.endsAt());
        assertNull(standing.eventUrl());

        Instant end = Instant.parse("2040-01-01T21:00:00Z");
        existing = repository.findById(POST).orElseThrow();
        PostDetailDto dated = repository.updatePost(POST, null, ending(existing, end, false));
        assertFalse(dated.openEnded());
        assertEquals(end, dated.endsAt());
    }

    @Test
    void anEdit_savesTheLinks_inOrder_andAnEmptyListRemovesThem() throws SQLException {
        List<PostLinkDto> links = List.of(new PostLinkDto("https://b.example", "Bilety"), new PostLinkDto("https://a.example", null));

        PostWriteRepository.ExistingPost existing = repository.findById(POST).orElseThrow();
        PostDetailDto linked = repository.updatePost(POST, null, withLinks(existing, links));
        assertEquals(links, linked.links());
        assertEquals(links, repository.findById(POST).orElseThrow().links());

        existing = repository.findById(POST).orElseThrow();
        assertNull(repository.updatePost(POST, null, withLinks(existing, List.of())).links());
        assertEquals("[]", queryString("SELECT links::text FROM posts WHERE id = '" + POST + "'"));
    }

    @Test
    void aNamedCover_movesToTheFront_andTheOthersKeepTheirOrder_andOneNotOnThePostIsRefused() throws SQLException {
        List<UUID> photos = List.of(photo(1), photo(2), photo(3));
        UUID elsewhere = photo(4);
        for (int position = 0; position < photos.size(); position++) {
            execute("INSERT INTO post_media (post_id, media_id, position) VALUES ('" + POST + "', '" + photos.get(position) + "', " + position + ")");
        }

        PostWriteRepository.ExistingPost existing = repository.findById(POST).orElseThrow();
        repository.updatePost(POST, null, withCover(existing, photos.get(2)));

        assertEquals("0:" + photos.get(2) + ",1:" + photos.get(0) + ",2:" + photos.get(1),
            queryString("SELECT string_agg(position || ':' || media_id, ',' ORDER BY position) FROM post_media WHERE post_id = '" + POST + "'"));
        assertThrows(IllegalArgumentException.class, () -> repository.updatePost(POST, null, withCover(existing, elsewhere)));
    }

    @Test
    void aNewPost_putsItsNamedCoverFirst_andACoverWithNoPhotosIsRefused() throws SQLException {
        List<UUID> photos = List.of(photo(1), photo(2), photo(3));

        PostDetailDto created = repository.createPost(AUTHOR, online(null, photos, photos.get(2)));

        assertEquals(List.of(photos.get(2), photos.get(0), photos.get(1)),
            created.media().stream().map(photo -> photo.media().id()).toList());
        assertThrows(IllegalArgumentException.class, () -> repository.createPost(AUTHOR, online(null, null, photos.get(0))));
        assertEquals("1", queryString("SELECT count(*) FROM posts WHERE author_id = '" + AUTHOR + "' AND id <> '" + POST + "'"));
    }

    private static UUID photo(int number) throws SQLException {
        UUID id = UUID.fromString("b0000000-0000-0000-0000-00000000020" + number);
        execute("INSERT INTO media (id, owner_id, object_key, purpose, status) VALUES ('" + id + "', '" + AUTHOR + "', 'pwdb/" + id
            + "', 'EVENT_MEDIA', 'ATTACHED')");
        return id;
    }

    @Test
    void aSecondPostFromTheSameEventPage_isRefused_butAnotherAuthorMayHaveOne() throws SQLException {
        String page = "https://www.trojmiasto.pl/imprezy/pwdb-imp1.html";
        repository.createPost(AUTHOR, online(page));

        assertThrows(DuplicateEventException.class, () -> repository.createPost(AUTHOR, online(page)));
        execute("UPDATE posts SET author_id = '" + VIEWER + "' WHERE author_id = '" + AUTHOR + "' AND event_url = '" + page + "'");
        repository.createPost(AUTHOR, online(page));
        execute("DELETE FROM posts WHERE author_id = '" + VIEWER + "'");
    }

    private static CreatePostRequest online(String eventUrl) {
        return online(eventUrl, null, null);
    }

    private static CreatePostRequest online(String eventUrl, List<UUID> mediaIds, UUID coverMediaId) {
        return new CreatePostRequest("imported", "Opis", eventUrl, null, Instant.now().plusSeconds(86_400), null, null,
            PostType.ONLINE, null, mediaIds, coverMediaId);
    }

    private static PostWriteRepository.Fields withLinks(PostWriteRepository.ExistingPost post, List<PostLinkDto> links) {
        return new PostWriteRepository.Fields(post.title(), post.description(), null, links, post.type(), post.startsAt(),
            null, false, null, null, null);
    }

    /** The saved post with no link and the given end. */
    private static PostWriteRepository.Fields ending(PostWriteRepository.ExistingPost post, Instant endsAt, boolean openEnded) {
        return new PostWriteRepository.Fields(post.title(), post.description(), null, null, post.type(), post.startsAt(), endsAt,
            openEnded, null, null, null);
    }

    private static PostWriteRepository.Fields withCover(PostWriteRepository.ExistingPost post, UUID coverMediaId) {
        return new PostWriteRepository.Fields(post.title(), post.description(), null, null, post.type(), post.startsAt(),
            post.endsAt(), post.openEnded(), null, null, coverMediaId);
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
