package com.coolture.feed;

import com.coolture.common.pagination.CursorCodec;
import com.coolture.repository.Upcoming;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that affects a feed session's order, so nothing is stored on the server. Two kinds, both with the session's
 * asOf (t): R (ranked) has the seed, the last score and id, and the location. U (upcoming) has the Upcoming.Position (u).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedCursor(int v, String k, Instant t, Long seed, Double s, UUID id, Double lat, Double lon,
                         Upcoming.Position u) {

    private static final int VERSION = 1;
    private static final String RANKED = "R";
    private static final String UPCOMING = "U";

    public static FeedCursor ranked(Instant asOf, long seed, double score, UUID id, Double lat, Double lon) {
        return new FeedCursor(VERSION, RANKED, asOf, seed, score, id, lat, lon, null);
    }

    public static FeedCursor upcoming(Upcoming.Position at) {
        return new FeedCursor(VERSION, UPCOMING, at.asOf(), null, null, null, null, null, at);
    }

    public Upcoming.Position position() {
        return u;
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
            case UPCOMING -> u != null && u.isValid() && t.equals(u.asOf());
            default -> false;
        };
        if (!common || !kind) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }
}
