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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Runs the real queries on the migrated database, as the read-only role post-read uses on AWS, so a missing grant fails here.
 * Needs `make up`. Run it with `make test-post-read-db`. The fixtures are removed afterwards.
 */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class PostReadDbTest {

    private static final UUID ME = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MODEL_POST = UUID.fromString("a0000000-0000-0000-0000-000000000200");

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
        stmt.execute("DELETE FROM post_media WHERE post_id IN (SELECT id FROM posts WHERE author_id IN " + users + ")");
        stmt.execute("DELETE FROM posts WHERE author_id IN " + users);
        stmt.execute("DELETE FROM media WHERE id::text LIKE 'a0000000-%'");
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
        return walkFeed(service, viewer, limit, latitude, longitude, PostFeedFilters.none(), mode);
    }

    private List<PostCardDto> walkFeed(FeedService service, UUID viewer, int limit, Double latitude, Double longitude,
                                       PostFeedFilters filters, FeedMode mode) throws SQLException {
        List<PostCardDto> cards = new ArrayList<>();
        String cursor = null;
        do {
            FeedPage page = service.getFeed(viewer, cursor, limit, latitude, longitude, filters);
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
        Set<UUID> thisSession = ids(feedRepository.findCandidates(ME, now, null, null, PostFeedFilters.none()));
        Set<UUID> laterSession = ids(feedRepository.findCandidates(ME, now.plus(Duration.ofHours(2)), null, null, PostFeedFilters.none()));

        laterSession.removeAll(thisSession);
        assertEquals(1, laterSession.size());
    }

    private static Set<UUID> ids(List<FeedScorer.Facts> facts) {
        return facts.stream().map(FeedScorer.Facts::id).collect(Collectors.toCollection(HashSet::new));
    }

    @Test
    void feed_theFallback_appliesExactlyTheSameRules() throws SQLException {
        assertEquals(ruleTitles(walkFeed(feedService, ME, 100, null, null, FeedMode.RANKED)),
            ruleTitles(feedRepository.findUpcoming(ME, Instant.now(), PostFeedFilters.none(), null, null, 1000)));
        assertEquals(ruleTitles(walkFeed(feedService, null, 100, null, null, FeedMode.RANKED)),
            ruleTitles(feedRepository.findUpcoming(null, Instant.now(), PostFeedFilters.none(), null, null, 1000)));
    }

    @Test
    void feed_aPostVectorFromAnotherModel_isNeutral() throws SQLException {
        Instant now = Instant.now();
        List<FeedScorer.Facts> candidates = feedRepository.findCandidates(ME, now, null, null, PostFeedFilters.none());
        FeedScorer.Facts otherModel = candidates.stream().filter(f -> f.id().equals(OTHER_MODEL_POST)).findFirst().orElseThrow();

        assertNull(otherModel.similarity());
        assertEquals(FeedConfig.NEUTRAL, new FeedScorer(FeedConfig.DEFAULTS).score(otherModel, now, 42).affinity());
        assertTrue(candidates.stream().anyMatch(f -> f.similarity() != null), "vectors of the same model must still be compared");
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
        assertEquals(Set.of("map_open", "map_mine"),
            marks(postRepository.findPostMarks(filters(null, false, null, null), AROUND_THE_FIXTURES, ME, false)));
        assertEquals(Set.of("map_open", "map_mine", "map_ended_last_year"),
            marks(postRepository.findPostMarks(filters(null, true, null, null), AROUND_THE_FIXTURES, ME, false)));
        assertEquals(Set.of("map_open", "map_mine", "map_ended_last_year"),
            marks(postRepository.findPostMarks(filters(null, false, Instant.now().minus(Duration.ofDays(500)), null), AROUND_THE_FIXTURES, ME, false)));
    }

    @Test
    void map_guest_hasNoBlocks() throws SQLException {
        assertEquals(Set.of("map_open", "map_mine", "map_by_i_blocked", "map_by_blocked_me"),
            marks(postRepository.findPostMarks(filters(null, false, null, null), AROUND_THE_FIXTURES, null, false)));
    }

    // ===== The same filters on the feed, the map and NEAREST =====

    private static final List<String> DISTANCES = List.of("close", "mid", "far");

    /** Around the fixtures' centre, (54.35, 18.65): close is there, mid about 650 m away, far about 6.5 km. */
    private static PostFeedFilters around(String sortBy, Double radiusKm) {
        return new PostFeedFilters(null, null, null, null, null, null, null, null, 54.35, 18.65, radiusKm,
            null, null, null, 20, sortBy, false);
    }

    private static PostFeedFilters searching(String q) {
        return new PostFeedFilters(q, null, null, null, null, null, null, null, null, null, null,
            null, null, null, 20, null, false);
    }

    /** The distance word of each near_ post, in order. */
    private static List<String> distances(List<String> titles) {
        return titles.stream().filter(t -> t.startsWith("near_")).map(t -> t.split("_")[1]).toList();
    }

    private static List<String> titles(List<PostCardDto> cards) {
        return cards.stream().map(PostCardDto::title).toList();
    }

    @Test
    void feed_aRadius_keepsOnlyThePostsInsideIt() throws SQLException {
        assertEquals(Set.of("close", "mid"),
            new HashSet<>(distances(titles(walkFeed(feedService, ME, 7, null, null, around(null, 1.0), FeedMode.RANKED)))));
        assertEquals(Set.of("close", "mid", "far"),
            new HashSet<>(distances(titles(walkFeed(feedService, ME, 7, null, null, around(null, 10.0), FeedMode.RANKED)))));
    }

    @Test
    void feed_aSearch_narrowsTheRankedPosts_andStillHidesMine() throws SQLException {
        List<String> found = titles(walkFeed(feedService, ME, 7, null, null, searching("near_far"), FeedMode.RANKED));
        assertEquals(8, found.size());
        assertTrue(found.stream().allMatch(t -> t.startsWith("near_far_")));

        assertEquals(Set.of(), new HashSet<>(titles(walkFeed(feedService, ME, 7, null, null, searching("map_mine"), FeedMode.RANKED))));
    }

    @Test
    void aSearch_readsTheCityAndThePlacesName_inEveryList() throws SQLException {
        for (String q : List.of("fixturowo", "HALA FIXTUROWA")) {
            List<String> feed = titles(walkFeed(feedService, ME, 7, null, null, searching(q), FeedMode.RANKED));
            List<String> posts = titles(postRepository.findFeed(searching(q), PostSort.RECENT, ME, null, 100));
            List<String> marks = postRepository.findPostMarks(searching(q), AROUND_THE_FIXTURES, ME, false).stream()
                .map(PostMarkDto::title).toList();

            for (List<String> found : List.of(feed, posts, marks)) {
                assertEquals(8, found.size(), q);
                assertTrue(found.stream().allMatch(t -> t.startsWith("near_far_")), q);
            }
        }
    }

    @Test
    void mapMarks_carryThePlace() throws SQLException {
        PostMarkDto far = postRepository.findPostMarks(searching("near_far_1"), AROUND_THE_FIXTURES, ME, false).get(0);

        assertEquals("Hala Fixturowa", far.venueName());
        assertEquals("Fixturowo", far.city());
    }

    @Test
    void feed_includePast_letsEndedEventsIn_andKeepsHidingWhatIsMine() throws SQLException {
        assertEquals(union(PUBLIC_LIVE, Set.of("friends_by_followed"), ENDED_RECENTLY, ENDED_LONG_AGO),
            ruleTitles(walkFeed(feedService, ME, 100, null, null, filters(null, true, null, null), FeedMode.RANKED)));
    }

    @Test
    void feed_theFallback_appliesTheSameFilters() throws SQLException {
        PostFeedFilters filters = around(null, 1.0);
        assertEquals(new HashSet<>(distances(titles(walkFeed(feedService, ME, 7, null, null, filters, FeedMode.RANKED)))),
            new HashSet<>(distances(titles(feedRepository.findUpcoming(ME, Instant.now(), filters, null, null, 1000)))));
    }

    @Test
    void map_aRadius_andExcludeMine_matchTheFeed() throws SQLException {
        assertEquals(Set.of("map_open"),
            marks(postRepository.findPostMarks(filters(null, false, null, null), AROUND_THE_FIXTURES, ME, true)));
        assertEquals(Set.of("map_open", "map_mine", "map_by_i_blocked", "map_by_blocked_me"),
            marks(postRepository.findPostMarks(filters(null, false, null, null), AROUND_THE_FIXTURES, null, true)),
            "a guest has nothing of their own to leave out");

        Set<String> inside = postRepository.findPostMarks(around(null, 1.0), AROUND_THE_FIXTURES, ME, false).stream()
            .map(PostMarkDto::title).filter(t -> t.startsWith("near_")).map(t -> t.split("_")[1]).collect(Collectors.toSet());
        assertEquals(Set.of("close", "mid"), inside);
    }

    @Test
    void nearest_walksEveryPlacedPostOnce_nearestFirst() throws SQLException {
        PostFeedFilters filters = around("NEAREST", null);
        List<PostCardDto> everything = postRepository.findFeed(filters, PostSort.NEAREST, ME, null, 1_000_000);
        List<PostCardDto> walked = walkPosts(ME, filters, 7);

        assertEquals(everything.stream().map(PostCardDto::id).toList(), walked.stream().map(PostCardDto::id).toList());
        assertTrue(walked.stream().allMatch(c -> c.location() != null), "only posts with a place");
        List<String> order = distances(titles(walked));
        assertEquals(24, order.size());
        assertEquals(order.stream().sorted(java.util.Comparator.comparingInt(DISTANCES::indexOf)).toList(), order);
    }

    @Test
    void nearest_worksForAGuest() throws SQLException {
        assertEquals(24, distances(titles(walkPosts(null, around("NEAREST", null), 7))).size());
    }

    @Test
    void cards_carryEveryPhoto_coverFirst_atMostTheLimit() throws SQLException {
        PostCardDto card = card(walkPosts(ME, filters("RECENT", false, null, null), 100), "ten_photos");

        assertEquals(com.coolture.common.validation.PostLimits.MAX_PHOTOS, card.photos().size());
        assertEquals(UUID.fromString("a0000000-0000-0000-0001-000000000010"), card.photos().get(0).id());
        assertEquals(UUID.fromString("a0000000-0000-0000-0001-000000000001"), card.photos().get(1).id());
        assertEquals(800, card.photos().get(0).width());
        assertNull(card(walkPosts(ME, filters("RECENT", false, null, null), 100), "future_public").photos());
    }

    // ===== Walks =====

    @ParameterizedTest
    @EnumSource(value = PostSort.class, mode = EnumSource.Mode.EXCLUDE, names = "NEAREST")
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
        assertEquals(ids(feedRepository.findCandidates(viewer, Instant.now(), latitude, longitude, PostFeedFilters.none())), new HashSet<>(walked));
    }

    @Test
    void walkingTheFallback_showsTheSamePostsOnce() throws SQLException {
        FeedRepository rankingDown = new FeedRepository() {
            @Override
            public List<FeedScorer.Facts> findCandidates(UUID viewer, Instant asOf, Double latitude, Double longitude, PostFeedFilters filters) throws SQLException {
                throw new SQLException("ranking is down");
            }
        };
        FeedService fallback = new FeedService(rankingDown, FeedConfig.DEFAULTS, Clock.systemUTC(), () -> 42);

        List<UUID> walked = walkFeed(fallback, ME, 7, null, null, FeedMode.UPCOMING).stream().map(PostCardDto::id).toList();

        assertEquals(walked.size(), new HashSet<>(walked).size(), "a post came twice");
        assertEquals(ids(feedRepository.findCandidates(ME, Instant.now(), null, null, PostFeedFilters.none())), new HashSet<>(walked));
    }
}
