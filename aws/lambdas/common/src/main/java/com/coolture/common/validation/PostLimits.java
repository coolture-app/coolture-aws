package com.coolture.common.validation;

/** Limits on what a post holds. Change them here; the frontend's MAX_POST_PHOTOS and the contract's maxItems follow. */
public final class PostLimits {

    /** Photos per post. Post-write rejects more, and cards return at most this many. */
    public static final int MAX_PHOTOS = 8;

    private PostLimits() {}
}
