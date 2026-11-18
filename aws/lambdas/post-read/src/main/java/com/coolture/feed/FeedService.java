package com.coolture.feed;

import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.PaginationMeta;
import com.coolture.repository.Upcoming;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** GET /feed. The first page starts a session, and every later page continues it from the cursor. */
public class FeedService {

    private record Ranked(UUID id, double score) {}

    private record Session(Instant asOf, long seed, Double latitude, Double longitude, Ranked after) {}

    private static final Comparator<Ranked> BEST_FIRST = Comparator.<Ranked>comparingDouble(Ranked::score)
        .thenComparing(Ranked::id)
        .reversed();

    private final FeedRepository repository;
    private final FeedScorer scorer;
    private final Clock clock;
    private final LongSupplier seeds;

    public FeedService() {
        this(new FeedRepository(), FeedConfig.fromEnvironment(), Clock.systemUTC(), () -> ThreadLocalRandom.current().nextLong());
    }

    public FeedService(FeedRepository repository, FeedConfig config, Clock clock, LongSupplier seeds) {
        this.repository = repository;
        this.scorer = new FeedScorer(config);
        this.clock = clock;
        this.seeds = seeds;
    }

    /** The plain feed, with no filters. */
    public FeedPage getFeed(UUID viewer, String rawCursor, int limit, Double latitude, Double longitude) throws SQLException {
        return getFeed(viewer, rawCursor, limit, latitude, longitude, PostFeedFilters.none());
    }

    /**
     * The location is used for proximity only on the first page. Later pages take it from the cursor. The filters are
     * sent with every page, as with /posts, and narrow which posts are ranked.
     */
    public FeedPage getFeed(UUID viewer, String rawCursor, int limit, Double latitude, Double longitude,
                            PostFeedFilters filters) throws SQLException {
        int effectiveLimit = clampLimit(limit);
        Optional<FeedCursor> cursor = FeedCursor.decode(rawCursor);

        if (cursor.isPresent() && !cursor.get().ranked()) {
            return upcomingPage(viewer, filters, cursor.get().position(), effectiveLimit);
        }

        Session session = cursor.isPresent()
            ? resume(cursor.get())
            : newSession(viewer, round(latitude), round(longitude));

        List<Ranked> ranked;
        try {
            ranked = rank(viewer, session, filters, effectiveLimit + 1);
        } catch (SQLException | RuntimeException e) {
            System.err.println("Feed ranking failed, using the upcoming order: " + e);
            return upcomingPage(viewer, filters, Upcoming.Position.start(session.asOf()), effectiveLimit);
        }

        CursorPage<Ranked> page = CursorPage.of(ranked, effectiveLimit, last -> FeedCursor
            .ranked(session.asOf(), session.seed(), last.score(), last.id(), session.latitude(), session.longitude())
            .encode());

        return new FeedPage(FeedMode.RANKED, loadInOrder(viewer, page.items()), page.page());
    }

    private static Session resume(FeedCursor c) {
        return new Session(c.t(), c.seed(), c.lat(), c.lon(), new Ranked(c.id(), c.s()));
    }

    /** With no position in the request, proximity measures from the viewer's home, if they set one. */
    private Session newSession(UUID viewer, Double latitude, Double longitude) {
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (latitude == null) {
            try {
                Optional<GeoPointDto> home = repository.findHome(viewer);
                if (home.isPresent()) {
                    return new Session(asOf, seeds.getAsLong(), home.get().latitude(), home.get().longitude(), null);
                }
            } catch (SQLException e) {
                // Proximity is one signal of several, so a failed lookup ranks without it rather than failing the page.
                System.err.println("Home lookup failed, ranking without proximity: " + e);
            }
        }
        return new Session(asOf, seeds.getAsLong(), latitude, longitude, null);
    }

    private List<Ranked> rank(UUID viewer, Session session, PostFeedFilters filters, int count) throws SQLException {
        UserVector.Parts parts = repository.findVectorParts(viewer, session.asOf());
        List<FeedScorer.Facts> candidates = repository.findCandidates(viewer, UserVector.blend(parts), parts.postAverage(),
            session.asOf(), session.latitude(), session.longitude(), filters);

        List<Ranked> ranked = new ArrayList<>(candidates.size());
        for (FeedScorer.Facts facts : candidates) {
            // Always at asOf and not the current time, so scores don't drift between pages.
            ranked.add(new Ranked(facts.id(), scorer.score(facts, session.asOf(), session.seed()).total()));
        }

        return ranked.stream()
            .filter(row -> session.after() == null || BEST_FIRST.compare(row, session.after()) > 0)
            .sorted(BEST_FIRST)
            .limit(count)
            .toList();
    }

    private List<PostCardDto> loadInOrder(UUID viewer, List<Ranked> rows) throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Ranked::id).toList();
        Map<UUID, PostCardDto> cards = repository.findCards(viewer, ids).stream()
            .collect(Collectors.toMap(PostCardDto::id, Function.identity()));
        return ids.stream().filter(cards::containsKey).map(cards::get).toList();
    }

    private FeedPage upcomingPage(UUID viewer, PostFeedFilters filters, Upcoming.Position at, int limit) throws SQLException {
        Upcoming.Page page = repository.findUpcoming(viewer, filters, at, limit);
        String next = page.hasMore() ? FeedCursor.upcoming(page.next()).encode() : null;
        return new FeedPage(FeedMode.UPCOMING, page.items(), new PaginationMeta(limit, page.hasMore(), next));
    }

    /** About 1.1 km, so a precise position can't reach logs or cursors, whatever the client sent. */
    private static Double round(Double coordinate) {
        return coordinate == null ? null : Math.round(coordinate * 100) / 100.0;
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, 100);
    }
}
