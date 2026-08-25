package com.coolture.common.pagination;

import com.coolture.common.json.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Optional;

public class CursorCodec {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public static String encode(CursorPayload payload) {
        if (payload == null) return null;
        try {
            byte[] json = JsonUtils.getMapper().writeValueAsBytes(payload);
            return ENCODER.encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode cursor payload", e);
        }
    }

    public static Optional<CursorPayload> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] json = DECODER.decode(cursor);
            return Optional.of(JsonUtils.getMapper().readValue(json, CursorPayload.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
