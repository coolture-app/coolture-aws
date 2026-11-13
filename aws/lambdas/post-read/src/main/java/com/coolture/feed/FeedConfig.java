package com.coolture.feed;

import java.util.HashMap;
import java.util.Map;

/**
 * Every value the feed depends on. Only the weights can be overridden, with FEED_WEIGHTS, for example
 * "affinity=0.4,jitter=0". A weight of 0 switches that signal off. FEED_WEIGHTS is never a request parameter.
 */
public record FeedConfig(double affinity, double proximity, double time, double popularity, double jitter) {

    public static final FeedConfig DEFAULTS = new FeedConfig(0.55, 0.20, 0.10, 0.15, 0.10);

    /** Used for a signal that is missing. When the viewer is the one missing it, it's the same for every post, so it can't change the order. */
    public static final double NEUTRAL = 0.5;
    /**
     * sdadas/mmlw-roberta-base puts user and event cosines in a narrow band, so this is the part of it that affinity spreads over 0 to 1.
     * The midpoint is the median cosine, which keeps NEUTRAL close to a typical post. Measure these again if the model changes.
     */
    public static final double AFFINITY_LOW = 0.786;
    public static final double AFFINITY_HIGH = 0.946;
    /** The model the constants above were measured with. The viewer's vector is built from this model's vectors only. */
    public static final String EMBEDDING_MODEL = "sdadas/mmlw-roberta-base";

    /**
     * How the viewer's vector is built (UserVector). Unmeasured starting values, for the evaluation to tune. Only the
     * stances named here count, so a negative one can never pull the vector away from what every post shares.
     */
    public static final Map<String, Double> STANCE_WEIGHTS = Map.of("LIKE", 1.0, "INTERESTED", 1.5, "TAKES_PART", 2.0);
    public static final double INTEREST_CATEGORY_WEIGHT = 0.25;
    public static final double INTEREST_SHARE_MIN = 0.2;
    public static final double INTEREST_DECAY = 10;
    /** A stance this many days old counts half, in the vector and in engagement, so recent taste leads. */
    public static final double STANCE_HALF_LIFE_DAYS = 14;
    public static final int STANCE_MAX_AGE_DAYS = 112;
    public static final double PROXIMITY_SCALE_METERS = 20_000;
    public static final double TIME_SCALE_DAYS = 30;
    public static final int POPULARITY_CAP = 100;

    public static final int QUERY_TIMEOUT_SECONDS = 5;

    /** An event with no end time has ended this long after it starts. */
    public static final int DEFAULT_EVENT_HOURS = 3;
    /** How long after its end an event still shows in browse. */
    public static final int PAST_WINDOW_HOURS = 12;

    public static FeedConfig fromEnvironment() {
        return parse(System.getenv("FEED_WEIGHTS"));
    }

    static FeedConfig parse(String raw) {
        // sam local can only override declared variables, so the template declares "default".
        if (raw == null || raw.isBlank() || "default".equalsIgnoreCase(raw.trim())) {
            return DEFAULTS;
        }
        try {
            Map<String, Double> weights = new HashMap<>(Map.of(
                "affinity", DEFAULTS.affinity,
                "proximity", DEFAULTS.proximity,
                "time", DEFAULTS.time,
                "popularity", DEFAULTS.popularity,
                "jitter", DEFAULTS.jitter));
            for (String entry : raw.split(",")) {
                String[] pair = entry.split("=", 2);
                String name = pair[0].trim();
                if (pair.length != 2 || !weights.containsKey(name)) {
                    throw new IllegalArgumentException("unknown weight '" + entry + "'");
                }
                double value = Double.parseDouble(pair[1].trim());
                if (!(value >= 0) || Double.isInfinite(value)) {
                    throw new IllegalArgumentException("bad weight '" + entry + "'");
                }
                weights.put(name, value);
            }
            return new FeedConfig(weights.get("affinity"), weights.get("proximity"), weights.get("time"),
                weights.get("popularity"), weights.get("jitter"));
        } catch (RuntimeException e) {
            System.err.println("Invalid FEED_WEIGHTS, using the defaults: " + e.getMessage());
            return DEFAULTS;
        }
    }
}
