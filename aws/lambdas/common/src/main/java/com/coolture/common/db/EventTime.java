package com.coolture.common.db;

import java.time.Duration;
import java.time.Instant;

/** When an event ends, in SQL and in Java. Every module takes the rule from here, so the two can't drift apart. */
public final class EventTime {

    /** An event with no end time has ended this long after it starts. */
    public static final int DEFAULT_EVENT_HOURS = 3;

    /** The end of the post aliased {@code p}. Binds nothing. */
    public static final String END =
        "COALESCE(p.ends_at, p.starts_at + interval '" + DEFAULT_EVENT_HOURS + " hours')";

    /** Binds nothing. */
    public static final String ENDED = END + " < now()";

    /** Binds nothing. */
    public static final String NOT_ENDED = END + " >= now()";

    private EventTime() {}

    public static Instant end(Instant startsAt, Instant endsAt) {
        return endsAt != null ? endsAt : startsAt.plus(Duration.ofHours(DEFAULT_EVENT_HOURS));
    }

    public static boolean hasEnded(Instant startsAt, Instant endsAt, Instant now) {
        return end(startsAt, endsAt).isBefore(now);
    }
}
