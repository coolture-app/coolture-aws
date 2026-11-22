package com.coolture.feed;

import java.time.Instant;
import java.util.UUID;

/**
 * FeedScorer.Facts for tests, so each test names only what it's about. Each field starts empty or neutral.
 */
public final class TestFacts {

    private UUID id = UUID.randomUUID();
    private Instant startsAt;
    private Instant endsAt;
    private int engagement;
    private Double similarity;
    private Double meters;
    private double nearMark;
    private boolean standing;
    private Double ppr;

    private TestFacts() {}

    public static TestFacts startingAt(Instant startsAt) {
        TestFacts facts = new TestFacts();
        facts.startsAt = startsAt;
        return facts;
    }

    public static TestFacts copyOf(FeedScorer.Facts f) {
        return startingAt(f.startsAt()).id(f.id()).endsAt(f.endsAt()).engagement(f.engagement())
            .similarity(f.similarity()).meters(f.meters()).nearMark(f.nearMark()).standing(f.standing()).ppr(f.ppr());
    }

    public TestFacts id(UUID id) {
        this.id = id;
        return this;
    }

    public TestFacts endsAt(Instant endsAt) {
        this.endsAt = endsAt;
        return this;
    }

    public TestFacts engagement(int engagement) {
        this.engagement = engagement;
        return this;
    }

    public TestFacts similarity(Double similarity) {
        this.similarity = similarity;
        return this;
    }

    public TestFacts meters(Double meters) {
        this.meters = meters;
        return this;
    }

    public TestFacts nearMark(double nearMark) {
        this.nearMark = nearMark;
        return this;
    }

    public TestFacts standing(boolean standing) {
        this.standing = standing;
        return this;
    }

    public TestFacts ppr(Double ppr) {
        this.ppr = ppr;
        return this;
    }

    public FeedScorer.Facts build() {
        return new FeedScorer.Facts(id, startsAt, endsAt, engagement, similarity, meters, nearMark, standing, ppr);
    }
}
