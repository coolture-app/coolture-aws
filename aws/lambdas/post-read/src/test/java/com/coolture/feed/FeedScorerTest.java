package com.coolture.feed;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedScorerTest {

    private static final Instant AS_OF = Instant.parse("2026-11-02T18:00:00Z");
    private static final FeedScorer DEFAULT = new FeedScorer(FeedConfig.DEFAULTS);
    private static final FeedConfig NO_JITTER = FeedConfig.parse("jitter=0");

    private static FeedScorer.Facts facts(UUID id, Instant startsAt, int engagement, Double similarity, Double meters) {
        return new FeedScorer.Facts(id, startsAt, engagement, similarity, meters);
    }

    private static List<FeedScorer.Facts> variedPosts(Double similarity, Double meters) {
        return IntStream.range(0, 50)
            .mapToObj(i -> facts(new UUID(i, i * 31L), AS_OF.plus(Duration.ofHours(i * 7L)), (i * 13) % 40,
                similarity == null ? null : similarity, meters == null ? null : meters))
            .toList();
    }

    private static List<UUID> order(FeedScorer scorer, List<FeedScorer.Facts> posts, long seed) {
        return posts.stream()
            .sorted(Comparator.comparingDouble((FeedScorer.Facts f) -> scorer.score(f, AS_OF, seed).total()).reversed())
            .map(FeedScorer.Facts::id)
            .toList();
    }

    @Test
    void score_computesEachPartFromTheFormula() {
        FeedScorer.Facts post = facts(UUID.randomUUID(), AS_OF.plus(Duration.ofDays(30)), 100, 0.225, 20_000.0);

        FeedScorer.Score score = DEFAULT.score(post, AS_OF, 7);

        assertEquals(0.75, score.affinity(), 1e-12);
        assertEquals(Math.exp(-1), score.proximity(), 1e-12);
        assertEquals(Math.exp(-1), score.time(), 1e-12);
        assertEquals(1.0, score.popularity(), 1e-12);
        FeedConfig w = FeedConfig.DEFAULTS;
        assertEquals(w.affinity() * score.affinity() + w.proximity() * score.proximity() + w.time() * score.time()
            + w.popularity() * score.popularity() + w.jitter() * score.jitter(), score.total(), 1e-12);
    }

    @Test
    void score_whenSignalsAreMissing_usesNeutralValues() {
        FeedScorer.Score score = DEFAULT.score(facts(UUID.randomUUID(), AS_OF, 0, null, null), AS_OF, 7);

        assertEquals(0.5, score.affinity());
        assertEquals(0.5, score.proximity());
        assertEquals(1.0, score.time());
        assertEquals(0.0, score.popularity());
    }

    @Test
    void score_whenSimilarityIsNaN_treatsItAsMissing() {
        FeedScorer.Score score = DEFAULT.score(facts(UUID.randomUUID(), AS_OF, 0, Double.NaN, null), AS_OF, 7);

        assertEquals(0.5, score.affinity());
        assertFalse(Double.isNaN(score.total()));
    }

    @Test
    void affinity_spreadsTheBandOverZeroToOne_withATypicalPostNeutral() {
        assertEquals(0.0, affinityOf(FeedConfig.AFFINITY_LOW), 1e-12);
        assertEquals(1.0, affinityOf(FeedConfig.AFFINITY_HIGH), 1e-12);
        assertEquals(FeedConfig.NEUTRAL, affinityOf(0), 1e-12);
    }

    @Test
    void affinity_outsideTheBand_staysBetweenZeroAndOne() {
        assertEquals(0.0, affinityOf(-1.0));
        assertEquals(1.0, affinityOf(1.0));
    }

    @Test
    void aGoodMatch_cantBeOvertakenByJitterAlone() {
        // The 95th and 5th percentile of a new user's cosines, with the averages taken out.
        FeedScorer scorer = new FeedScorer(FeedConfig.parse("proximity=0,time=0,popularity=0,jitter=0"));
        double good = scorer.score(facts(UUID.randomUUID(), AS_OF, 0, 0.273, null), AS_OF, 7).total();
        double poor = scorer.score(facts(UUID.randomUUID(), AS_OF, 0, -0.176, null), AS_OF, 7).total();

        assertTrue(good - poor > FeedConfig.DEFAULTS.jitter());
    }

    private static double affinityOf(double similarity) {
        return DEFAULT.score(facts(UUID.randomUUID(), AS_OF, 0, similarity, null), AS_OF, 7).affinity();
    }

    @Test
    void score_whenPopularityPassesTheCap_staysAtOne() {
        assertEquals(1.0, DEFAULT.score(facts(UUID.randomUUID(), AS_OF, 5000, null, null), AS_OF, 7).popularity());
    }

    @Test
    void score_whenEventEnded_timeFadesWithTheDaysSinceItsEnd() {
        Instant start = AS_OF.minus(Duration.ofDays(40));
        FeedScorer.Facts ended = new FeedScorer.Facts(UUID.randomUUID(), start, AS_OF.minus(Duration.ofDays(30)), 0, null, null);

        assertEquals(Math.exp(-1), DEFAULT.score(ended, AS_OF, 7).time(), 1e-12);
    }

    @Test
    void score_whenEventRunsAtAsOf_timeIsAtItsMaximumEvenWithAnEnd() {
        FeedScorer.Facts running = new FeedScorer.Facts(UUID.randomUUID(), AS_OF.minus(Duration.ofDays(30)),
            AS_OF.plus(Duration.ofDays(30)), 0, null, null);

        assertEquals(1.0, DEFAULT.score(running, AS_OF, 7).time());
    }

    @Test
    void score_whenEventHasStarted_timeIsAtItsMaximum() {
        FeedScorer.Facts running = facts(UUID.randomUUID(), AS_OF.minus(Duration.ofDays(30)), 0, null, null);

        assertEquals(1.0, DEFAULT.score(running, AS_OF, 7).time());
    }

    @Test
    void aSignalMissingForEveryone_doesNotChangeTheOrder() {
        FeedScorer scorer = new FeedScorer(NO_JITTER);

        // The neutral value and any other single value for everyone only add a constant to each score.
        assertEquals(order(scorer, variedPosts(null, null), 1), order(scorer, variedPosts(0.3, null), 1));
        assertEquals(order(scorer, variedPosts(null, null), 1), order(scorer, variedPosts(null, 5_000.0), 1));
    }

    @Test
    void aWeightOfZero_removesThatSignal() {
        FeedScorer scorer = new FeedScorer(FeedConfig.parse("affinity=0,proximity=0,popularity=0,jitter=0"));
        FeedScorer.Facts plain = facts(UUID.randomUUID(), AS_OF.plus(Duration.ofDays(1)), 0, null, null);
        FeedScorer.Facts favoured = facts(UUID.randomUUID(), AS_OF.plus(Duration.ofDays(1)), 500, 1.0, 0.0);

        assertEquals(scorer.score(plain, AS_OF, 1).total(), scorer.score(favoured, AS_OF, 2).total());
    }

    @Test
    void theSameSeed_givesTheSameResult_andAnotherSeedChangesIt() {
        List<FeedScorer.Facts> posts = variedPosts(null, null);

        assertEquals(order(DEFAULT, posts, 42), order(DEFAULT, posts, 42));
        assertNotEquals(order(DEFAULT, posts, 42), order(DEFAULT, posts, 43));
        assertEquals(DEFAULT.score(posts.getFirst(), AS_OF, 42), DEFAULT.score(posts.getFirst(), AS_OF, 42));
    }

    @Test
    void jitterStaysInRange_andIsStableAcrossInstances() {
        for (FeedScorer.Facts post : variedPosts(null, null)) {
            double jitter = DEFAULT.score(post, AS_OF, 99).jitter();
            assertTrue(jitter >= 0 && jitter < 1);
            assertEquals(jitter, new FeedScorer(FeedConfig.DEFAULTS).score(post, AS_OF, 99).jitter());
        }
    }

    @Test
    void aCloserCopyOfAPost_ranksAboveAFartherOne() {
        FeedScorer scorer = new FeedScorer(NO_JITTER);
        Instant startsAt = AS_OF.plus(Duration.ofDays(3));

        double closer = scorer.score(facts(UUID.randomUUID(), startsAt, 5, 0.2, 1_000.0), AS_OF, 1).total();
        double farther = scorer.score(facts(UUID.randomUUID(), startsAt, 5, 0.2, 50_000.0), AS_OF, 1).total();

        assertTrue(closer > farther);
    }

    @Test
    void jitterOfZero_isDeterministicWhateverTheSeed() {
        FeedScorer scorer = new FeedScorer(NO_JITTER);
        List<FeedScorer.Facts> posts = variedPosts(0.1, 2_000.0);

        assertEquals(order(scorer, posts, 1), order(scorer, posts, 999_999));
    }
}
