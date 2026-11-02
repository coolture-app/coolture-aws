package com.coolture.common.pagination;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CursorCodecTest {

    @Test
    void encodeAndDecode_roundTripsSuccessfully() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-10-19T12:00:00Z");
        CursorPayload payload = new CursorPayload(id, createdAt);

        String encoded = CursorCodec.encode(payload);
        assertNotNull(encoded);
        assertFalse(encoded.isBlank());

        Optional<CursorPayload> decoded = CursorCodec.decode(encoded);
        assertTrue(decoded.isPresent());
        assertEquals(id, decoded.get().id());
        assertEquals(createdAt, decoded.get().createdAt());
    }

    @Test
    void encode_whenNullPayload_returnsNull() {
        assertNull(CursorCodec.encode(null));
    }

    @Test
    void decode_whenNullOrBlank_returnsEmpty() {
        assertTrue(CursorCodec.decode(null).isEmpty());
        assertTrue(CursorCodec.decode("").isEmpty());
        assertTrue(CursorCodec.decode("   ").isEmpty());
    }

    @Test
    void decode_whenInvalidBase64_throws() {
        assertThrows(IllegalArgumentException.class, () -> CursorCodec.decode("!not-valid-base64!"));
    }

    @Test
    void decode_whenCorruptedJson_throws() {
        assertThrows(IllegalArgumentException.class, () -> CursorCodec.decode(encoded("{not-json}")));
    }

    @Test
    void decode_whenJsonIsNull_throws() {
        assertThrows(IllegalArgumentException.class, () -> CursorCodec.decode(encoded("null")));
    }

    @Test
    void decode_whenPayloadMissesAField_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> CursorCodec.decode(encoded("{\"id\":\"" + UUID.randomUUID() + "\"}")));
        assertThrows(IllegalArgumentException.class,
            () -> CursorCodec.decode(encoded("{\"createdAt\":\"2026-10-19T12:00:00Z\"}")));
    }

    @Test
    void decodeWithType_roundTripsAndKeepsBlankAsEmpty() {
        record Payload(String sort, int likes) {}

        assertEquals(new Payload("POPULAR", 7), CursorCodec.decode(CursorCodec.encode(new Payload("POPULAR", 7)), Payload.class).orElseThrow());
        assertTrue(CursorCodec.decode(" ", Payload.class).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CursorCodec.decode("garbage", Payload.class));
    }

    @Test
    void cursorPageWithCursorFunction_setsCursorOnlyWhenThereIsMore() {
        CursorPage<String> more = CursorPage.of(List.of("a", "b", "c"), 2, s -> "after-" + s);
        assertEquals(List.of("a", "b"), more.items());
        assertTrue(more.page().hasMore());
        assertEquals("after-b", more.page().nextCursor());

        CursorPage<String> last = CursorPage.of(List.of("a", "b"), 2, s -> "after-" + s);
        assertFalse(last.page().hasMore());
        assertNull(last.page().nextCursor());
    }

    private static String encoded(String json) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }
}
