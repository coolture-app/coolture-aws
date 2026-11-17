package com.coolture.feed;

import com.coolture.common.pagination.CursorCodec;
import com.coolture.repository.Upcoming;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that affects a feed session's order, so nothing is stored on the server. Two kinds, both with the session's
 * asOf (t): R (ranked) has the seed, the last score and id, and the location. U (upcoming) has an Upcoming.Position: the
 * dated stream's last start and id (st, id), the running stream's last end and id (re, rid), and the count (n).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedCursor(int v, String k, Instant t, Long seed, Double s, UUID id, Double lat, Double lon, Instant st,
                         Instant re, UUID rid, Integer n) {

    private static final int VERSION = 1;
    private static final String RANKED = "R";
    private static final String UPCOMING = "U";

    public static FeedCursor ranked(Instant asOf, long seed, double score, UUID id, Double lat, Double lon) {
        return new FeedCursor(VERSION, RANKED, asOf, seed, score, id, lat, lon, null, null, null, null);
    }

    public static FeedCursor upcoming(Upcoming.Position at) {
        return new FeedCursor(VERSION, UPCOMING, at.asOf(), null, null, at.datedId(), null, null, at.datedStart(),
            at.runningEnd(), at.runningId(), at.count());
    }

    public Upcoming.Position position() {
        return new Upcoming.Position(t, st, id, re, rid, n);
    }

    public static Optional<FeedCursor> decode(String raw) {
        Optional<FeedCursor> cursor = CursorCodec.decode(raw, FeedCursor.class);
        cursor.ifPresent(FeedCursor::requireValid);
        return cursor;
    }

    public String encode() {
        return CursorCodec.encode(this);
    }

    public boolean ranked() {
        return RANKED.equals(k);
    }

    private void requireValid() {
        boolean common = v == VERSION && t != null;
        boolean kind = switch (String.valueOf(k)) {
            case RANKED -> id != null && seed != null && s != null && !s.isNaN() && (lat == null) == (lon == null);
            case UPCOMING -> n != null && n >= 0 && (st == null) == (id == null) && (re == null || rid != null);
            default -> false;
        };
        if (!common || !kind) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }
}
