package com.coolture.common.pagination;

import com.coolture.common.json.JsonUtils;

import java.util.Base64;
import java.util.Optional;

public class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public static String encode(CursorPayload payload) {
        if (payload == null) return null;
        byte[] json = JsonUtils.toJsonBytes(payload);
        return ENCODER.encodeToString(json);
    }
    public static Optional<CursorPayload> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] json = DECODER.decode(cursor);
            return Optional.of(JsonUtils.fromJson(json, CursorPayload.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
