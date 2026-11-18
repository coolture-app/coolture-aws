package com.coolture.feed;

import com.coolture.common.db.EventTime;
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

    private static List<FeedScorer.Facts> variedPosts(Double similarity, Double meters) {
        return IntStream.range(0, 50)
            .mapToObj(i -> TestFacts.startingAt(AS_OF.plus(Duration.ofHours(i * 7L))).id(new UUID(i, i * 31L))
                .engagement((i * 13) % 40).similarity(similarity).meters(meters).build())
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
        FeedScorer.Facts post = TestFacts.startingAt(AS_OF.plus(Duration.ofDays(30))).engagement(100).similarity(0.225)
            .meters(20_000.0).build();

        FeedScorer.Score score = DEFAULT.score(post, AS_OF, 7);

        assertEquals(0.75, score.affinity(), 1e-12);
        assertEquals(Math.exp(-1), score.proximity(), 1e-12);
        assertEquals(Math.exp(-1), score.time(), 1e-12);
        assertEquals(1.0, score.popularity(), 1e-12);
        FeedConfig w = FeedConfig.DEFAULTS;
        assertEquals(w.affinity() * score.affinity() + w.proximity() * score.proximity() + w.time() * score.time()
            + w.popularity() * score.popularity() + w.jitter() * score.jitter() + w.ppr() * score.ppr(), score.total(), 1e-12);
    }

    @Test
    void ppr_whenMissing_isNeutral() {
        FeedScorer.Facts post = TestFacts.startingAt(AS_OF.plus(Duration.ofDays(1))).build();

        assertEquals(FeedConfig.NEUTRAL, DEFAULT.score(post, AS_OF, 1).ppr());
    }

    @Test
    void ppr_whenPresent_isUsedAsIs() {
        FeedScorer.Facts post = TestFacts.startingAt(AS_OF.plus(Duration.ofDays(1))).ppr(0.9).build();

        assertEquals(0.9, DEFAULT.score(post, AS_OF, 1).ppr());
    }

    @Test
    void score_whenSignalsAreMissing_usesNeutralValues() {
        FeedScorer.Score score = DEFAULT.score(TestFacts.startingAt(AS_OF).build(), AS_OF, 7);

        assertEquals(0.5, score.affinity());
        assertEquals(0.5, score.proximity());
        assertEquals(1.0, score.time());
        assertEquals(0.0, score.popularity());
    }

    @Test
    void score_whenSimilarityIsNaN_treatsItAsMissing() {
        FeedScorer.Score score = DEFAULT.score(TestFacts.startingAt(AS_OF).similarity(Double.NaN).build(), AS_OF, 7);

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
        double good = scorer.score(TestFacts.startingAt(AS_OF).similarity(0.273).build(), AS_OF, 7).total();
        double poor = scorer.score(TestFacts.startingAt(AS_OF).similarity(-0.176).build(), AS_OF, 7).total();

        assertTrue(good - poor > FeedConfig.DEFAULTS.jitter());
    }

    private static double affinityOf(double similarity) {
        return DEFAULT.score(TestFacts.startingAt(AS_OF).similarity(similarity).build(), AS_OF, 7).affinity();
    }

    private static FeedScorer.Facts marked(double similarity, double nearMark) {
        return TestFacts.startingAt(AS_OF).similarity(similarity).nearMark(nearMark).build();
    }

    @Test
    void notForMe_takesItsWeightTimesNearMarkOffAffinity() {
        FeedScorer.Score score = DEFAULT.score(marked(0.225, 0.4), AS_OF, 7);

        assertEquals(0.5 * 0.4, score.notForMe(), 1e-12);
        assertEquals(0.75 - 0.2, score.affinity(), 1e-12);
    }

    @Test
    void notForMe_comesOffBeforeTheClamp_soAffinityStaysBetweenZeroAndOne() {
        assertEquals(0.0, DEFAULT.score(marked(FeedConfig.AFFINITY_LOW, 1), AS_OF, 7).affinity());
        assertEquals(0.5, DEFAULT.score(marked(FeedConfig.AFFINITY_HIGH, 1), AS_OF, 7).affinity(), 1e-12);
        // Far past the band, a post keeps the top affinity despite a mark, as the penalty was measured.
        assertEquals(1.0, DEFAULT.score(marked(1.0, 1), AS_OF, 7).affinity(), 1e-12);
    }

    @Test
    void notForMe_appliesWithoutAViewerVectorToo() {
        FeedScorer.Facts noVector = TestFacts.startingAt(AS_OF).nearMark(1).build();

        assertEquals(FeedConfig.NEUTRAL - 0.5, DEFAULT.score(noVector, AS_OF, 7).affinity(), 1e-12);
    }

    @Test
    void notForMe_ofZero_switchesThePenaltyOff() {
        FeedScorer off = new FeedScorer(FeedConfig.parse("notForMe=0,jitter=0"));

        assertEquals(off.score(marked(0.1, 0), AS_OF, 7).total(), off.score(marked(0.1, 1), AS_OF, 7).total());
    }

    @Test
    void aPostLikeAMarkedOne_ranksBelowAnEqualPostThatIsNot() {
        FeedScorer scorer = new FeedScorer(NO_JITTER);

        assertTrue(scorer.score(marked(0.1, 0.6), AS_OF, 1).total() < scorer.score(marked(0.1, 0), AS_OF, 1).total());
    }

    @Test
    void score_whenPopularityPassesTheCap_staysAtOne() {
        assertEquals(1.0, DEFAULT.score(TestFacts.startingAt(AS_OF).engagement(5000).build(), AS_OF, 7).popularity());
    }

    @Test
    void score_whenEventEnded_timeFadesWithTheDaysSinceItsEnd() {
        Instant start = AS_OF.minus(Duration.ofDays(40));
        FeedScorer.Facts ended = TestFacts.startingAt(start).endsAt(AS_OF.minus(Duration.ofDays(30))).build();

        assertEquals(Math.exp(-1), DEFAULT.score(ended, AS_OF, 7).time(), 1e-12);
    }

    /** As FeedRepository builds the facts of a dated run. */
    private static FeedScorer.Facts run(Instant start, Instant end, Double similarity) {
        return TestFacts.startingAt(start).endsAt(end).similarity(similarity).standing(EventTime.isStanding(start, end, false))
            .build();
    }

    @Test
    void score_aThreeWeekRun_runningAtAsOf_hasTheMaximumTime() {
        FeedScorer.Facts running = run(AS_OF.minus(Duration.ofDays(10)), AS_OF.plus(Duration.ofDays(11)), null);

        assertEquals(1.0, DEFAULT.score(running, AS_OF, 7).time());
    }

    @Test
    void score_a31DayRun_runningAtAsOf_isCappedAsStanding() {
        FeedScorer.Facts running = run(AS_OF.minus(Duration.ofDays(1)), AS_OF.plus(Duration.ofDays(30)), null);

        assertEquals(FeedConfig.STANDING_TIME_CAP, DEFAULT.score(running, AS_OF, 7).time());
    }

    @Test
    void score_aPostWithNoEndDate_isCapped_whileRunningAndBefore() {
        Instant start = AS_OF.minus(Duration.ofDays(400));
        FeedScorer.Facts standing = TestFacts.startingAt(start).standing(true).build();
        FeedScorer.Facts tomorrow = TestFacts.startingAt(AS_OF.plus(Duration.ofDays(1))).standing(true).build();

        assertEquals(FeedConfig.STANDING_TIME_CAP, DEFAULT.score(standing, AS_OF, 7).time());
        assertEquals(FeedConfig.STANDING_TIME_CAP, DEFAULT.score(tomorrow, AS_OF, 7).time());
    }

    @Test
    void score_aStandingEventFarAhead_keepsItsLowerTime() {
        Instant start = AS_OF.plus(Duration.ofDays(60));
        FeedScorer.Facts far = TestFacts.startingAt(start).standing(true).build();

        assertEquals(Math.exp(-2), DEFAULT.score(far, AS_OF, 7).time(), 1e-12);
    }

    @Test
    void score_theCapIsSoft_aStrongMatchStillLeads() {
        FeedScorer scorer = new FeedScorer(NO_JITTER);
        FeedScorer.Facts exhibition = TestFacts.startingAt(AS_OF.minus(Duration.ofDays(100))).similarity(FeedConfig.AFFINITY_HIGH)
            .standing(true).build();
        FeedScorer.Facts concertTonight = run(AS_OF.plus(Duration.ofHours(2)), null, 0.0);

        assertTrue(scorer.score(exhibition, AS_OF, 7).total() > scorer.score(concertTonight, AS_OF, 7).total());
    }

    @Test
    void score_whenEventHasStarted_timeIsAtItsMaximum() {
        FeedScorer.Facts running = TestFacts.startingAt(AS_OF.minus(Duration.ofDays(30))).build();

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
        FeedScorer.Facts plain = TestFacts.startingAt(AS_OF.plus(Duration.ofDays(1))).build();
        FeedScorer.Facts favoured = TestFacts.startingAt(AS_OF.plus(Duration.ofDays(1))).engagement(500).similarity(1.0).meters(0.0)
            .build();

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

        TestFacts near = TestFacts.startingAt(startsAt).engagement(5).similarity(0.2);
        double closer = scorer.score(near.meters(1_000.0).build(), AS_OF, 1).total();
        double farther = scorer.score(near.meters(50_000.0).build(), AS_OF, 1).total();

        assertTrue(closer > farther);
    }

    @Test
    void jitterOfZero_isDeterministicWhateverTheSeed() {
        FeedScorer scorer = new FeedScorer(NO_JITTER);
        List<FeedScorer.Facts> posts = variedPosts(0.1, 2_000.0);

        assertEquals(order(scorer, posts, 1), order(scorer, posts, 999_999));
    }
}
