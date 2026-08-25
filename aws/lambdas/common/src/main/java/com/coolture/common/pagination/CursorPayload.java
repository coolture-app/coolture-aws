package com.coolture.common.pagination;

import java.time.Instant;
import java.util.UUID;

public record CursorPayload(UUID id, Instant createdAt) {}
