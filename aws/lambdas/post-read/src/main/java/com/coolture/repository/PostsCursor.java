package com.coolture.repository;

import com.coolture.common.db.ParamBinder;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.pagination.CursorCodec;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Where a GET /posts page ended: the sort, the id, and the value that sort orders by. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostsCursor(PostSort sort, UUID id, Instant createdAt, Integer likes, Instant startsAt) {

    public static String after(PostSort sort, PostCardDto card) {
        return CursorCodec.encode(switch (sort) {
            case RECENT -> new PostsCursor(sort, card.id(), card.createdAt(), null, null);
            case POPULAR -> new PostsCursor(sort, card.id(), null, card.positiveReactionCount(), null);
            case UPCOMING -> new PostsCursor(sort, card.id(), null, null, card.startsAt());
        });
    }

    public static Optional<PostsCursor> decode(String raw, PostSort sort) {
        Optional<PostsCursor> cursor = CursorCodec.decode(raw, PostsCursor.class);
        cursor.ifPresent(c -> {
            if (c.sort != sort) {
                throw new IllegalArgumentException("Cursor belongs to another sortBy");
            }
            boolean hasValue = switch (sort) {
                case RECENT -> c.createdAt != null;
                case POPULAR -> c.likes != null;
                case UPCOMING -> c.startsAt != null;
            };
            if (c.id == null || !hasValue) {
                throw new IllegalArgumentException("Invalid cursor");
            }
        });
        return cursor;
    }

    /** Binds the two parameters of the sort's keyset condition. */
    void bindKeyset(ParamBinder binder) throws SQLException {
        switch (sort) {
            case RECENT -> binder.bindTimestamp(createdAt);
            case POPULAR -> binder.bindInt(likes);
            case UPCOMING -> binder.bindTimestamp(startsAt);
        }
        binder.bindUUID(id);
    }
}
