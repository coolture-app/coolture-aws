package com.coolture.repository;

import com.coolture.common.db.EventTime;
import com.coolture.common.db.Friends;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.ResultSetMappers;
import com.coolture.common.dto.PostCardDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Nadchodzące: /posts with sortBy=UPCOMING and /feed's fallback. Two streams from the moment m, the later of the list's
 * asOf and the filter's from: events starting at or after m, by start, and events already running at m, ending soonest
 * first and those with no end date last. Every RUNNING_EVERY-th card comes from the running stream, so running events are
 * neither always first nor always last. A stream that runs out leaves the other to continue alone. With Pokaż minione,
 * the events that ended before asOf follow both, latest end first.
 */
public final class Upcoming {

    public static final int RUNNING_EVERY = 5;

    private Upcoming() {}

    /**
     * Where a list stopped: its asOf, each stream's last key (null ids when the stream gave no card yet) and how many
     * cards came so far, which keeps the rhythm across pages. A running key with an id and no end is a post with no end
     * date. Both cursors carry it as it is. Once both streams ran out, ended says the list is in its ended events, and
     * endedEnd and endedId are the last one given.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Position(Instant asOf, Instant datedStart, UUID datedId, Instant runningEnd, UUID runningId, int count,
                           @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean ended, Instant endedEnd, UUID endedId) {

        public Position(Instant asOf, Instant datedStart, UUID datedId, Instant runningEnd, UUID runningId, int count) {
            this(asOf, datedStart, datedId, runningEnd, runningId, count, false, null, null);
        }

        public static Position start(Instant asOf) {
            return new Position(asOf, null, null, null, null, 0);
        }

        /** What a position decoded from a cursor must hold. */
        @JsonIgnore
        public boolean isValid() {
            return asOf != null && count >= 0 && (datedStart == null) == (datedId == null)
                && (runningEnd == null || runningId != null)
                && (endedEnd == null) == (endedId == null) && (ended || endedId == null);
        }

        Position endedAfter(PostCardDto last) {
            return last == null
                ? new Position(asOf, datedStart, datedId, runningEnd, runningId, count, true, endedEnd, endedId)
                : new Position(asOf, datedStart, datedId, runningEnd, runningId, count, true,
                    EventTime.end(last.startsAt(), last.endsAt(), last.openEnded()), last.id());
        }

        Instant moment(Instant from) {
            return from != null && from.isAfter(asOf) ? from : asOf;
        }
    }

    public record Page(List<PostCardDto> items, boolean hasMore, Position next) {}

    /** A list's own query up to the streams' conditions: PostSql.CARD_SELECT's columns, a WHERE, and their binds. */
    public record Query(String sql, Binds binds) {}

    @FunctionalInterface
    public interface Binds {
        void bind(ParamBinder b) throws SQLException;
    }

    /**
     * Both streams from the given position, merged, with photos and friends' stances. Once both ran out and ended isn't
     * null, the events of ended (the list's query with ended events in place of its rule against them) that ended before
     * asOf.
     */
    public static Page find(Connection conn, Query query, Query ended, UUID viewer, Instant from, Position at, int limit)
            throws SQLException {
        Page page = at.ended()
            ? new Page(List.of(), false, at)
            : merge(Stream.DATED.read(conn, query, at, from, limit + 1),
                Stream.RUNNING.read(conn, query, at, from, limit + 1), at, limit);
        if (!page.hasMore() && ended != null) {
            int room = limit - page.items().size();
            List<PostCardDto> past = Stream.ENDED.read(conn, ended, page.next(), from, room + 1);
            List<PostCardDto> taken = past.subList(0, Math.min(room, past.size()));
            List<PostCardDto> items = new ArrayList<>(page.items());
            items.addAll(taken);
            page = new Page(items, past.size() > room,
                page.next().endedAfter(taken.isEmpty() ? null : taken.get(taken.size() - 1)));
        }
        return new Page(Friends.withFriendStances(conn, viewer, MediaQueries.withPhotos(conn, page.items())),
            page.hasMore(), page.next());
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

    /** Each stream orders by its key and the id, and continues after the position's last key for it. */
    private enum Stream {

        DATED("p.starts_at >= ?::timestamptz", "p.starts_at", "ASC", ">") {
            @Override
            void bindWithin(ParamBinder b, Position at, Instant from) throws SQLException {
                b.bindTimestamp(at.moment(from));
            }

            @Override
            Instant lastKey(Position at) {
                return at.datedStart();
            }

            @Override
            UUID lastId(Position at) {
                return at.datedId();
            }
        },

        RUNNING("p.starts_at < ?::timestamptz AND " + EventTime.END + " >= ?::timestamptz", EventTime.END, "ASC", ">") {
            @Override
            void bindWithin(ParamBinder b, Position at, Instant from) throws SQLException {
                Instant moment = at.moment(from);
                b.bindTimestamp(moment).bindTimestamp(moment);
            }

            @Override
            Instant lastKey(Position at) {
                return at.runningEnd();
            }

            @Override
            UUID lastId(Position at) {
                return at.runningId();
            }
        },

        /** Bounded by asOf, not now, so an event that ends while the list is read can't come a second time here. */
        ENDED(EventTime.END + " < ?::timestamptz", EventTime.END, "DESC", "<") {
            @Override
            void bindWithin(ParamBinder b, Position at, Instant from) throws SQLException {
                b.bindTimestamp(at.asOf());
            }

            @Override
            Instant lastKey(Position at) {
                return at.endedEnd();
            }

            @Override
            UUID lastId(Position at) {
                return at.endedId();
            }
        };

        private final String within;
        private final String key;
        private final String order;
        private final String after;

        Stream(String within, String key, String order, String after) {
            this.within = within;
            this.key = key;
            this.order = order;
            this.after = after;
        }

        abstract void bindWithin(ParamBinder b, Position at, Instant from) throws SQLException;

        abstract Instant lastKey(Position at);

        abstract UUID lastId(Position at);

        List<PostCardDto> read(Connection conn, Query query, Position at, Instant from, int limit) throws SQLException {
            boolean continues = lastId(at) != null;
            String sql = query.sql()
                + "  AND " + within + "\n"
                // A running post with no end date has a null last key, read as infinity. An ended event always has an end.
                + (continues ? "  AND (" + key + ", p.id) " + after
                    + " (COALESCE(?::timestamptz, 'infinity'::timestamptz), ?::uuid)\n" : "")
                + "ORDER BY " + key + " " + order + ", p.id " + order + "\n"
                + "LIMIT ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ParamBinder b = new ParamBinder(stmt, conn);
                query.binds().bind(b);
                bindWithin(b, at, from);
                if (continues) {
                    b.bindTimestamp(lastKey(at)).bindUUID(lastId(at));
                }
                b.bindInt(limit);

                List<PostCardDto> cards = new ArrayList<>();
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        cards.add(ResultSetMappers.mapPostCard(rs));
                    }
                }
                return cards;
            }
        }
    }
}
