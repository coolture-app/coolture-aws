package com.coolture.common.pagination;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaginationMeta(
    int limit,
    boolean hasMore,
    String nextCursor
) {}
