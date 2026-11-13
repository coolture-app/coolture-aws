package com.coolture.feed;

import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostFeedFilters;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedServiceTest {

    private static final Instant START = Instant.parse("2026-11-02T18:00:00Z");
    private static final UUID VIEWER = UUID.randomUUID();

    /** An in-memory database of candidates. Set a failure to make the ranking or the fallback query throw. */
    private static class FakeRepository extends FeedRepository {
        List<FeedScorer.Facts> candidates = new ArrayList<>();
        SQLException rankingFailure;
        RuntimeException rankingBug;
        SQLException upcomingFailure;
        final List<Instant> asOfs = new ArrayList<>();
        final List<Double> latitudes = new ArrayList<>();
        final List<UUID> viewers = new ArrayList<>();
        final List<double[]> postAverages = new ArrayList<>();
        double[] postAverage;

        @Override
        public UserVector.Parts findVectorParts(UUID viewer, Instant asOf) {
            return new UserVector.Parts(List.of(), null, List.of(), null, postAverage);
        }

        @Override
        public List<FeedScorer.Facts> findCandidates(UUID viewer, double[] viewerVector, double[] postAverage, Instant asOf,
                                                     Double latitude, Double longitude, PostFeedFilters filters) throws SQLException {
            postAverages.add(postAverage);
            asOfs.add(asOf);
            latitudes.add(latitude);
            viewers.add(viewer);
            if (rankingFailure != null) throw rankingFailure;
            if (rankingBug != null) throw rankingBug;
            return candidates;
        }

        @Override
        public List<PostCardDto> findUpcoming(UUID viewer, Instant asOf, PostFeedFilters filters, Instant afterStartsAt, UUID afterId, int limit) throws SQLException {
            asOfs.add(asOf);
            if (upcomingFailure != null) throw upcomingFailure;
            Comparator<FeedScorer.Facts> soonest = Comparator.comparing(FeedScorer.Facts::startsAt).thenComparing(FeedScorer.Facts::id);
            FeedScorer.Facts after = afterStartsAt == null ? null : new FeedScorer.Facts(afterId, afterStartsAt, 0, null, null);
            return candidates.stream()
                .filter(f -> after == null || soonest.compare(f, after) > 0)
                .sorted(soonest)
                .limit(limit)
                .map(FeedServiceTest::card)
                .toList();
        }

        @Override
        public List<PostCardDto> findCards(UUID viewer, List<UUID> ids) {
            // Reversed, because the service must restore the order itself.
            return candidates.stream().filter(f -> ids.contains(f.id())).map(FeedServiceTest::card).toList().reversed();
        }
    }

    private static PostCardDto card(FeedScorer.Facts f) {
        return new PostCardDto(f.id(), null, null, "t", null, null, f.startsAt(), null, List.of(),
            f.engagement(), 0, 0, null, null, null, START, null, null, null, null, null, null);
    }

    private static List<FeedScorer.Facts> posts(int count) {
        // Few distinct start times and likes, so ties on everything but jitter are common.
        return IntStream.range(0, count)
            .mapToObj(i -> new FeedScorer.Facts(UUID.randomUUID(), START.plus(Duration.ofDays(i % 3)), i % 2, null, null))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private final FakeRepository repository = new FakeRepository();

    /** Advances by an hour on every read, so anything scoring at the live clock would drift. */
    private final Clock movingClock = new Clock() {
        private Instant now = START;

        @Override
        public Instant instant() {
            now = now.plus(Duration.ofHours(1));
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    };

    private FeedService service(FeedConfig config, long seed) {
        return new FeedService(repository, config, movingClock, () -> seed);
    }

    private FeedService service() {
        return service(FeedConfig.DEFAULTS, 42);
    }

    private List<UUID> walk(FeedService service, UUID viewer, int limit) throws SQLException {
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            FeedPage page = service.getFeed(viewer, cursor, limit, null, null);
            assertEquals(FeedMode.RANKED, page.mode());
            page.items().forEach(c -> seen.add(c.id()));
            if (!page.page().hasMore()) {
                assertNull(page.page().nextCursor());
                return seen;
            }
            cursor = page.page().nextCursor();
        }
        throw new AssertionError("The walk never ended");
    }

    @Test
    void walkingEveryPage_showsEachPostOnce() throws SQLException {
        repository.candidates = posts(137);

        List<UUID> seen = walk(service(), VIEWER, 20);

        assertEquals(137, seen.size());
        assertEquals(new HashSet<>(repository.candidates.stream().map(FeedScorer.Facts::id).toList()), new HashSet<>(seen));
    }

    @Test
    void comparesPostsWithThePostAverageTheVectorWasBuiltWith() throws SQLException {
        repository.candidates = posts(3);
        repository.postAverage = new double[]{0.5, 0.25};

        service().getFeed(VIEWER, null, 20, null, null);

        assertSame(repository.postAverage, repository.postAverages.getFirst());
    }

    @Test
    void walkingWithExactMultipleOfLimit_endsWithHasMoreFalse() throws SQLException {
        repository.candidates = posts(40);

        assertEquals(40, walk(service(), VIEWER, 20).size());
    }

    @Test
    void hasMore_isRightOnEachSideOfTheLimit() throws SQLException {
        repository.candidates = posts(21);
        FeedPage first = service().getFeed(VIEWER, null, 20, null, null);
        assertEquals(20, first.items().size());
        assertTrue(first.page().hasMore());

        repository.candidates = posts(20);
        FeedPage only = service().getFeed(VIEWER, null, 20, null, null);
        assertEquals(20, only.items().size());
        assertFalse(only.page().hasMore());
        assertNull(only.page().nextCursor());
    }

    @Test
    void noCandidates_givesAnEmptyLastPage() throws SQLException {
        FeedPage page = service().getFeed(VIEWER, null, 20, null, null);

        assertEquals(FeedMode.RANKED, page.mode());
        assertTrue(page.items().isEmpty());
        assertFalse(page.page().hasMore());
    }

    @Test
    void itemsArriveInScoreOrder() throws SQLException {
        repository.candidates = posts(30);
        FeedScorer scorer = new FeedScorer(FeedConfig.DEFAULTS);
        FeedPage page = service().getFeed(VIEWER, null, 30, null, null);
        Instant asOf = repository.asOfs.getFirst();

        List<Double> scores = page.items().stream()
            .map(c -> repository.candidates.stream().filter(f -> f.id().equals(c.id())).findFirst().orElseThrow())
            .map(f -> scorer.score(f, asOf, 42).total())
            .toList();

        assertEquals(scores.stream().sorted(Comparator.reverseOrder()).toList(), scores);
    }

    @Test
    void asOfStaysFrozenAcrossPages_soTheClockCantMoveScores() throws SQLException {
        repository.candidates = posts(60);

        walk(service(), VIEWER, 10);

        assertEquals(1, new HashSet<>(repository.asOfs).size());
        assertTrue(repository.asOfs.size() > 1);
    }

    @Test
    void scoresUseAsOf_evenWhenTheClockHasMovedPastEveryStart() throws SQLException {
        repository.candidates = posts(60);
        Instant[] now = {START};
        Clock jumping = new Clock() {
            @Override
            public Instant instant() {
                Instant current = now[0];
                now[0] = current.plus(Duration.ofDays(30));
                return current;
            }

            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        };
        FeedScorer scorer = new FeedScorer(FeedConfig.DEFAULTS);

        List<UUID> actual = new FeedService(repository, FeedConfig.DEFAULTS, jumping, () -> 42)
            .getFeed(VIEWER, null, 60, null, null).items().stream().map(PostCardDto::id).toList();

        List<UUID> atAsOf = repository.candidates.stream()
            .sorted(Comparator.comparingDouble((FeedScorer.Facts f) -> scorer.score(f, START, 42).total()).reversed())
            .map(FeedScorer.Facts::id)
            .toList();
        assertEquals(atAsOf, actual);
    }

    @Test
    void differentSessions_giveADifferentFirstPage() throws SQLException {
        repository.candidates = posts(100);

        List<UUID> first = service(FeedConfig.DEFAULTS, 1).getFeed(VIEWER, null, 20, null, null).items().stream().map(PostCardDto::id).toList();
        List<UUID> second = service(FeedConfig.DEFAULTS, 2).getFeed(VIEWER, null, 20, null, null).items().stream().map(PostCardDto::id).toList();

        assertNotEquals(first, second);
    }

    @Test
    void guest_isRankedLikeAnyoneElse() throws SQLException {
        repository.candidates = posts(25);

        List<UUID> seen = walk(service(), null, 10);

        assertEquals(25, seen.size());
        assertNull(repository.viewers.getFirst());
    }

    @Test
    void location_isRoundedAndStaysInTheCursor() throws SQLException {
        repository.candidates = posts(5);

        FeedPage first = service().getFeed(VIEWER, null, 2, 54.3512345, 18.6512345);
        service().getFeed(VIEWER, first.page().nextCursor(), 2, null, null);

        assertEquals(List.of(54.35, 54.35), repository.latitudes);
        assertEquals(18.65, FeedCursor.decode(first.page().nextCursor()).orElseThrow().lon());
    }

    @Test
    void whenTheRankingQueryFails_returnsUpcomingOrder() throws SQLException {
        repository.candidates = posts(3);
        repository.rankingFailure = new SQLException("canceling statement due to statement timeout");

        FeedPage page = service().getFeed(VIEWER, null, 20, null, null);

        assertEquals(FeedMode.UPCOMING, page.mode());
        assertEquals(3, page.items().size());
        assertFalse(page.page().hasMore());
    }

    @Test
    void whenScoringThrows_returnsUpcomingOrder() throws SQLException {
        repository.candidates = posts(3);
        repository.rankingBug = new IllegalStateException("bug");

        assertEquals(FeedMode.UPCOMING, service().getFeed(VIEWER, null, 20, null, null).mode());
    }

    @Test
    void whenAFactCantBeScored_returnsUpcomingOrder() throws SQLException {
        repository.candidates = posts(3);
        // A null start time makes the real scorer throw. The upcoming fake never sorts it in, so hide it from there.
        List<FeedScorer.Facts> all = repository.candidates;
        FakeRepository broken = new FakeRepository() {
            @Override
            public List<FeedScorer.Facts> findCandidates(UUID viewer, double[] viewerVector, double[] postAverage, Instant asOf,
                                                         Double latitude, Double longitude, PostFeedFilters filters) {
                return List.of(new FeedScorer.Facts(UUID.randomUUID(), null, 0, null, null));
            }
        };
        broken.candidates = all;

        FeedPage page = new FeedService(broken, FeedConfig.DEFAULTS, movingClock, () -> 42).getFeed(VIEWER, null, 20, null, null);

        assertEquals(FeedMode.UPCOMING, page.mode());
    }

    @Test
    void whenRankingFailsMidSession_restartsUpcomingFromTheTop_withTheSameAsOf() throws SQLException {
        repository.candidates = posts(25);
        FeedPage first = service().getFeed(VIEWER, null, 10, null, null);
        Instant asOf = repository.asOfs.getFirst();

        repository.rankingFailure = new SQLException("timeout");
        FeedPage second = service().getFeed(VIEWER, first.page().nextCursor(), 10, null, null);

        assertEquals(FeedMode.UPCOMING, second.mode());
        UUID soonest = repository.candidates.stream()
            .min(Comparator.comparing(FeedScorer.Facts::startsAt).thenComparing(FeedScorer.Facts::id))
            .orElseThrow().id();
        assertEquals(soonest, second.items().getFirst().id());
        assertEquals(asOf, repository.asOfs.getLast());
    }

    @Test
    void upcomingCursor_continuesUpcoming_withoutTryingToRankAgain() throws SQLException {
        repository.candidates = posts(25);
        repository.rankingFailure = new SQLException("timeout");

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            FeedPage page = service().getFeed(VIEWER, cursor, 10, null, null);
            assertEquals(FeedMode.UPCOMING, page.mode());
            page.items().forEach(c -> seen.add(c.id()));
            cursor = page.page().nextCursor();
        } while (cursor != null);

        assertEquals(25, seen.size());
        assertEquals(25, new HashSet<>(seen).size());
        // One failed ranking on the first page, then the fallback only.
        assertEquals(1, repository.latitudes.size());
    }

    @Test
    void whenTheFallbackFailsToo_theErrorReachesTheCaller() {
        repository.rankingFailure = new SQLException("down");
        repository.upcomingFailure = new SQLException("down");

        assertThrows(SQLException.class, () -> service().getFeed(VIEWER, null, 20, null, null));
    }

    @Test
    void aPostDeletedAfterRanking_isLeftOut_andPagingContinues() throws SQLException {
        repository.candidates = posts(6);
        FakeRepository missingCard = new FakeRepository() {
            @Override
            public List<PostCardDto> findCards(UUID viewer, List<UUID> ids) {
                return super.findCards(viewer, ids).stream().skip(1).toList();
            }
        };
        missingCard.candidates = repository.candidates;

        FeedPage page = new FeedService(missingCard, FeedConfig.DEFAULTS, movingClock, () -> 42).getFeed(VIEWER, null, 3, null, null);

        assertEquals(2, page.items().size());
        assertTrue(page.page().hasMore());
    }

    @Test
    void anUnreadableCursor_throws_ratherThanFallingBack() {
        assertThrows(IllegalArgumentException.class, () -> service().getFeed(VIEWER, "garbage", 20, null, null));
    }

    @Test
    void limit_isClampedLikeOtherLists() throws SQLException {
        repository.candidates = posts(150);

        assertEquals(100, service().getFeed(VIEWER, null, 500, null, null).page().limit());
        assertEquals(20, service().getFeed(VIEWER, null, 0, null, null).page().limit());
    }

    @Test
    void jitterOfZero_makesTheOrderReproducibleBetweenSessions() throws SQLException {
        repository.candidates = posts(50);
        FeedConfig noJitter = FeedConfig.parse("jitter=0");
        Clock fixed = Clock.fixed(START, ZoneOffset.UTC);

        List<UUID> first = new FeedService(repository, noJitter, fixed, () -> 1).getFeed(VIEWER, null, 50, null, null).items().stream().map(PostCardDto::id).toList();
        List<UUID> second = new FeedService(repository, noJitter, fixed, () -> 2).getFeed(VIEWER, null, 50, null, null).items().stream().map(PostCardDto::id).toList();

        assertEquals(first, second);
    }
}
