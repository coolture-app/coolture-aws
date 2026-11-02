package com.coolture;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostMarkDto;
import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.ReactionType;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.storage.MediaUrls;
import com.coolture.common.storage.S3StorageService;
import com.coolture.feed.FeedConfig;
import com.coolture.feed.FeedMode;
import com.coolture.feed.FeedPage;
import com.coolture.feed.FeedRepository;
import com.coolture.feed.FeedScorer;
import com.coolture.feed.FeedService;
import com.coolture.repository.PostReadRepository;
import com.coolture.repository.PostSort;
import com.coolture.services.PostReadService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Runs the real queries on the migrated database, as the read-only role post-read uses on AWS, so a missing grant fails here.
 * Needs `make up`. Run it with `make test-post-read-db`. The fixtures are removed afterwards.
 */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class PostReadDbTest {

    private static final UUID ME = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private static final Set<String> PUBLIC_LIVE = Set.of("future_public", "no_end_started_2h", "multi_day_running", "commented");
    private static final Set<String> INTERACTED = Set.of("liked", "disliked", "interested", "attending");
    private static final Set<String> ENDED_RECENTLY = Set.of("no_end_started_4h", "no_end_started_14h", "ended_1h_ago", "ended_11h_ago");
    private static final Set<String> ENDED_LONG_AGO = Set.of("no_end_started_16h", "ended_13h_ago", "ended_last_year");
    private static final Set<String> HIDDEN_FROM_ME = Set.of("deleted", "by_i_blocked", "by_blocked_me", "friends_by_stranger", "private_by_stranger");
    private static final Set<String> MY_VIEW_ONLY = Set.of("friends_by_followed", "mine", "mine_private", "created_after_as_of");

    private static final Set<String> BROWSE_DEFAULT = union(PUBLIC_LIVE, INTERACTED, ENDED_RECENTLY, MY_VIEW_ONLY);
    private static final Set<String> NOT_ENDED_FOR_ME = union(PUBLIC_LIVE, INTERACTED, MY_VIEW_ONLY);

    /** Every post that tests a rule. The database also holds demo data and the walk posts, which these checks ignore. */
    private static final Set<String> RULE_TITLES = union(PUBLIC_LIVE, INTERACTED, ENDED_RECENTLY, ENDED_LONG_AGO, HIDDEN_FROM_ME, MY_VIEW_ONLY);

    private static final MapBoundsDto AROUND_THE_FIXTURES = new MapBoundsDto(new GeoPointDto(55.0, 18.0), new GeoPointDto(54.0, 19.0));

    private final PostReadRepository postRepository = new PostReadRepository();
    private final PostReadService postService = new PostReadService(postRepository);
    private final FeedRepository feedRepository = new FeedRepository();
    private final FeedService feedService = new FeedService(feedRepository, FeedConfig.DEFAULTS, Clock.systemUTC(), () -> 42);

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        return Stream.of(sets).flatMap(Set::stream).collect(Collectors.toSet());
    }

    private static PostFeedFilters filters(String sortBy, boolean includePast, Instant startsFrom, Instant startsTo) {
        return new PostFeedFilters(null, null, null, null, null, null, startsFrom, startsTo, null, null, null,
            null, null, null, 20, sortBy, includePast);
    }

    private static Set<String> ruleTitles(List<PostCardDto> cards) {
        return cards.stream().map(PostCardDto::title).filter(RULE_TITLES::contains).collect(Collectors.toSet());
    }

    private static void removeFixtures(Statement stmt) throws SQLException {
        stmt.execute("RESET ROLE");
        String users = "(SELECT id FROM users WHERE id::text LIKE 'a0000000-%')";
        stmt.execute("DELETE FROM comments WHERE author_id IN " + users);
        stmt.execute("DELETE FROM post_reactions WHERE user_id IN " + users);
        stmt.execute("DELETE FROM post_participations WHERE user_id IN " + users);
        stmt.execute("DELETE FROM posts WHERE author_id IN " + users);
        stmt.execute("DELETE FROM user_relations WHERE source_user_id IN " + users);
        stmt.execute("DELETE FROM event_locations WHERE id::text LIKE 'a0000000-%'");
        stmt.execute("DELETE FROM users WHERE id::text LIKE 'a0000000-%'");
    }

    @BeforeAll
    static void addFixtures() throws SQLException, IOException {
        MediaUrls.useStorage(mock(S3StorageService.class));
        String fixtures;
        try (InputStream in = PostReadDbTest.class.getResourceAsStream("/feed-fixtures.sql")) {
            fixtures = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = DatabaseConfig.getConnection(); Statement stmt = conn.createStatement()) {
            removeFixtures(stmt);
            stmt.execute(fixtures);
        }
    }

    @AfterAll
    static void cleanUp() throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection(); Statement stmt = conn.createStatement()) {
            removeFixtures(stmt);
        }
        MediaUrls.useStorage(null);
    }

    /** The pool holds two connections, and a role set on a connection stays when it goes back to the pool. */
    @BeforeEach
    void readAsTheReadOnlyRole() throws SQLException {
        try (Connection first = DatabaseConfig.getConnection(); Connection second = DatabaseConfig.getConnection()) {
            for (Connection conn : List.of(first, second)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET ROLE coolture_reader");
                    try (ResultSet rs = stmt.executeQuery("SELECT current_user")) {
                        rs.next();
                        assertEquals("coolture_reader", rs.getString(1));
                    }
                }
            }
        }
    }

    private List<PostCardDto> walkFeed(FeedService service, UUID viewer, int limit, Double latitude, Double longitude, FeedMode mode) throws SQLException {
        List<PostCardDto> cards = new ArrayList<>();
        String cursor = null;
        do {
            FeedPage page = service.getFeed(viewer, cursor, limit, latitude, longitude);
            assertEquals(mode, page.mode());
            cards.addAll(page.items());
            cursor = page.page().nextCursor();
        } while (cursor != null);
        return cards;
    }

    private List<PostCardDto> walkPosts(UUID viewer, PostFeedFilters filters, int limit) throws SQLException {
        List<PostCardDto> cards = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<PostCardDto> page = postService.getFeed(viewer, filters, cursor, limit);
            cards.addAll(page.items());
            cursor = page.page().nextCursor();
        } while (cursor != null);
        return cards;
    }

    private static Set<String> marks(List<PostMarkDto> marks) {
        return marks.stream().map(PostMarkDto::title).filter(t -> t.startsWith("map_")).collect(Collectors.toSet());
    }

    // ===== Feed rules =====

    @Test
    void feed_signedIn_followsEveryRule() throws SQLException {
        assertEquals(union(PUBLIC_LIVE, Set.of("friends_by_followed")), ruleTitles(walkFeed(feedService, ME, 100, null, null, FeedMode.RANKED)));
    }

    @Test
    void feed_guest_isPublicAndLive_withNoPersonalFilters() throws SQLException {
        assertEquals(union(PUBLIC_LIVE, INTERACTED, Set.of("by_i_blocked", "by_blocked_me", "mine")),
            ruleTitles(walkFeed(feedService, null, 100, null, null, FeedMode.RANKED)));
    }

    @Test
    void feed_aPostCreatedAfterAsOf_appearsOnlyInALaterSession() throws SQLException {
        Instant now = Instant.now();
        Set<UUID> thisSession = ids(feedRepository.findCandidates(ME, now, null, null));
        Set<UUID> laterSession = ids(feedRepository.findCandidates(ME, now.plus(Duration.ofHours(2)), null, null));

        laterSession.removeAll(thisSession);
        assertEquals(1, laterSession.size());
    }

    private static Set<UUID> ids(List<FeedScorer.Facts> facts) {
        return facts.stream().map(FeedScorer.Facts::id).collect(Collectors.toCollection(HashSet::new));
    }

    @Test
    void feed_theFallback_appliesExactlyTheSameRules() throws SQLException {
        assertEquals(ruleTitles(walkFeed(feedService, ME, 100, null, null, FeedMode.RANKED)),
            ruleTitles(feedRepository.findUpcoming(ME, Instant.now(), null, null, 1000)));
        assertEquals(ruleTitles(walkFeed(feedService, null, 100, null, null, FeedMode.RANKED)),
            ruleTitles(feedRepository.findUpcoming(null, Instant.now(), null, null, 1000)));
    }

    // ===== /posts rules =====

    @ParameterizedTest
    @CsvSource({"RECENT", "POPULAR"})
    void browse_hidesEventsEndedOver12HoursAgo_unlessOptedIn(String sort) throws SQLException {
        assertEquals(BROWSE_DEFAULT, ruleTitles(walkPosts(ME, filters(sort, false, null, null), 100)));
        assertEquals(union(BROWSE_DEFAULT, ENDED_LONG_AGO), ruleTitles(walkPosts(ME, filters(sort, true, null, null), 100)));
    }

    @Test
    void browse_aChosenDateTurnsTheWindowOff() throws SQLException {
        Instant longAgo = Instant.now().minus(Duration.ofDays(500));
        Instant farAway = Instant.now().plus(Duration.ofDays(500));

        assertEquals(union(BROWSE_DEFAULT, ENDED_LONG_AGO), ruleTitles(walkPosts(ME, filters("RECENT", false, longAgo, null), 100)));
        assertEquals(union(BROWSE_DEFAULT, ENDED_LONG_AGO), ruleTitles(walkPosts(ME, filters("RECENT", false, null, farAway), 100)));
    }

    @Test
    void browse_upcoming_neverShowsAnEndedEvent() throws SQLException {
        Instant longAgo = Instant.now().minus(Duration.ofDays(500));

        assertEquals(NOT_ENDED_FOR_ME, ruleTitles(walkPosts(ME, filters("UPCOMING", false, null, null), 100)));
        assertEquals(NOT_ENDED_FOR_ME, ruleTitles(walkPosts(ME, filters("UPCOMING", true, null, null), 100)));
        assertEquals(NOT_ENDED_FOR_ME, ruleTitles(walkPosts(ME, filters("UPCOMING", true, longAgo, null), 100)));
    }

    @Test
    void browse_guest_seesPublicPostsWithoutBlocks() throws SQLException {
        assertEquals(union(PUBLIC_LIVE, INTERACTED, ENDED_RECENTLY, Set.of("by_i_blocked", "by_blocked_me", "mine", "created_after_as_of")),
            ruleTitles(walkPosts(null, filters("RECENT", false, null, null), 100)));
    }

    @Test
    void browse_cardsCarryTheViewersOwnReactionAndParticipation() throws SQLException {
        List<PostCardDto> cards = walkPosts(ME, filters("RECENT", false, null, null), 100);

        assertEquals(ReactionType.LIKE, card(cards, "liked").myReaction());
        assertEquals(ParticipationType.INTERESTED, card(cards, "interested").myParticipation());
    }

    private static PostCardDto card(List<PostCardDto> cards, String title) {
        return cards.stream().filter(c -> title.equals(c.title())).findFirst().orElseThrow();
    }

    // ===== Map rules =====

    @Test
    void map_appliesBlocksAndThePastWindow() throws SQLException {
        assertEquals(Set.of("map_open"),
            marks(postRepository.findPostMarks(filters(null, false, null, null), AROUND_THE_FIXTURES, ME)));
        assertEquals(Set.of("map_open", "map_ended_last_year"),
            marks(postRepository.findPostMarks(filters(null, true, null, null), AROUND_THE_FIXTURES, ME)));
        assertEquals(Set.of("map_open", "map_ended_last_year"),
            marks(postRepository.findPostMarks(filters(null, false, Instant.now().minus(Duration.ofDays(500)), null), AROUND_THE_FIXTURES, ME)));
    }

    @Test
    void map_guest_hasNoBlocks() throws SQLException {
        assertEquals(Set.of("map_open", "map_by_i_blocked", "map_by_blocked_me"),
            marks(postRepository.findPostMarks(filters(null, false, null, null), AROUND_THE_FIXTURES, null)));
    }

    // ===== Walks =====

    @ParameterizedTest
    @EnumSource(PostSort.class)
    void walkingASort_showsEveryPostOnceInOrder(PostSort sort) throws SQLException {
        for (boolean includePast : List.of(false, true)) {
            PostFeedFilters filters = filters(sort.name(), includePast, null, null);
            List<UUID> everything = postRepository.findFeed(filters, sort, ME, null, 1_000_000).stream().map(PostCardDto::id).toList();
            List<UUID> walked = walkPosts(ME, filters, 7).stream().map(PostCardDto::id).toList();

            assertTrue(everything.size() > 60, "the walk must cross the tied posts");
            assertEquals(everything, walked);
        }
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void walkingTheFeed_showsEveryCandidateOnce(boolean signedIn, boolean withLocation) throws SQLException {
        UUID viewer = signedIn ? ME : null;
        Double latitude = withLocation ? 54.35 : null;
        Double longitude = withLocation ? 18.65 : null;

        List<UUID> walked = walkFeed(feedService, viewer, 7, latitude, longitude, FeedMode.RANKED).stream().map(PostCardDto::id).toList();

        assertEquals(walked.size(), new HashSet<>(walked).size(), "a post came twice");
        assertEquals(ids(feedRepository.findCandidates(viewer, Instant.now(), latitude, longitude)), new HashSet<>(walked));
    }

    @Test
    void walkingTheFallback_showsTheSamePostsOnce() throws SQLException {
        FeedRepository rankingDown = new FeedRepository() {
            @Override
            public List<FeedScorer.Facts> findCandidates(UUID viewer, Instant asOf, Double latitude, Double longitude) throws SQLException {
                throw new SQLException("ranking is down");
            }
        };
        FeedService fallback = new FeedService(rankingDown, FeedConfig.DEFAULTS, Clock.systemUTC(), () -> 42);

        List<UUID> walked = walkFeed(fallback, ME, 7, null, null, FeedMode.UPCOMING).stream().map(PostCardDto::id).toList();

        assertEquals(walked.size(), new HashSet<>(walked).size(), "a post came twice");
        assertEquals(ids(feedRepository.findCandidates(ME, Instant.now(), null, null)), new HashSet<>(walked));
    }
}
