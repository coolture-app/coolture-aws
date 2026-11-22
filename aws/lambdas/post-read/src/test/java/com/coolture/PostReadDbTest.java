package com.coolture;

import com.coolture.common.db.DatabaseConfig;
import com.coolture.common.db.EventTime;
import com.coolture.common.dto.FriendStancesDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MapBoundsDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostDetailDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.PostFriendsDto;
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
import com.coolture.feed.TestFacts;
import com.coolture.feed.UserVector;
import com.coolture.repository.PostReadRepository;
import com.coolture.repository.PostSort;
import com.coolture.repository.Upcoming;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Runs the real queries on the migrated database, as the read-only role post-read uses on AWS, so a missing grant fails here.
 * Needs `make up`. Run it with `make test-db`. The fixtures are removed afterwards.
 */
@EnabledIfEnvironmentVariable(named = "DB_TEST", matches = "true")
class PostReadDbTest {

    private static final UUID ME = UUID.fromString("a0000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_MODEL_POST = UUID.fromString("a0000000-0000-0000-0000-000000000200");

    private static final Set<String> PUBLIC_LIVE = Set.of("future_public", "no_end_started_2h", "multi_day_running", "commented",
        "by_followed", "by_fan", "friend_going", "friend_interested", "friend_not_for_me", "followed_going", "fan_going",
        "quiet_friend_going", "friends_crowd");
    private static final Set<String> INTERACTED = Set.of("liked", "marked", "interested", "attending");
    private static final Set<String> ENDED = Set.of("no_end_started_4h", "no_end_started_14h", "ended_1h_ago", "ended_11h_ago",
        "no_end_started_16h", "ended_13h_ago", "ended_last_year");
    private static final Set<String> HIDDEN_FROM_ME = Set.of("deleted", "by_i_blocked", "by_blocked_me");
    private static final Set<String> MY_VIEW_ONLY = Set.of("mine", "created_after_as_of");

    private static final Set<String> NOT_ENDED_FOR_ME = union(PUBLIC_LIVE, INTERACTED, MY_VIEW_ONLY);

    /** Every post that tests a rule. The database also holds demo data and the walk posts, which these checks ignore. */
    private static final Set<String> RULE_TITLES = union(PUBLIC_LIVE, INTERACTED, ENDED, HIDDEN_FROM_ME, MY_VIEW_ONLY);

    private static final MapBoundsDto AROUND_THE_FIXTURES = new MapBoundsDto(new GeoPointDto(55.0, 18.0), new GeoPointDto(54.0, 19.0));

    private final PostReadRepository postRepository = new PostReadRepository();
    private final PostReadService postService = new PostReadService(postRepository);
    private final FeedRepository feedRepository = new FeedRepository("test");
    private final FeedService feedService = new FeedService(feedRepository, FeedConfig.DEFAULTS, Clock.systemUTC(), () -> 42);

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        return Stream.of(sets).flatMap(Set::stream).collect(Collectors.toSet());
    }

    private static PostFeedFilters filters(String sortBy, boolean includePast, Instant startsFrom, Instant startsTo) {
        return new PostFeedFilters(null, null, null, null, null, startsFrom, startsTo, null, null, null,
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
        stmt.execute("DELETE FROM interests WHERE category_key = 'test_category'");
        stmt.execute("DELETE FROM interest_categories WHERE key = 'test_category'");
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
        assertEquals(PUBLIC_LIVE, ruleTitles(walkFeed(feedService, ME, 100, null, null, FeedMode.RANKED)));
    }

    @Test
    void feed_guest_isPublicAndLive_withNoPersonalFilters() throws SQLException {
        assertEquals(union(PUBLIC_LIVE, INTERACTED, Set.of("by_i_blocked", "by_blocked_me", "mine")),
            ruleTitles(walkFeed(feedService, null, 100, null, null, FeedMode.RANKED)));
    }

    @Test
    void feed_aPostCreatedAfterAsOf_appearsOnlyInALaterSession() throws SQLException {
        Instant now = Instant.now();
        Set<UUID> thisSession = ids(feedRepository.findCandidates(ME, null, null, now, null, null, PostFeedFilters.none()));
        Set<UUID> laterSession = ids(feedRepository.findCandidates(ME, null, null, now.plus(Duration.ofHours(2)), null, null, PostFeedFilters.none()));

        laterSession.removeAll(thisSession);
        assertEquals(1, laterSession.size());
    }

    private static Set<UUID> ids(List<FeedScorer.Facts> facts) {
        return facts.stream().map(FeedScorer.Facts::id).collect(Collectors.toCollection(HashSet::new));
    }

    @Test
    void feed_theFallback_appliesExactlyTheSameRules() throws SQLException {
        assertEquals(ruleTitles(walkFeed(feedService, ME, 100, null, null, FeedMode.RANKED)),
            ruleTitles(feedRepository.findUpcoming(ME, PostFeedFilters.none(), Upcoming.Position.start(Instant.now()), 1000).items()));
        assertEquals(ruleTitles(walkFeed(feedService, null, 100, null, null, FeedMode.RANKED)),
            ruleTitles(feedRepository.findUpcoming(null, PostFeedFilters.none(), Upcoming.Position.start(Instant.now()), 1000).items()));
    }

    @Test
    void feed_aPostVectorFromAnotherModel_isNeutral() throws SQLException {
        Instant now = Instant.now();
        List<FeedScorer.Facts> candidates = candidates(now);
        FeedScorer.Facts otherModel = candidates.stream().filter(f -> f.id().equals(OTHER_MODEL_POST)).findFirst().orElseThrow();

        assertNull(otherModel.similarity());
        assertEquals(FeedConfig.NEUTRAL, new FeedScorer(FeedConfig.DEFAULTS).score(otherModel, now, 42).affinity());
        assertTrue(candidates.stream().anyMatch(f -> f.similarity() != null), "vectors of the same model must still be compared");
    }

    @Test
    void feed_comparesEachPost_withThePostAverageTakenOut() throws SQLException {
        Instant now = Instant.now();
        UserVector.Parts parts = feedRepository.findVectorParts(ME, now);
        double[] viewer = UserVector.blend(parts);
        UUID post = postTitled("walk_3");
        double[] centred = embeddingOf(post).clone();
        for (int i = 0; i < centred.length; i++) {
            centred[i] -= parts.postAverage()[i];
        }

        assertEquals(cosine(centred, viewer), similarity(post, now), 1e-5);
    }

    @Test
    void feed_thePostAverage_countsOnlyPostsCreatedByAsOf() throws SQLException {
        // Half the walk posts were created an hour before the others.
        Instant beforeTheNewerHalf = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofMinutes(30));

        double[] then = feedRepository.findVectorParts(ME, beforeTheNewerHalf).postAverage();
        double[] now = feedRepository.findVectorParts(ME, Instant.now()).postAverage();

        assertFalse(Arrays.equals(then, now), "a post created after asOf moved the average");
    }

