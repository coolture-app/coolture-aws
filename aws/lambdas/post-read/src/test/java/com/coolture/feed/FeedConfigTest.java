package com.coolture.feed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class FeedConfigTest {

    @Test
    void defaults_matchTheDesign() {
        assertEquals(new FeedConfig(0.65, 0.15, 0.10, 0.10, 0.10, 0.10), FeedConfig.DEFAULTS);
    }

    @Test
    void parse_whenUnsetBlankOrDefault_usesDefaults() {
        assertSame(FeedConfig.DEFAULTS, FeedConfig.parse(null));
        assertSame(FeedConfig.DEFAULTS, FeedConfig.parse("  "));
        assertSame(FeedConfig.DEFAULTS, FeedConfig.parse("default"));
    }

    @Test
    void parse_whenAllWeightsGiven_usesThem() {
        FeedConfig config = FeedConfig.parse("affinity=0.1,proximity=0.2,time=0.3,popularity=0.4,jitter=0,graph=0.5");

        assertEquals(new FeedConfig(0.1, 0.2, 0.3, 0.4, 0, 0.5), config);
    }

    @Test
    void parse_whenSomeWeightsGiven_keepsTheOtherDefaults() {
        FeedConfig config = FeedConfig.parse("jitter=0, affinity = 0");

        assertEquals(new FeedConfig(0, 0.15, 0.10, 0.10, 0, 0.10), config);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nonsense", "affinity", "affinity=", "affinity=abc", "unknown=1", "affinity=-1", "affinity=NaN", "affinity=Infinity", "jitter=0,time"})
    void parse_whenAnythingIsInvalid_usesAllDefaults(String raw) {
        assertSame(FeedConfig.DEFAULTS, FeedConfig.parse(raw));
    }
}
