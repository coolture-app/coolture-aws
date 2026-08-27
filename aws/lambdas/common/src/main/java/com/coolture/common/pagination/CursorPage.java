package com.coolture.common.pagination;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public record CursorPage<T>(List<T> items, PaginationMeta page) {

    public static <T> CursorPage<T> of(
            List<T> rows,
            int limit,
            Function<T, UUID> idExtractor,
            Function<T, Instant> timestampExtractor) {

        boolean hasMore = rows.size() > limit;
        List<T> items = hasMore ? rows.subList(0, limit) : rows;

        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            T last = items.getLast();
            nextCursor = CursorCodec.encode(new CursorPayload(idExtractor.apply(last), timestampExtractor.apply(last)));
        }

        PaginationMeta meta = new PaginationMeta(limit, hasMore, nextCursor);
        return new CursorPage<>(items, meta);
    }
}
