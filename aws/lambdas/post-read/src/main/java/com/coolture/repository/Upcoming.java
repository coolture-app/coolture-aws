package com.coolture.repository;

import com.coolture.common.db.EventTime;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.dto.PostCardDto;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Nadchodzące: /posts with sortBy=UPCOMING and /feed's fallback. Two streams from the moment m, the later of the list's
 * asOf and the filter's from: events starting at or after m, by start, and events already running at m, ending soonest
 * first and those with no end date last. Every RUNNING_EVERY-th card comes from the running stream, so running events are
 * neither always first nor always last. A stream that runs out leaves the other to continue alone.
 */
public final class Upcoming {

    public static final int RUNNING_EVERY = 5;

    private Upcoming() {}

    /**
     * Where a list stopped: its asOf, each stream's last key (null ids when the stream gave no card yet) and how many
     * cards came so far, which keeps the rhythm across pages. A running key with an id and no end is a post with no end
     * date.
     */
    public record Position(Instant asOf, Instant datedStart, UUID datedId, Instant runningEnd, UUID runningId, int count) {

        public static Position start(Instant asOf) {
            return new Position(asOf, null, null, null, null, 0);
        }

        Instant moment(Instant from) {
            return from != null && from.isAfter(asOf) ? from : asOf;
        }
    }

    public record Page(List<PostCardDto> items, boolean hasMore, Position next) {

        public Page withItems(List<PostCardDto> cards) {
            return new Page(cards, hasMore, next);
        }
    }

    public enum Stream {

        DATED("p.starts_at >= ?::timestamptz",
            "(p.starts_at, p.id) > (?::timestamptz, ?::uuid)",
            "p.starts_at ASC, p.id ASC"),
        RUNNING("p.starts_at < ?::timestamptz AND " + EventTime.END + " >= ?::timestamptz",
            "(" + EventTime.END + ", p.id) > (COALESCE(?::timestamptz, 'infinity'::timestamptz), ?::uuid)",
            EventTime.END + " ASC, p.id ASC");

        private final String within;
        private final String after;
        private final String order;

        Stream(String within, String after, String order) {
            this.within = within;
            this.after = after;
            this.order = order;
        }

        /** The condition, ORDER BY included, to follow a WHERE's other conditions. Bind with {@link #bind}. */
        public String sql(Position at) {
            return "  AND " + within + "\n"
                + (continues(at) ? "  AND " + after + "\n" : "")
                + "ORDER BY " + order + "\n";
        }

        public void bind(ParamBinder b, Position at, Instant from) throws SQLException {
            Instant moment = at.moment(from);
            b.bindTimestamp(moment);
            if (this == RUNNING) {
                b.bindTimestamp(moment);
            }
            if (continues(at)) {
                if (this == DATED) {
                    b.bindTimestamp(at.datedStart()).bindUUID(at.datedId());
                } else {
                    b.bindTimestamp(at.runningEnd()).bindUUID(at.runningId());
                }
            }
        }

        private boolean continues(Position at) {
            return (this == DATED ? at.datedId() : at.runningId()) != null;
        }
    }

    /** Each stream's next cards, at least limit + 1 of each when there are that many. */
    public static Page merge(List<PostCardDto> dated, List<PostCardDto> running, Position at, int limit) {
        List<PostCardDto> items = new ArrayList<>();
        Position next = at;
        int d = 0;
        int r = 0;
        while (items.size() <= limit) {
            boolean runningTurn = (at.count() + items.size() + 1) % RUNNING_EVERY == 0;
            boolean takeRunning = r < running.size() && (runningTurn || d >= dated.size());
            if (!takeRunning && d >= dated.size()) {
                break;
            }
            PostCardDto card = takeRunning ? running.get(r++) : dated.get(d++);
            items.add(card);
            if (items.size() <= limit) {
                next = takeRunning
                    ? new Position(at.asOf(), next.datedStart(), next.datedId(),
                        EventTime.end(card.startsAt(), card.endsAt(), card.openEnded()), card.id(), at.count() + items.size())
                    : new Position(at.asOf(), card.startsAt(), card.id(),
                        next.runningEnd(), next.runningId(), at.count() + items.size());
            }
        }
        boolean hasMore = items.size() > limit;
        return new Page(hasMore ? items.subList(0, limit) : items, hasMore, next);
    }
}
