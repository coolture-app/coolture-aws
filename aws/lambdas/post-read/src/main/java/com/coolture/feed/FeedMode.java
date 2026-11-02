package com.coolture.feed;

/** How a feed page was ordered. RANKED is the scorer, also for guests. UPCOMING is the fallback when ranking failed. */
public enum FeedMode {
    RANKED,
    UPCOMING
}