    private static double[] embeddingOf(UUID post) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT embedding::real[] FROM post_embeddings WHERE post_id = ? AND model = 'test'")) {
            stmt.setObject(1, post);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                Float[] values = (Float[]) rs.getArray(1).getArray();
                double[] result = new double[values.length];
                for (int i = 0; i < values.length; i++) {
                    result[i] = values[i];
                }
                return result;
            }
        }
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot / (length(a) * length(b));
    }

    // ===== The viewer's vector =====

    @Test
    void aPostCountsInterestedAndGoingApart_onItsPageAndItsCard() throws SQLException {
        UUID post = postTitled("walk_3");
        try {
            writeAsTheApp("INSERT INTO post_participations (post_id, user_id, type, created_at) VALUES (?, ?, 'INTERESTED', now())", post, ME);
            PostDetailDto page = postRepository.findById(post, ME).orElseThrow();
            PostCardDto card = postRepository.findFeed(searching("walk_3"), PostSort.RECENT, ME, null, 100).stream()
                .filter(c -> c.id().equals(post)).findFirst().orElseThrow();

            assertEquals(1, page.interestedCount());
            assertEquals(0, page.goingCount());
            assertEquals(1, page.participantCount());
            assertEquals(1, card.interestedCount());
            assertEquals(0, card.goingCount());
            assertEquals(1, card.participantCount());
        } finally {
            unmark(post);
        }
    }

    private static UUID postTitled(String title) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM posts WHERE title = '" + title + "'")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    /** As the app's role, because the tests read as the read-only one. */
    private static long writeAsTheApp(String sql, Object... params) throws SQLException {
        try (Connection conn = DatabaseConfig.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("RESET ROLE");
            try (PreparedStatement write = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    write.setObject(i + 1, params[i]);
                }
                if (!write.execute()) {
                    return 0;
                }
                try (ResultSet rs = write.getResultSet()) {
                    rs.next();
                    return rs.getLong(1);
                }
            } finally {
                stmt.execute("SET ROLE coolture_reader");
            }
        }
    }

    @Test
    void feed_readsTheLatestPprRunFinishedBeforeAsOfWithinItsMaxAge() throws SQLException {
        // A day ahead, so a run made just now, as make seed's last step does, is never the latest.
        Instant base = Instant.now().plus(Duration.ofDays(1));
        UUID post = postTitled("future_public");
        UUID commented = postTitled("commented");
        String run = "INSERT INTO rec_ppr_runs (finished_at) VALUES (?) RETURNING run_number";
        String score = "INSERT INTO rec_ppr_scores (run_number, user_id, post_id, score) VALUES (?, ?, ?, ?)";
        // The later run is made first, so the latest isn't simply the highest number.
        long later = writeAsTheApp(run, Timestamp.from(base.plus(Duration.ofHours(1))));
        long earlier = writeAsTheApp(run, Timestamp.from(base.minus(Duration.ofHours(1))));
        writeAsTheApp(score, later, ME, post, 0.75);
        writeAsTheApp(score, earlier, ME, post, 0.25);
        writeAsTheApp(score, earlier, UUID.fromString("a0000000-0000-0000-0000-000000000002"), commented, 0.9);
        try {
            assertEquals(0.25, pprOf(post, base), 1e-6);
            assertEquals(0.25, pprOf(post, base.plus(Duration.ofHours(1)).plusSeconds(30)), 1e-6);
            assertEquals(0.75, pprOf(post, base.plus(Duration.ofHours(2))), 1e-6);
            assertNull(pprOf(commented, base), "another user's score");
            assertNull(pprOf(post, base.plus(Duration.ofHours(1 + FeedConfig.PPR_RUN_MAX_AGE_HOURS + 1))), "a stale run");
        } finally {
            writeAsTheApp("DELETE FROM rec_ppr_scores WHERE run_number IN (?, ?)", earlier, later);
            writeAsTheApp("DELETE FROM rec_ppr_runs WHERE run_number IN (?, ?)", earlier, later);
        }
    }

    /** Written by the post-embedding worker, so this checks the read side of that table's contract. */
    @Test
    void kinds_narrowListsAndTheMap_andShowOnTheMarkAndThePage() throws SQLException {
        UUID concert = postTitled("future_public");
        UUID kidsConcert = postTitled("multi_day_running");
        UUID play = postTitled("commented");
        UUID onTheMap = postTitled("map_open");
        String kinds = "INSERT INTO rec_post_kinds (post_id, kinds, model) VALUES (?, ?::varchar[], 'test')";
        writeAsTheApp(kinds, concert, "{concerts}");
        writeAsTheApp(kinds, kidsConcert, "{kids,concerts}");
        writeAsTheApp(kinds, play, "{stage,kids}");
        writeAsTheApp(kinds, onTheMap, "{exhibitions,talks}");
        try {
            assertEquals(Set.of("future_public", "multi_day_running"), ruleTitles(walkPosts(ME, ofKinds(List.of("concerts"), false), 100)));
            assertEquals(Set.of("future_public", "multi_day_running", "commented"),
                ruleTitles(walkPosts(ME, ofKinds(List.of("concerts", "stage"), false), 100)));
            assertEquals(Set.of("multi_day_running"), ruleTitles(walkPosts(ME, ofKinds(List.of("concerts"), true), 100)));
            assertEquals(Set.of("multi_day_running", "commented"), ruleTitles(walkPosts(ME, ofKinds(null, true), 100)));
            assertEquals(Set.of(), ruleTitles(walkPosts(ME, ofKinds(List.of("no_such_kind"), false), 100)));

            assertEquals(Set.of("map_open"), marks(postRepository.findPostMarks(ofKinds(List.of("talks"), false), AROUND_THE_FIXTURES, ME, false)));
            PostMarkDto mark = postRepository.findPostMarks(ofKinds(null, false), AROUND_THE_FIXTURES, ME, false).stream()
                .filter(m -> m.id().equals(onTheMap)).findFirst().orElseThrow();
            assertEquals("exhibitions", mark.kind());
            assertEquals(List.of("exhibitions", "talks"), mark.kinds());
            PostMarkDto foundAsTalk = postRepository.findPostMarks(ofKinds(List.of("concerts", "talks"), false), AROUND_THE_FIXTURES, ME, false)
                .stream().filter(m -> m.id().equals(onTheMap)).findFirst().orElseThrow();
            assertEquals("talks", foundAsTalk.kind(), "a filtered pin names the kind it was found by");

            assertEquals(List.of("kids", "concerts"), postRepository.findById(kidsConcert, ME).orElseThrow().kinds());
            assertNull(postRepository.findById(postTitled("by_followed"), ME).orElseThrow().kinds(), "not derived yet");
        } finally {
            writeAsTheApp("DELETE FROM rec_post_kinds WHERE post_id IN (?, ?, ?, ?)", concert, kidsConcert, play, onTheMap);
        }
    }

    private static PostFeedFilters ofKinds(List<String> kinds, boolean kids) {
        return new PostFeedFilters(null, null, null, null, null, null, null, null, null, null,
            null, null, null, 20, "RECENT", false, kinds, kids);
    }

    private Double pprOf(UUID post, Instant asOf) throws SQLException {
        return candidates(asOf).stream().filter(f -> f.id().equals(post)).findFirst().orElseThrow().ppr();
    }

    private static void like(UUID post) throws SQLException {
        writeAsTheApp("INSERT INTO post_reactions (post_id, user_id, type, created_at) VALUES (?, ?, 'LIKE', now())", post, ME);
    }

    private static void unlike(UUID post) throws SQLException {
        writeAsTheApp("DELETE FROM post_reactions WHERE post_id = ? AND user_id = ?", post, ME);
    }

    /** "Nie dla mnie" at the given SQL time. */
    private static void mark(UUID post, String createdAt) throws SQLException {
        writeAsTheApp("INSERT INTO post_participations (post_id, user_id, type, created_at) VALUES (?, ?, 'NOT_INTERESTED', "
            + createdAt + ")", post, ME);
    }

    private static void unmark(UUID post) throws SQLException {
        writeAsTheApp("DELETE FROM post_participations WHERE post_id = ? AND user_id = ?", post, ME);
    }

    private double nearMark(UUID post, Instant asOf) throws SQLException {
        return candidates(asOf).stream().filter(f -> f.id().equals(post)).findFirst().orElseThrow().nearMark();
    }

    private static List<UUID> idsOf(List<PostCardDto> cards) {
        return cards.stream().map(PostCardDto::id).toList();
    }

    /**
     * A session that started before anything the test writes next. Docker's clock on Windows can drift a second or more
     * from this one, so a mark or like stamped with the database's now() could otherwise land before asOf.
     */
    private static Instant sessionStart() {
        return Instant.now().minusSeconds(10);
    }

    /** ME's candidates, as the feed's first page gathers them. */
    private List<FeedScorer.Facts> candidates(Instant asOf) throws SQLException {
        UserVector.Parts parts = feedRepository.findVectorParts(ME, asOf);
        return feedRepository.findCandidates(ME, UserVector.blend(parts), parts.postAverage(), asOf, null, null, PostFeedFilters.none());
    }

    private double similarity(UUID post, Instant asOf) throws SQLException {
        return candidates(asOf).stream().filter(f -> f.id().equals(post)).findFirst().orElseThrow().similarity();
    }

    @Test
    void feed_aLikeMadeDuringASession_leavesItsVectorAlone_andCountsFromTheNext() throws SQLException {
        Instant asOf = sessionStart();
        FeedService session = new FeedService(feedRepository, FeedConfig.DEFAULTS, Clock.fixed(asOf, ZoneOffset.UTC), () -> 42);
        UUID liked = postTitled("walk_7");
        UUID twin = postTitled("twin_of_walk_7");
        double[] before = UserVector.blend(feedRepository.findVectorParts(ME, asOf));
        double twinBefore = similarity(twin, asOf);

        List<UUID> walked = new ArrayList<>();
        FeedPage page = session.getFeed(ME, null, 7, null, null);
        walked.addAll(idsOf(page.items()));
        like(liked);
        try {
            while (page.page().nextCursor() != null) {
                page = session.getFeed(ME, page.page().nextCursor(), 7, null, null);
                walked.addAll(idsOf(page.items()));
            }
            assertEquals(walked.size(), new HashSet<>(walked).size(), "a post came twice");
            assertArrayEquals(before, UserVector.blend(feedRepository.findVectorParts(ME, asOf)), 0,
                "a like after asOf changed the session's vector");

            assertTrue(similarity(twin, Instant.now()) > twinBefore, "the next session's vector didn't move towards the like");
        } finally {
            unlike(liked);
        }
    }

    @Test
    void feed_aMarkMadeDuringASession_leavesItsPagesAlone_andPushesItsTwinDownFromTheNext() throws SQLException {
        Instant asOf = sessionStart();
        FeedService session = new FeedService(feedRepository, FeedConfig.DEFAULTS, Clock.fixed(asOf, ZoneOffset.UTC), () -> 42);
        UUID marked = postTitled("walk_7");
        UUID twin = postTitled("twin_of_walk_7");

        List<UUID> walked = new ArrayList<>();
        FeedPage page = session.getFeed(ME, null, 7, null, null);
        walked.addAll(idsOf(page.items()));
        mark(marked, "now()");
        try {
            while (page.page().nextCursor() != null) {
                page = session.getFeed(ME, page.page().nextCursor(), 7, null, null);
                walked.addAll(idsOf(page.items()));
            }
            assertEquals(walked.size(), new HashSet<>(walked).size(), "a post came twice");
            assertEquals(0, nearMark(twin, asOf), "a mark after asOf acted on its own session");

            Instant next = Instant.now();
            assertEquals(1, nearMark(twin, next), 1e-6);
            assertFalse(candidates(next).stream().anyMatch(f -> f.id().equals(marked)), "the marked post is still a candidate");
            FeedConfig d = FeedConfig.DEFAULTS;
            FeedScorer scorer = new FeedScorer(new FeedConfig(d.affinity(), d.proximity(), d.time(), d.popularity(), 0, d.notForMe(), d.ppr()));
            FeedScorer.Facts twinFacts = candidates(next).stream().filter(f -> f.id().equals(twin)).findFirst().orElseThrow();
            FeedScorer.Facts unmarked = TestFacts.copyOf(twinFacts).nearMark(0).build();
            assertTrue(scorer.score(twinFacts, next, 1).total() < scorer.score(unmarked, next, 1).total());
        } finally {
            unmark(marked);
        }
    }

    @Test
    void feed_aMarkFadesByHalfEachHalfLife_andIsLeftOutWhenVeryOld() throws SQLException {
        UUID marked = postTitled("walk_7");
        UUID twin = postTitled("twin_of_walk_7");
        mark(marked, "now()");
        try {
            // An hour more, so a database clock ahead of this one still counts whole days.
            Instant halfLifeLater = Instant.now().plus(Duration.ofSeconds((long) (FeedConfig.STANCE_HALF_LIFE_DAYS * 86400)))
                .plus(Duration.ofHours(1));
            Instant tooOld = Instant.now().plus(Duration.ofDays(FeedConfig.STANCE_MAX_AGE_DAYS + 1));

            assertEquals(0.5, nearMark(twin, halfLifeLater), 1e-6);
            assertEquals(0, nearMark(twin, tooOld));
        } finally {
            unmark(marked);
        }
    }

    @Test
    void vector_leavesMarksOut() throws SQLException {
        Instant asOf = Instant.now();
        UUID marked = postTitled("walk_7");
        double[] before = UserVector.blend(feedRepository.findVectorParts(ME, asOf));
        mark(marked, "now() - interval '1 second'");
        try {
            assertArrayEquals(before, UserVector.blend(feedRepository.findVectorParts(ME, asOf)), 0);
        } finally {
            unmark(marked);
        }
    }

    @Test
    void vector_isBuiltFromInterestsAlone_whenThereAreNoStances() throws SQLException {
        UserVector.Parts parts = feedRepository.findVectorParts(UUID.fromString("a0000000-0000-0000-0000-000000000002"), Instant.now());

        assertTrue(parts.stances().isEmpty());
        assertEquals(List.of(1), parts.categories().stream().map(UserVector.Category::picks).toList());
        assertEquals(768, UserVector.blend(parts).length);
    }

    @Test
    void vector_aStanceFadesByHalfEachHalfLife_andIsLeftOutWhenVeryOld() throws SQLException {
        Instant halfLifeLater = Instant.now().plus(Duration.ofSeconds((long) (FeedConfig.STANCE_HALF_LIFE_DAYS * 86400)));
        Instant tooOld = Instant.now().plus(Duration.ofDays(FeedConfig.STANCE_MAX_AGE_DAYS + 1));

        UserVector.Stance now = feedRepository.findVectorParts(ME, Instant.now()).stances().getFirst();
        UserVector.Stance later = feedRepository.findVectorParts(ME, halfLifeLater).stances().getFirst();

        assertEquals(1.0, now.amount(), 0.001);
        assertEquals(0.5, later.amount(), 0.001);
        assertEquals(0.5 * length(now.sum()), length(later.sum()), 0.001 * length(now.sum()));
        assertTrue(feedRepository.findVectorParts(ME, tooOld).stances().isEmpty());
    }

    private static double length(double[] vector) {
        double sum = 0;
        for (double v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    @Test
    void vector_isNullForAGuest() throws SQLException {
        assertNull(UserVector.blend(feedRepository.findVectorParts(null, Instant.now())));
    }

    // ===== /posts rules =====

    @ParameterizedTest
    @CsvSource({"RECENT", "POPULAR"})
    void browse_hidesEndedEvents_unlessOptedIn(String sort) throws SQLException {
        assertEquals(NOT_ENDED_FOR_ME, ruleTitles(walkPosts(ME, filters(sort, false, null, null), 100)));
        assertEquals(union(NOT_ENDED_FOR_ME, ENDED), ruleTitles(walkPosts(ME, filters(sort, true, null, null), 100)));
    }

    @Test
    void browse_aChosenDateShowsEndedEvents() throws SQLException {
        Instant longAgo = Instant.now().minus(Duration.ofDays(500));
        Instant farAway = Instant.now().plus(Duration.ofDays(500));

        assertEquals(union(NOT_ENDED_FOR_ME, ENDED), ruleTitles(walkPosts(ME, filters("RECENT", false, longAgo, null), 100)));
        assertEquals(union(NOT_ENDED_FOR_ME, ENDED), ruleTitles(walkPosts(ME, filters("RECENT", false, null, farAway), 100)));
    }

    @Test
    void browse_upcoming_neverShowsAnEndedEvent() throws SQLException {
        Instant longAgo = Instant.now().minus(Duration.ofDays(500));

        assertEquals(NOT_ENDED_FOR_ME, ruleTitles(walkPosts(ME, filters("UPCOMING", false, null, null), 100)));
        assertEquals(NOT_ENDED_FOR_ME, ruleTitles(walkPosts(ME, filters("UPCOMING", true, null, null), 100)));
        assertEquals(NOT_ENDED_FOR_ME, ruleTitles(walkPosts(ME, filters("UPCOMING", true, longAgo, null), 100)));
    }

    @Test
    void browse_past_showsOnlyEndedEvents_latestEndFirst_acrossPages() throws SQLException {
        List<PostCardDto> cards = walkPosts(ME, filters("PAST", false, null, null), 2).stream()
            .filter(c -> RULE_TITLES.contains(c.title())).toList();

        assertEquals(ENDED, ruleTitles(cards));
        for (int i = 1; i < cards.size(); i++) {
            Instant before = EventTime.end(cards.get(i - 1).startsAt(), cards.get(i - 1).endsAt(), cards.get(i - 1).openEnded());
            Instant after = EventTime.end(cards.get(i).startsAt(), cards.get(i).endsAt(), cards.get(i).openEnded());
            assertFalse(after.isAfter(before), cards.get(i).title() + " after " + cards.get(i - 1).title());
        }
    }

    @Test
    void browse_aFriendsStances_onlyWhileTheyShare_andNeverTheirNotForMe() throws SQLException {
        assertEquals(Set.of("friend_going", "friends_crowd"), titlesOf(walkPosts(ME, stancesOf("6", "TAKES_PART"), 100)));
        assertEquals(Set.of("friend_interested"), titlesOf(walkPosts(ME, stancesOf("6", "INTERESTED"), 100)));
        // quiet_friend doesn't share, and `followed` isn't a friend.
        assertEquals(Set.of(), titlesOf(walkPosts(ME, stancesOf("8", "TAKES_PART"), 100)));
        assertEquals(Set.of(), titlesOf(walkPosts(ME, stancesOf("3", "TAKES_PART"), 100)));

        assertThrows(IllegalArgumentException.class, () -> walkPosts(ME, stancesOf("6", "NOT_INTERESTED"), 100));
        assertThrows(SecurityException.class, () -> walkPosts(null, stancesOf("6", "TAKES_PART"), 100));
    }

    private static PostFeedFilters stancesOf(String who, String type) {
        return new PostFeedFilters(null, null, null, null, null, null, null, null, null, null,
            List.of(type), null, null, 20, "UPCOMING", false, null, false, UUID.fromString("a0000000-0000-0000-0000-00000000000" + who));
    }

    private static Set<String> titlesOf(List<PostCardDto> cards) {
        return cards.stream().map(PostCardDto::title).collect(Collectors.toSet());
    }

    @Test
    void browse_guest_seesPublicPostsWithoutBlocks() throws SQLException {
        assertEquals(union(PUBLIC_LIVE, INTERACTED, Set.of("by_i_blocked", "by_blocked_me", "mine", "created_after_as_of")),
            ruleTitles(walkPosts(null, filters("RECENT", false, null, null), 100)));
    }

    @Test
    void browse_cardsCarryTheViewersOwnReactionAndParticipation() throws SQLException {
        List<PostCardDto> cards = walkPosts(ME, filters("RECENT", false, null, null), 100);

        assertEquals(ReactionType.LIKE, card(cards, "liked").myReaction());
        assertEquals(ParticipationType.INTERESTED, card(cards, "interested").myParticipation());
        assertEquals(ParticipationType.NOT_INTERESTED, card(cards, "marked").myParticipation());
    }

    private static PostFeedFilters stances(String... types) {
        return new PostFeedFilters(null, null, null, null, null, null, null, null, null, null,
            List.of(types), null, null, 20, "STANCE_NEWEST", true);
    }

    @Test
    void browse_theViewersMarks_newestFirst_andOnlyTheirOwn() throws SQLException {
        UUID older = postTitled("walk_11");
        UUID newer = postTitled("walk_12");
        mark(older, "now() - interval '2 hours'");
        mark(newer, "now() - interval '1 hour'");
        try {
            List<String> walked = titles(walkPosts(ME, stances("NOT_INTERESTED"), 1));

            assertEquals(List.of("marked", "walk_12", "walk_11"), walked);
            assertTrue(walkPosts(UUID.fromString("a0000000-0000-0000-0000-000000000002"), stances("NOT_INTERESTED"), 20).isEmpty());
        } finally {
            unmark(older);
            unmark(newer);
        }
    }

    private static PostCardDto card(List<PostCardDto> cards, String title) {
        return cards.stream().filter(c -> title.equals(c.title())).findFirst().orElseThrow();
    }

    // ===== People I follow and friends =====

    /** Mine is left out: the feed never shows it, and the others show it only by author. */
    private static final Set<String> FOLLOWED_AND_FRIENDS = Set.of("by_followed", "friend_going", "friend_interested", "friends_crowd");

    private static PostFeedFilters followed() {
        return new PostFeedFilters(null, null, null, true, null, null, null, null, null, null,
            null, null, null, 20, "RECENT", false);
    }

    @Test
    void followed_keepsPostsByPeopleIFollow_andWhatFriendsAreInterestedInOrGoTo() throws SQLException {
        assertEquals(FOLLOWED_AND_FRIENDS, ruleTitles(walkPosts(ME, followed(), 100)));
        assertEquals(FOLLOWED_AND_FRIENDS, ruleTitles(walkFeed(feedService, ME, 100, null, null, followed(), FeedMode.RANKED)));
    }

    @Test
    void followed_isEmptyForAGuest() throws SQLException {
        assertTrue(ruleTitles(walkPosts(null, followed(), 100)).isEmpty());
    }

    @Test
    void cards_showFriendsStances_butNotOneWayFollows_quietFriends_orNieDlaMnie() throws SQLException {
        List<PostCardDto> cards = walkPosts(ME, filters("RECENT", false, null, null), 100);

        FriendStancesDto going = card(cards, "friend_going").friendStances();
        assertEquals(1, going.going().count());
        assertEquals("friend", going.going().people().get(0).username());
        assertNull(going.interested());
        assertEquals("friend", card(cards, "friend_interested").friendStances().interested().people().get(0).username());
        for (String title : List.of("friend_not_for_me", "followed_going", "fan_going", "quiet_friend_going", "future_public")) {
            assertNull(card(cards, title).friendStances(), title);
        }
    }

    @Test
    void friendsStances_reachTheFeed_andThePostPage_butNotAGuest() throws SQLException {
        UUID post = postTitled("friend_going");

        assertNotNull(card(walkFeed(feedService, ME, 100, null, null, FeedMode.RANKED), "friend_going").friendStances());
        assertNotNull(postRepository.findById(post, ME).orElseThrow().friendStances());
        assertNull(postRepository.findById(post, null).orElseThrow().friendStances());
    }

    private static List<String> usernames(List<FriendStancesDto.Friend> friends) {
        return friends.stream().map(FriendStancesDto.Friend::username).toList();
    }

    @Test
    void aCard_namesTheNewestTwoFriends_andThePostsFriendsList_namesThemAll() throws SQLException {
        UUID crowd = postTitled("friends_crowd");

        FriendStancesDto card = card(walkPosts(ME, filters("RECENT", false, null, null), 100), "friends_crowd").friendStances();
        assertEquals(3, card.going().count());
        assertEquals(List.of("friend_3", "friend_2"), usernames(card.going().people()));
        assertNull(card.interested());

        PostFriendsDto all = postService.getFriends(crowd, ME);
        assertEquals(List.of("friend_3", "friend_2", "friend"), usernames(all.going()));
        assertEquals(List.of(), all.interested());
    }

    @Test
    void thePostsFriendsList_leavesOutOneWayFollows_quietFriends_andNieDlaMnie() throws SQLException {
        assertEquals(List.of("friend"), usernames(postService.getFriends(postTitled("friend_going"), ME).going()));
        assertEquals(List.of("friend"), usernames(postService.getFriends(postTitled("friend_interested"), ME).interested()));
        for (String title : List.of("followed_going", "fan_going", "quiet_friend_going", "friend_not_for_me")) {
            PostFriendsDto friends = postService.getFriends(postTitled(title), ME);
            assertEquals(new PostFriendsDto(List.of(), List.of()), friends, title);
        }
    }

    @Test
    void thePostsFriendsList_isEmptyForAGuest_andNotFoundForADeletedOrMissingPost() throws SQLException {
        assertEquals(new PostFriendsDto(List.of(), List.of()), postService.getFriends(postTitled("friends_crowd"), null));
        assertTrue(postRepository.findFriends(postTitled("deleted"), ME).isEmpty());
        assertTrue(postRepository.findFriends(UUID.randomUUID(), ME).isEmpty());
    }

    @Test
    void thePostPage_andItsFriendsList_areNotFound_acrossABlock_eitherWay() throws SQLException {
        for (String title : List.of("by_i_blocked", "by_blocked_me")) {
            assertTrue(postRepository.findById(postTitled(title), ME).isEmpty(), title);
            assertTrue(postRepository.findFriends(postTitled(title), ME).isEmpty(), title);
            assertTrue(postRepository.findById(postTitled(title), null).isPresent(), title + " for a guest");
            assertTrue(postRepository.findFriends(postTitled(title), null).isPresent(), title + " for a guest");
        }
    }

    @Test
    void aStance_isntShownToTheOneWhoTookIt_orToAStranger() throws SQLException {
        UUID friend = UUID.fromString("a0000000-0000-0000-0000-000000000006");
        UUID stranger = UUID.fromString("a0000000-0000-0000-0000-000000000002");

        assertNull(card(walkPosts(friend, filters("RECENT", false, null, null), 100), "friend_going").friendStances());
        assertNull(card(walkPosts(stranger, filters("RECENT", false, null, null), 100), "friend_going").friendStances());
    }

    // ===== Map rules =====

    @Test
    void map_appliesBlocks_andHidesEndedEvents() throws SQLException {
        assertEquals(Set.of("map_open", "map_mine"),
            marks(postRepository.findPostMarks(filters(null, false, null, null), AROUND_THE_FIXTURES, ME, false)));
        assertEquals(Set.of("map_open", "map_mine", "map_ended_1h_ago", "map_ended_last_year"),
            marks(postRepository.findPostMarks(filters(null, true, null, null), AROUND_THE_FIXTURES, ME, false)));
        assertEquals(Set.of("map_open", "map_mine", "map_ended_1h_ago", "map_ended_last_year"),
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
        return new PostFeedFilters(null, null, null, null, null, null, null, 54.35, 18.65, radiusKm,
            null, null, null, 20, sortBy, false);
    }

    private static PostFeedFilters searching(String q) {
        return new PostFeedFilters(q, null, null, null, null, null, null, null, null, null,
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

    private static final UUID SEARCHER = UUID.fromString("a0000000-0000-0000-0000-000000000013");

    /** The searcher's posts that {@code q} finds, since the database may hold others with the same words. */
    private Set<String> searched(String q) throws SQLException {
        return new HashSet<>(titles(walkPosts(ME, by(SEARCHER, "RECENT", null, null, q), 100)));
    }

    @Test
    void aSearch_ignoresDiacritics_onBothSides() throws SQLException {
        assertEquals(Set.of("Śpiew gospel"), searched("spiew"));
        assertEquals(Set.of("Śpiew gospel"), searched("ŚPIEW"));
        assertEquals(Set.of("Muzyka w filharmonii"), searched("lodz"));
    }

    @Test
    void aSearch_findsAnotherFormOfAWord() throws SQLException {
        assertEquals(Set.of("Wieczór koncertowy"), searched("koncertu"));
    }

    @Test
    void aSearch_needsEveryWord_inAnyField_inAnyOrder() throws SQLException {
        assertEquals(Set.of("Muzyka w filharmonii"), searched("muzyka klasyczna"));
        assertEquals(Set.of("Muzyka w filharmonii"), searched("Łódź  muzyka"));
        assertEquals(Set.of(), searched("muzyka opera"));
    }

    @Test
    void aSearch_takesPercentAsALetter() throws SQLException {
        assertEquals(Set.of("Zniżka 50%"), searched("50%"));
    }

    @Test
    void mapMarks_carryThePlaceAndTheStart() throws SQLException {
        PostMarkDto far = postRepository.findPostMarks(searching("near_far_1"), AROUND_THE_FIXTURES, ME, false).get(0);

        assertEquals("Hala Fixturowa", far.venueName());
        assertEquals("Fixturowo", far.city());
        assertNotNull(far.startsAt());
        assertTrue(far.excerpt() == null || far.excerpt().length() <= 200);
    }

    @Test
    void mapMarks_carryTheViewersOwnReactionAndStance_andNoneForAGuest() throws SQLException {
        PostMarkDto before = postRepository.findPostMarks(searching("near_far_1"), AROUND_THE_FIXTURES, ME, false).get(0);
        try {
            like(before.id());
            mark(before.id(), "now()");
            PostMarkDto mine = postRepository.findPostMarks(searching("near_far_1"), AROUND_THE_FIXTURES, ME, false).get(0);
            PostMarkDto guest = postRepository.findPostMarks(searching("near_far_1"), AROUND_THE_FIXTURES, null, false).get(0);

            assertEquals(ReactionType.LIKE, mine.myReaction());
            assertEquals(ParticipationType.NOT_INTERESTED, mine.myParticipation());
            assertNull(guest.myReaction());
            assertNull(guest.myParticipation());
        } finally {
            unlike(before.id());
            unmark(before.id());
        }
    }

    @Test
    void feed_includePast_letsEndedEventsIn_andKeepsHidingWhatIsMine() throws SQLException {
        assertEquals(union(PUBLIC_LIVE, ENDED),
            ruleTitles(walkFeed(feedService, ME, 100, null, null, filters(null, true, null, null), FeedMode.RANKED)));
    }

    @Test
    void feed_theFallback_appliesTheSameFilters() throws SQLException {
        PostFeedFilters filters = around(null, 1.0);
        assertEquals(new HashSet<>(distances(titles(walkFeed(feedService, ME, 7, null, null, filters, FeedMode.RANKED)))),
            new HashSet<>(distances(titles(feedRepository.findUpcoming(ME, filters, Upcoming.Position.start(Instant.now()), 1000).items()))));
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
    @EnumSource(value = PostSort.class, mode = EnumSource.Mode.EXCLUDE, names = {"NEAREST", "STANCE_NEWEST", "UPCOMING", "PAST"})
    void walkingASort_showsEveryPostOnceInOrder(PostSort sort) throws SQLException {
        for (boolean includePast : List.of(false, true)) {
            PostFeedFilters filters = filters(sort.name(), includePast, null, null);
            List<UUID> everything = postRepository.findFeed(filters, sort, ME, null, 1_000_000).stream().map(PostCardDto::id).toList();
            List<UUID> walked = walkPosts(ME, filters, 7).stream().map(PostCardDto::id).toList();

            assertTrue(everything.size() > 60, "the walk must cross the tied posts");
            assertEquals(everything, walked);
        }
    }

    /** walk_1 has one like; walk_6 none, until two people mark it. Popularne counted likes alone before. */
    @Test
    void browse_popular_countsStancesLikeTheFeed() throws SQLException {
        UUID liked = postTitled("walk_1");
        UUID marked = postTitled("walk_6");
        try {
            writeAsTheApp("UPDATE posts SET participant_count = 2 WHERE id = ?", marked);

            List<UUID> order = idsOf(walkPosts(ME, filters("POPULAR", false, null, null), 7));
            assertTrue(order.indexOf(marked) < order.indexOf(liked), "two stances outrank one like");
            assertEquals(2, engagementOf(marked));
        } finally {
            writeAsTheApp("UPDATE posts SET participant_count = 0 WHERE id = ?", marked);
        }
    }

    private int engagementOf(UUID post) throws SQLException {
        return candidates(Instant.now()).stream().filter(f -> f.id().equals(post)).findFirst().orElseThrow().engagement();
    }

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void walkingTheFeed_showsEveryCandidateOnce(boolean signedIn, boolean withLocation) throws SQLException {
        UUID viewer = signedIn ? ME : null;
        Double latitude = withLocation ? 54.35 : null;
        Double longitude = withLocation ? 18.65 : null;

        List<UUID> walked = walkFeed(feedService, viewer, 7, latitude, longitude, FeedMode.RANKED).stream().map(PostCardDto::id).toList();

        assertEquals(walked.size(), new HashSet<>(walked).size(), "a post came twice");
        assertEquals(ids(feedRepository.findCandidates(viewer, null, null, Instant.now(), latitude, longitude, PostFeedFilters.none())), new HashSet<>(walked));
    }

    @Test
    void walkingTheFallback_showsTheSamePostsOnce() throws SQLException {
        FeedRepository rankingDown = new FeedRepository("test") {
            @Override
            public List<FeedScorer.Facts> findCandidates(UUID viewer, double[] viewerVector, double[] postAverage, Instant asOf,
                                                         Double latitude, Double longitude, PostFeedFilters filters) throws SQLException {
                throw new SQLException("ranking is down");
            }
        };
        FeedService fallback = new FeedService(rankingDown, FeedConfig.DEFAULTS, Clock.systemUTC(), () -> 42);

        List<UUID> walked = walkFeed(fallback, ME, 7, null, null, FeedMode.UPCOMING).stream().map(PostCardDto::id).toList();

        assertEquals(walked.size(), new HashSet<>(walked).size(), "a post came twice");
        assertEquals(ids(feedRepository.findCandidates(ME, null, null, Instant.now(), null, null, PostFeedFilters.none())), new HashSet<>(walked));
    }

    @Test
    void walkingUpcoming_showsEveryPostOnce_inTheSameOrderAtAnyPageSize() throws SQLException {
        PostFeedFilters filters = filters("UPCOMING", false, null, null);
        List<UUID> inOnePage = walkPosts(ME, filters, 100).stream().map(PostCardDto::id).toList();
        List<UUID> walked = walkPosts(ME, filters, 7).stream().map(PostCardDto::id).toList();

        assertTrue(inOnePage.size() > 60, "the walk must cross the tied posts");
        assertEquals(inOnePage, walked);
        assertEquals(walked.size(), new HashSet<>(walked).size(), "a post came twice");
    }

    // ===== Dates by overlap and events with no end date =====

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"2026-11-16T18:00:00Z, false", "null, false", "null, true"})
    void eventEnd_inTheDatabase_isEventTimeEnd(String endsAt, boolean openEnded) throws SQLException {
        Instant start = Instant.parse("2026-11-14T18:00:00Z");
        Instant end = endsAt == null ? null : Instant.parse(endsAt);
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT CASE WHEN isfinite(e) THEN e END FROM (SELECT event_end(?::timestamptz, ?::timestamptz, ?) AS e) x")) {
            stmt.setTimestamp(1, Timestamp.from(start));
            stmt.setTimestamp(2, end == null ? null : Timestamp.from(end));
            stmt.setBoolean(3, openEnded);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                Timestamp got = rs.getTimestamp(1);
                assertEquals(EventTime.end(start, end, openEnded), got == null ? null : got.toInstant());
            }
        }
    }

    private static final UUID TIMEKEEPER = UUID.fromString("a0000000-0000-0000-0000-000000000011");
    private static final UUID RHYTHM = UUID.fromString("a0000000-0000-0000-0000-000000000012");
    private static final Instant WINDOW_FROM = Instant.parse("2040-06-06T00:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2040-06-07T23:59:59.999Z");

    private static PostFeedFilters by(UUID author, String sortBy, Instant from, Instant to, String q) {
        return new PostFeedFilters(q, author, null, null, null, from, to, null, null, null,
            null, null, null, 100, sortBy, false);
    }

    private Set<String> timeTitles(Instant from, Instant to) throws SQLException {
        return walkPosts(ME, by(TIMEKEEPER, "RECENT", from, to, null), 100).stream()
            .map(PostCardDto::title).collect(Collectors.toSet());
    }

    @Test
    void dates_matchAPostRunningAtAnyMomentInTheWindow_atEachEdge() throws SQLException {
        assertEquals(Set.of("time_ends_at_from", "time_starts_at_to", "time_spans_the_window", "time_default_end_inside",
            "time_no_end_date_before"), timeTitles(WINDOW_FROM, WINDOW_TO));
    }

    @Test
    void dates_eitherSideIsOptional() throws SQLException {
        assertEquals(Set.of("time_ends_at_from", "time_starts_at_to", "time_starts_after_to", "time_spans_the_window",
            "time_default_end_inside", "time_no_end_date_before", "time_no_end_date_after"), timeTitles(WINDOW_FROM, null));
        assertEquals(Set.of("time_ends_at_from", "time_ends_before_from", "time_starts_at_to", "time_spans_the_window",
            "time_default_end_inside", "time_default_end_before", "time_no_end_date_before"), timeTitles(null, WINDOW_TO));
    }

    @Test
    void dates_aPostWithNoEndDate_isInEveryLaterWindow() throws SQLException {
        Instant decadeLater = Instant.parse("2050-01-01T00:00:00Z");
        assertEquals(Set.of("time_no_end_date_before", "time_no_end_date_after"),
            timeTitles(decadeLater, decadeLater.plus(Duration.ofDays(1))));
    }

    @Test
    void dates_meanTheSameInTheFeed() throws SQLException {
        PostFeedFilters filters = by(TIMEKEEPER, null, WINDOW_FROM, WINDOW_TO, null);
        Set<String> feed = walkFeed(feedService, ME, 100, null, null, filters, FeedMode.RANKED).stream()
            .map(PostCardDto::title).collect(Collectors.toSet());

        assertEquals(timeTitles(WINDOW_FROM, WINDOW_TO), feed);
    }

    @Test
    void cards_mapMarksAndThePostPage_sayWhenThereIsNoEndDate() throws SQLException {
        PostCardDto card = walkPosts(ME, by(TIMEKEEPER, "RECENT", null, null, null), 100).stream()
            .filter(c -> c.title().equals("time_no_end_date_before")).findFirst().orElseThrow();

        assertTrue(card.openEnded());
        assertNull(card.endsAt());
        assertTrue(postRepository.findById(card.id(), ME).orElseThrow().openEnded());
    }

    // ===== Nadchodzące =====

    private List<String> upcomingTitles(PostFeedFilters filters, int pageSize) throws SQLException {
        return walkPosts(ME, filters, pageSize).stream().map(PostCardDto::title).toList();
    }

    @ParameterizedTest
    @CsvSource({"1", "3", "5", "100"})
    void upcoming_everyFifthCardIsRunning_endingSoonestFirst_noEndDateLast(int pageSize) throws SQLException {
        assertEquals(List.of("rhythm_dated_1", "rhythm_dated_2", "rhythm_dated_3", "rhythm_dated_4", "rhythm_running_1",
                "rhythm_dated_5", "rhythm_dated_6", "rhythm_dated_7", "rhythm_dated_8", "rhythm_running_2",
                "rhythm_dated_9", "rhythm_running_open"),
            upcomingTitles(by(RHYTHM, "UPCOMING", null, null, null), pageSize),
            "the dated stream runs out at the 11th card, so the running stream continues alone");
    }

    @ParameterizedTest
    @CsvSource({"1", "4", "100"})
    void upcoming_whenTheRunningStreamRunsOut_theDatedOneContinues(int pageSize) throws SQLException {
        assertEquals(List.of("rhythm_dated_1", "rhythm_dated_2", "rhythm_dated_3", "rhythm_dated_4", "rhythm_running_1",
                "rhythm_dated_5", "rhythm_dated_6", "rhythm_dated_7", "rhythm_dated_8", "rhythm_dated_9"),
            upcomingTitles(by(RHYTHM, "UPCOMING", null, null, "rytm"), pageSize));
    }

    @Test
    void upcoming_aLaterFrom_movesTheMoment() throws SQLException {
        Instant from = Instant.now().plus(Duration.ofDays(3)).plus(Duration.ofHours(12));

        assertEquals(List.of("rhythm_dated_4", "rhythm_dated_5", "rhythm_dated_6", "rhythm_dated_7", "rhythm_running_open",
                "rhythm_dated_8", "rhythm_dated_9"),
            upcomingTitles(by(RHYTHM, "UPCOMING", from, null, null), 2));
    }

    @Test
    void upcoming_theFeedsFallback_mixesTheSameWay() throws SQLException {
        List<String> fallback = feedRepository.findUpcoming(null, by(RHYTHM, null, null, null, null),
            Upcoming.Position.start(Instant.now()), 100).items().stream().map(PostCardDto::title).toList();

        assertEquals(upcomingTitles(by(RHYTHM, "UPCOMING", null, null, null), 100), fallback);
    }
}
