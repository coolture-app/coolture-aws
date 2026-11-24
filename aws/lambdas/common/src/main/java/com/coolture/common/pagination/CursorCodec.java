package com.coolture.common.pagination;

import com.coolture.common.json.JsonUtils;

import java.util.Base64;
import java.util.Optional;

public class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String INVALID_CURSOR = "Invalid cursor";

    public static String encode(Object payload) {
        if (payload == null) return null;
        byte[] json = JsonUtils.toJsonBytes(payload);
        return ENCODER.encodeToString(json);
    }

    public static Optional<CursorPayload> decode(String cursor) {
        Optional<CursorPayload> payload = decode(cursor, CursorPayload.class);
        payload.ifPresent(p -> {
            if (p.id() == null || p.createdAt() == null) {
                throw new IllegalArgumentException(INVALID_CURSOR);
            }
        });
        return payload;
    }

    /**
     * Blank means no cursor, because the frontend sends an empty one for the first page of some lists.
     * Anything else that can't be read throws, so a bad cursor can't silently restart a list.
     */
    public static <T> Optional<T> decode(String cursor, Class<T> type) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] json = DECODER.decode(cursor);
            return Optional.of(JsonUtils.fromJson(json, type));
        } catch (Exception e) {
            throw new IllegalArgumentException(INVALID_CURSOR);
        }
    }
}
