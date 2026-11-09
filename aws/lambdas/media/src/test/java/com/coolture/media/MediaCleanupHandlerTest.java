package com.coolture.media;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.coolture.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaCleanupHandlerTest {

    private final MediaService service = mock(MediaService.class);
    private final Context context = mock(Context.class);

    MediaCleanupHandlerTest() {
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
    }

    @Test
    void keepsPhotosForADaySoSomeoneStillInTheFormLosesNothing() throws Exception {
        when(service.deleteAbandoned(any())).thenReturn(3);

        Instant before = Instant.now();
        int deleted = new MediaCleanupHandler(service).handleRequest(Map.of(), context);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(service).deleteAbandoned(cutoff.capture());
        assertEquals(3, deleted);
        assertTrue(Duration.between(cutoff.getValue(), before).compareTo(Duration.ofHours(24).minusMinutes(1)) > 0);
    }

    @Test
    void failsVisiblyWhenTheDatabaseIsDown() throws Exception {
        when(service.deleteAbandoned(any())).thenThrow(new SQLException("down"));

        assertThrows(IllegalStateException.class,
            () -> new MediaCleanupHandler(service).handleRequest(Map.of(), context));
    }
}
