package com.coolture.common.pagination;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public record CursorPage<T>(List<T> items, PaginationMeta page) {

    public static <T> CursorPage<T> of(
            List<T> rows, int limit, Function<T, UUID> idOf, Function<T, Instant> timestampOf) {
        return of(rows, limit,
            last -> CursorCodec.encode(new CursorPayload(idOf.apply(last), timestampOf.apply(last))));
    }

    public static <T> CursorPage<T> of(List<T> rows, int limit, Function<T, String> cursorOf) {
        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? rows.subList(0, limit) : rows;

        String nextCursor = hasMore && !items.isEmpty() ? cursorOf.apply(items.getLast()) : null;

        return new CursorPage<>(items, new PaginationMeta(limit, hasMore, nextCursor));
    }
}
