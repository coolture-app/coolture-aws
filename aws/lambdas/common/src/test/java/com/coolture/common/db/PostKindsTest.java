package com.coolture.common.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class PostKindsTest {

    @Test
    void shown_isTheFirstKindTheFilterAsksFor() {
        assertEquals("talks", PostKinds.shown(List.of("exhibitions", "talks"), List.of("talks")));
        assertEquals("exhibitions", PostKinds.shown(List.of("exhibitions", "talks"), List.of("talks", "exhibitions")));
    }

    @Test
    void shown_isTheFirstKindWhenNothingMatchesOrNothingIsAsked() {
        assertEquals("exhibitions", PostKinds.shown(List.of("exhibitions", "talks"), null));
        assertEquals("exhibitions", PostKinds.shown(List.of("exhibitions", "talks"), List.of()));
        assertEquals("exhibitions", PostKinds.shown(List.of("exhibitions", "talks"), List.of("stage")));
    }

    @Test
    void shown_isNullUntilDerived() {
        assertNull(PostKinds.shown(null, List.of("talks")));
        assertNull(PostKinds.shown(List.of(), null));
    }
}
