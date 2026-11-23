package com.coolture.feed;

import com.coolture.common.dto.PostFeedFilters;

/** A /feed request's filters, as PostReadHandler parses them. */
public final class FeedRequests {

    private FeedRequests() {}

    public static final PostFeedFilters NO_FILTERS = page(null, 20, null, null);

    public static PostFeedFilters page(String cursor, int limit, Double latitude, Double longitude) {
        return new PostFeedFilters(null, null, null, null, null, null, null, latitude, longitude, null,
            null, null, cursor, limit, null, false, null, false, null);
    }

    /** The filters with this page's cursor and limit. */
    public static PostFeedFilters page(PostFeedFilters f, String cursor, int limit) {
        return new PostFeedFilters(f.q(), f.authorId(), f.status(), f.followed(), f.type(), f.from(), f.to(),
            f.latitude(), f.longitude(), f.radiusKm(), f.participationTypes(), f.reactionType(), cursor, limit,
            f.sortBy(), f.includePast(), f.kinds(), f.kids(), f.participantId());
    }
}
