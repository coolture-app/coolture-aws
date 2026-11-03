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

    /** Null similarity means no vector for the viewer or the post, or vectors of different models. Null meters means no location or an online post. */
    public record Facts(UUID id, Instant startsAt, int engagement, Double similarity, Double meters) {}

    /** The parts before weighting, kept so that explanations can be added later, and the weighted total. */
    public record Score(double affinity, double proximity, double time, double popularity, double jitter, double total) {}

    private final FeedConfig config;

    public FeedScorer(FeedConfig config) {
        this.config = config;
    }

    public Score score(Facts facts, Instant asOf, long seed) {
        double affinity = affinity(facts.similarity());
        double proximity = facts.meters() == null
            ? FeedConfig.NEUTRAL
            : StrictMath.exp(-facts.meters() / FeedConfig.PROXIMITY_SCALE_METERS);
        double time = time(facts.startsAt(), asOf);
        double popularity = StrictMath.min(StrictMath.log(1.0 + StrictMath.max(facts.engagement(), 0)) / POPULARITY_SCALE, 1.0);
        double jitter = jitter(facts.id(), seed);

        double total = config.affinity() * affinity
            + config.proximity() * proximity
            + config.time() * time
            + config.popularity() * popularity
            + config.jitter() * jitter;

        return new Score(affinity, proximity, time, popularity, jitter, total);
    }

    private static double affinity(Double similarity) {
        // pgvector returns NaN for a zero vector, which says as little as having no vector.
        if (similarity == null || similarity.isNaN()) {
            return FeedConfig.NEUTRAL;
        }
        double scaled = (similarity - FeedConfig.AFFINITY_LOW) / (FeedConfig.AFFINITY_HIGH - FeedConfig.AFFINITY_LOW);
        return StrictMath.max(0, StrictMath.min(scaled, 1));
    }

    private static double time(Instant startsAt, Instant asOf) {
        double days = StrictMath.max(Duration.between(asOf, startsAt).toMillis() / MILLIS_PER_DAY, 0);
        return StrictMath.exp(-days / FeedConfig.TIME_SCALE_DAYS);
    }

    private static double jitter(UUID id, long seed) {
        return new SplittableRandom(seed ^ id.getMostSignificantBits() ^ id.getLeastSignificantBits()).nextDouble();
    }
}
