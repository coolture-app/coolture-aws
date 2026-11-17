package com.coolture.common.db;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventTimeTest {

    private static final Instant START = Instant.parse("2026-11-14T18:00:00Z");

    @Test
    void end_isItsOwn_orTheDefaultLength_orNeverWithNoEndDate() {
        Instant own = START.plus(Duration.ofDays(2));

        assertEquals(own, EventTime.end(START, own, false));
        assertEquals(START.plus(Duration.ofHours(EventTime.DEFAULT_EVENT_HOURS)), EventTime.end(START, null, false));
        assertNull(EventTime.end(START, null, true));
    }

    @Test
    void running_fromItsStartToItsEnd() {
        Instant end = START.plus(Duration.ofHours(3));

        assertFalse(EventTime.isRunning(START, null, false, START.minusMillis(1)));
        assertTrue(EventTime.isRunning(START, null, false, START));
        assertTrue(EventTime.isRunning(START, null, false, end));
        assertFalse(EventTime.isRunning(START, null, false, end.plusMillis(1)));
        assertTrue(EventTime.hasEnded(START, null, false, end.plusMillis(1)));
    }

    @Test
    void aPostWithNoEndDate_neverEnds() {
        Instant muchLater = START.plus(Duration.ofDays(3650));

        assertTrue(EventTime.isRunning(START, null, true, muchLater));
        assertFalse(EventTime.hasEnded(START, null, true, muchLater));
    }

    @Test
    void standing_isNoEndDate_orARunOverThirtyDays() {
        assertTrue(EventTime.isStanding(START, null, true));
        assertTrue(EventTime.isStanding(START, START.plus(Duration.ofDays(31)), false));
        assertFalse(EventTime.isStanding(START, START.plus(Duration.ofDays(30)), false));
        assertFalse(EventTime.isStanding(START, START.plus(Duration.ofDays(21)), false));
        assertFalse(EventTime.isStanding(START, null, false));
    }
}
