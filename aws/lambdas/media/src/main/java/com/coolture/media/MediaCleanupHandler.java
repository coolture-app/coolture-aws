package com.coolture.media;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.coolture.media.service.MediaService;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Runs once a day and deletes the photos of posts that were never published. */
public class MediaCleanupHandler implements RequestHandler<Map<String, Object>, Integer> {

    /** Longer than anyone stays in the post form, because a photo there is on no post yet. */
    static final Duration KEPT_FOR = Duration.ofHours(24);

    private final MediaService service;

    public MediaCleanupHandler() {
        this(new MediaService());
    }

    public MediaCleanupHandler(MediaService service) {
        this.service = service;
    }

    @Override
    public Integer handleRequest(Map<String, Object> event, Context context) {
        try {
            int deleted = service.deleteAbandoned(Instant.now().minus(KEPT_FOR));
            context.getLogger().log("Deleted " + deleted + " photos on no post\n");
            return deleted;
        } catch (SQLException e) {
            // Failing shows up in the function's error metric, and tomorrow's run picks up the same photos.
            throw new IllegalStateException("Could not look for photos on no post", e);
        }
    }
}
