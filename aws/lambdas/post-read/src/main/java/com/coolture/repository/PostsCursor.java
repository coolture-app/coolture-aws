package com.coolture.repository;

import com.coolture.common.db.ParamBinder;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.pagination.CursorCodec;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a GET /posts page ended: the sort, the id, and the value that sort orders by. UPCOMING keeps an Upcoming.Position
 * instead: asOf, the dated stream's last start and id, the running stream's last end and id, and the count.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostsCursor(PostSort sort, UUID id, Instant createdAt, Integer likes, Instant startsAt,
                          Instant asOf, Instant runningEnd, UUID runningId, Integer count) {

    public static String after(PostSort sort, PostCardDto card) {
        return CursorCodec.encode(switch (sort) {
            case RECENT -> new PostsCursor(sort, card.id(), card.createdAt(), null, null, null, null, null, null);
            case POPULAR -> new PostsCursor(sort, card.id(), null, card.positiveReactionCount(), null, null, null, null, null);
            case UPCOMING -> throw new IllegalArgumentException("UPCOMING pages with upcoming()");
            case NEAREST, STANCE_NEWEST -> new PostsCursor(sort, card.id(), null, null, null, null, null, null, null);
        });
    }

    public static String upcoming(Upcoming.Position at) {
        return CursorCodec.encode(new PostsCursor(PostSort.UPCOMING, at.datedId(), null, null, at.datedStart(),
            at.asOf(), at.runningEnd(), at.runningId(), at.count()));
    }

    public Upcoming.Position position() {
        return new Upcoming.Position(asOf, startsAt, id, runningEnd, runningId, count);
    }

    public static Optional<PostsCursor> decode(String raw, PostSort sort) {
        Optional<PostsCursor> cursor = CursorCodec.decode(raw, PostsCursor.class);
        cursor.ifPresent(c -> {
            if (c.sort != sort) {
                throw new IllegalArgumentException("Cursor belongs to another sortBy");
            }
            boolean valid = switch (sort) {
                case RECENT -> c.id != null && c.createdAt != null;
                case POPULAR -> c.id != null && c.likes != null;
                case UPCOMING -> c.asOf != null && c.count != null && c.count >= 0
                    && (c.startsAt == null) == (c.id == null) && (c.runningEnd == null || c.runningId != null);
                case NEAREST, STANCE_NEWEST -> c.id != null;
            };
            if (!valid) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        });
        return cursor;
    }

    /** Binds the parameters of the sort's keyset condition. NEAREST needs the request's position, STANCE_NEWEST the viewer. */
    void bindKeyset(ParamBinder binder, Double latitude, Double longitude, UUID viewer) throws SQLException {
        switch (sort) {
            case RECENT -> binder.bindTimestamp(createdAt);
            case POPULAR -> binder.bindInt(likes);
            case UPCOMING -> throw new IllegalStateException("UPCOMING binds through Upcoming.Stream");
            case NEAREST -> binder.bindDouble(longitude).bindDouble(latitude)
                .bindDouble(longitude).bindDouble(latitude)
                .bindUUID(id);
            case STANCE_NEWEST -> binder.bindUUID(id).bindUUID(viewer);
        }
        binder.bindUUID(id);
    }
}
