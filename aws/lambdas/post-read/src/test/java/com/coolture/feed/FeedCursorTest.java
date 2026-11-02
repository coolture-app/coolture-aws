package com.coolture.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedCursorTest {

    private static final Instant AS_OF = Instant.parse("2026-11-02T18:00:00.123Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000105");

    private static String encoded(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ranked_roundTrips() {
        FeedCursor cursor = FeedCursor.ranked(AS_OF, -8127361234L, 0.73419, ID, 54.35, 18.65);

        FeedCursor decoded = FeedCursor.decode(cursor.encode()).orElseThrow();

        assertEquals(cursor, decoded);
        assertTrue(decoded.ranked());
    }

    @Test
    void ranked_withoutLocation_roundTrips() {
        FeedCursor cursor = FeedCursor.ranked(AS_OF, 1L, 0.5, ID, null, null);

        assertEquals(cursor, FeedCursor.decode(cursor.encode()).orElseThrow());
    }

    @Test
    void upcoming_roundTrips() {
        FeedCursor cursor = FeedCursor.upcoming(AS_OF, Instant.parse("2026-11-05T19:00:00.654321Z"), ID);

        FeedCursor decoded = FeedCursor.decode(cursor.encode()).orElseThrow();

        assertEquals(cursor, decoded);
        assertFalse(decoded.ranked());
    }

    @Test
    void aScore_comesBackExactly() {
        Random random = new Random(1);
        for (int i = 0; i < 20_000; i++) {
            double score = random.nextDouble();
            String raw = FeedCursor.ranked(AS_OF, 1L, score, ID, null, null).encode();
            assertEquals(score, FeedCursor.decode(raw).orElseThrow().s());
        }
    }

    @Test
    void decode_whenBlank_returnsEmpty() {
        assertTrue(FeedCursor.decode(null).isEmpty());
        assertTrue(FeedCursor.decode(" ").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "!!!", "eyJ2Ijox"})
    void decode_whenUnreadable_throws(String raw) {
        assertThrows(IllegalArgumentException.class, () -> FeedCursor.decode(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "null",
        // a cursor of a /posts or a comment list
        "{\"sort\":\"RECENT\",\"id\":\"00000000-0000-0000-0000-000000000105\",\"createdAt\":\"2026-11-02T18:00:00Z\"}",
        "{\"id\":\"00000000-0000-0000-0000-000000000105\",\"createdAt\":\"2026-11-02T18:00:00Z\"}",
        // unknown kind or version
        "{\"v\":1,\"k\":\"X\",\"t\":\"2026-11-02T18:00:00Z\",\"id\":\"00000000-0000-0000-0000-000000000105\"}",
        "{\"v\":2,\"k\":\"U\",\"t\":\"2026-11-02T18:00:00Z\",\"st\":\"2026-11-02T18:00:00Z\",\"id\":\"00000000-0000-0000-0000-000000000105\"}",
        // the fields of the other kind
        "{\"v\":1,\"k\":\"R\",\"t\":\"2026-11-02T18:00:00Z\",\"st\":\"2026-11-02T18:00:00Z\",\"id\":\"00000000-0000-0000-0000-000000000105\"}",
        "{\"v\":1,\"k\":\"U\",\"t\":\"2026-11-02T18:00:00Z\",\"seed\":1,\"s\":0.5,\"id\":\"00000000-0000-0000-0000-000000000105\"}",
        // half a location
        "{\"v\":1,\"k\":\"R\",\"t\":\"2026-11-02T18:00:00Z\",\"seed\":1,\"s\":0.5,\"id\":\"00000000-0000-0000-0000-000000000105\",\"lat\":54.35}",
        // missing asOf or id
        "{\"v\":1,\"k\":\"R\",\"seed\":1,\"s\":0.5,\"id\":\"00000000-0000-0000-0000-000000000105\"}",
        "{\"v\":1,\"k\":\"R\",\"t\":\"2026-11-02T18:00:00Z\",\"seed\":1,\"s\":0.5}",
    })
    void decode_whenTheWrongShapeOrKind_throws(String json) {
        assertThrows(IllegalArgumentException.class, () -> FeedCursor.decode(encoded(json)));
    }
}
