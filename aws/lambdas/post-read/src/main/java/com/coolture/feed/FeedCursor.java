package com.coolture.feed;

import com.coolture.common.pagination.CursorCodec;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that affects a feed session's order, so nothing is stored on the server. Two kinds:
 * R (ranked) has the seed, the last score and the location. U (upcoming) has the last start time.
 * Both have the session's asOf (t) and the last id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeedCursor(int v, String k, Instant t, Long seed, Double s, UUID id, Double lat, Double lon, Instant st) {

    private static final int VERSION = 1;
    private static final String RANKED = "R";
    private static final String UPCOMING = "U";

    public static FeedCursor ranked(Instant asOf, long seed, double score, UUID id, Double lat, Double lon) {
        return new FeedCursor(VERSION, RANKED, asOf, seed, score, id, lat, lon, null);
    }

    public static FeedCursor upcoming(Instant asOf, Instant startsAt, UUID id) {
        return new FeedCursor(VERSION, UPCOMING, asOf, null, null, id, null, null, startsAt);
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
        boolean common = v == VERSION && t != null && id != null;
        boolean kind = switch (String.valueOf(k)) {
            case RANKED -> seed != null && s != null && !s.isNaN() && (lat == null) == (lon == null);
            case UPCOMING -> st != null;
            default -> false;
        };
        if (!common || !kind) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }
}
