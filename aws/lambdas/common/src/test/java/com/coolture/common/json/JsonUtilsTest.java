package com.coolture.common.json;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonUtilsTest {

    @Test
    void toJson_writesInstantAsIso8601String() {
        String json = JsonUtils.toJson(Map.of("createdAt", Instant.parse("2026-10-25T14:22:45Z")));

        assertEquals("{\"createdAt\":\"2026-10-25T14:22:45Z\"}", json);
    }
}
