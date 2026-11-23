package com.coolture.common.db;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

/**
 * When an event ends. A post with no end date (open_ended) never ends. In SQL the rule is the database's event_end
 * (V10), which the PageRank job and the seed call too; {@link #end} is the same rule for rows already read, and
 * PostReadDbTest checks the two agree.
 */
public final class EventTime {

    /**
     * An event with no end time has ended this long after it starts. A change needs a migration that replaces
     * event_end.
     */
    public static final int DEFAULT_EVENT_HOURS = 3;

    /**
     * A run longer than this is a standing event, like a post with no end date: the feed doesn't treat it as tonight's.
     */
    public static final int STANDING_AFTER_DAYS = 30;

    /**
     * The end of the post aliased {@code p}. Binds nothing. Only for conditions and ordering: JDBC can't read
     * 'infinity' as an Instant, so a query that needs the end selects ends_at and open_ended for {@link #end}.
     */
    public static final String END = "event_end(p.starts_at, p.ends_at, p.open_ended)";

    /** Binds nothing. */
    public static final String ENDED = END + " < now()";

    /** Binds nothing. */
    public static final String NOT_ENDED = END + " >= now()";

    /** The post runs at some moment between from and to, either optional. Bind with {@link #bindOverlap}. */
    public static final String OVERLAPS =
        "(?::timestamptz IS NULL OR " + END + " >= ?::timestamptz)"
            + " AND (?::timestamptz IS NULL OR p.starts_at <= ?::timestamptz)";

    private EventTime() {}

    public static void bindOverlap(ParamBinder b, Instant from, Instant to) throws SQLException {
        b.bindTimestampFilterPair(from);
        b.bindTimestampFilterPair(to);
    }

    /** Null when the event never ends. */
    public static Instant end(Instant startsAt, Instant endsAt, boolean openEnded) {
        if (openEnded) {
            return null;
        }
        return endsAt != null ? endsAt : startsAt.plus(Duration.ofHours(DEFAULT_EVENT_HOURS));
    }

    public static boolean isStanding(Instant startsAt, Instant endsAt, boolean openEnded) {
        return openEnded
            || endsAt != null && Duration.between(startsAt, endsAt).compareTo(Duration.ofDays(STANDING_AFTER_DAYS)) > 0;
    }
}
