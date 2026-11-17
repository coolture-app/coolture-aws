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
 * neither always first nor always last. A stream that runs out leaves the other to continue alone.
 */
public final class Upcoming {

    public static final int RUNNING_EVERY = 5;

    private Upcoming() {}

    /**
     * Where a list stopped: its asOf, each stream's last key (null ids when the stream gave no card yet) and how many
     * cards came so far, which keeps the rhythm across pages. A running key with an id and no end is a post with no end
     * date. Both cursors carry it as it is.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Position(Instant asOf, Instant datedStart, UUID datedId, Instant runningEnd, UUID runningId, int count) {

        public static Position start(Instant asOf) {
            return new Position(asOf, null, null, null, null, 0);
        }

        /** What a position decoded from a cursor must hold. */
        @JsonIgnore
        public boolean isValid() {
            return asOf != null && count >= 0 && (datedStart == null) == (datedId == null)
                && (runningEnd == null || runningId != null);
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

    /** Both streams from the given position, merged, with photos and friends' stances. */
    public static Page find(Connection conn, Query query, UUID viewer, Instant from, Position at, int limit)
            throws SQLException {
        List<PostCardDto> dated = Stream.DATED.read(conn, query, at, from, limit + 1);
        List<PostCardDto> running = Stream.RUNNING.read(conn, query, at, from, limit + 1);
        Page page = merge(dated, running, at, limit);
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

        DATED("p.starts_at >= ?::timestamptz", "p.starts_at") {
            @Override
            void bindWithin(ParamBinder b, Instant moment) throws SQLException {
                b.bindTimestamp(moment);
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

        RUNNING("p.starts_at < ?::timestamptz AND " + EventTime.END + " >= ?::timestamptz", EventTime.END) {
            @Override
            void bindWithin(ParamBinder b, Instant moment) throws SQLException {
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
        };

        private final String within;
        private final String key;

        Stream(String within, String key) {
            this.within = within;
            this.key = key;
        }

        abstract void bindWithin(ParamBinder b, Instant moment) throws SQLException;

        abstract Instant lastKey(Position at);

        abstract UUID lastId(Position at);

        List<PostCardDto> read(Connection conn, Query query, Position at, Instant from, int limit) throws SQLException {
            boolean continues = lastId(at) != null;
            String sql = query.sql()
                + "  AND " + within + "\n"
                // A null key is the end of a post with no end date.
                + (continues ? "  AND (" + key + ", p.id) > (COALESCE(?::timestamptz, 'infinity'::timestamptz), ?::uuid)\n" : "")
                + "ORDER BY " + key + " ASC, p.id ASC\n"
                + "LIMIT ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ParamBinder b = new ParamBinder(stmt, conn);
                query.binds().bind(b);
                bindWithin(b, at.moment(from));
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
