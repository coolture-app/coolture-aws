package com.coolture.feed;

import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Turns the facts about a post into a number. No I/O, so it can be tested alone and moved later.
 * Uses StrictMath, because a cursor keeps the exact score and the next page may run on another Lambda instance.
 */
public final class FeedScorer {

    private static final double MILLIS_PER_DAY = Duration.ofDays(1).toMillis();
    private static final double POPULARITY_SCALE = StrictMath.log(1.0 + FeedConfig.POPULARITY_CAP);

    /**
     * Null similarity means no vector for the viewer or the post, or vectors of different models. Null meters means no
     * location or an online post. endsAt is the event's end (its own, or the default length); null means not ended.
     * nearMark is 0 to 1: how far past NOT_FOR_ME_FROM the post is to its closest "Nie dla mnie" mark, that mark's fade
     * included (FeedRepository).
     */
    public record Facts(UUID id, Instant startsAt, Instant endsAt, int engagement, Double similarity, Double meters,
                        double nearMark) {

        public Facts(UUID id, Instant startsAt, Instant endsAt, int engagement, Double similarity, Double meters) {
            this(id, startsAt, endsAt, engagement, similarity, meters, 0);
        }

        /** An event that hasn't ended, as every event in the plain feed. */
        public Facts(UUID id, Instant startsAt, int engagement, Double similarity, Double meters) {
            this(id, startsAt, null, engagement, similarity, meters);
        }
    }

    /** The parts before weighting, kept so that explanations can be added later, and the weighted total. */
    public record Score(double affinity, double notForMe, double proximity, double time, double popularity, double jitter,
                        double total) {}

    private final FeedConfig config;

    public FeedScorer(FeedConfig config) {
        this.config = config;
    }

    public Score score(Facts facts, Instant asOf, long seed) {
        double notForMe = config.notForMe() * StrictMath.max(0, StrictMath.min(facts.nearMark(), 1));
        double affinity = affinity(facts.similarity(), notForMe);
        double proximity = facts.meters() == null
            ? FeedConfig.NEUTRAL
            : StrictMath.exp(-facts.meters() / FeedConfig.PROXIMITY_SCALE_METERS);
        double time = time(facts.startsAt(), facts.endsAt(), asOf);
        double popularity = StrictMath.min(StrictMath.log(1.0 + StrictMath.max(facts.engagement(), 0)) / POPULARITY_SCALE, 1.0);
        double jitter = jitter(facts.id(), seed);

        double total = config.affinity() * affinity
            + config.proximity() * proximity
            + config.time() * time
            + config.popularity() * popularity
            + config.jitter() * jitter;

        return new Score(affinity, notForMe, proximity, time, popularity, jitter, total);
    }

    /** The penalty comes off before the clamp, as it was measured, so affinity stays within 0 to 1. */
    private static double affinity(Double similarity, double notForMe) {
        double scaled;
        // pgvector returns NaN for a zero vector, which says as little as having no vector.
        if (similarity == null || similarity.isNaN()) {
            scaled = FeedConfig.NEUTRAL;
        } else {
            scaled = (similarity - FeedConfig.AFFINITY_LOW) / (FeedConfig.AFFINITY_HIGH - FeedConfig.AFFINITY_LOW);
        }
        return StrictMath.max(0, StrictMath.min(scaled - notForMe, 1));
    }

    /**
     * How far the event is from asOf, in days: until its start, 0 while it runs, and since its end once it ended, so an
     * ended event, shown when past events are on, fades like a far one and a long exhibition doesn't.
     */
    private static double time(Instant startsAt, Instant endsAt, Instant asOf) {
        Duration away;
        if (startsAt.isAfter(asOf)) {
            away = Duration.between(asOf, startsAt);
        } else if (endsAt != null && endsAt.isBefore(asOf)) {
            away = Duration.between(endsAt, asOf);
        } else {
            away = Duration.ZERO;
        }
        double days = away.toMillis() / MILLIS_PER_DAY;
        return StrictMath.exp(-days / FeedConfig.TIME_SCALE_DAYS);
    }

    private static double jitter(UUID id, long seed) {
        return new SplittableRandom(seed ^ id.getMostSignificantBits() ^ id.getLeastSignificantBits()).nextDouble();
    }
}
