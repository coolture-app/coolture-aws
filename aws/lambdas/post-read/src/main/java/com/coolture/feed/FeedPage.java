package com.coolture.feed;

import com.coolture.common.dto.PostCardDto;
import com.coolture.common.pagination.PaginationMeta;

import java.util.List;

public record FeedPage(FeedMode mode, List<PostCardDto> items, PaginationMeta page) {}
