package com.coolture.common.pagination;

import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    void decode_whenInvalidBase64_returnsEmpty() {
        assertTrue(CursorCodec.decode("!not-valid-base64!").isEmpty());
    }

    @Test
    void decode_whenCorruptedJson_returnsEmpty() {
        String base64Corrupted = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{not-json}".getBytes());
        assertTrue(CursorCodec.decode(base64Corrupted).isEmpty());
    }
}
