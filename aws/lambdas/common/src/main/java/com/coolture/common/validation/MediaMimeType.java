package com.coolture.common.validation;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Allowed MIME types for user-uploaded media.
 */
public final class MediaMimeType {

    public static final Set<String> IMAGES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/avif"
    );

    public static final Set<String> VIDEOS = Set.of(
            "video/mp4", "video/webm", "video/quicktime", "video/x-msvideo"
    );

    private static final Set<String> ALLOWED = union(IMAGES, VIDEOS);

    public static void validate(String mimeType) {
        if (mimeType == null || !ALLOWED.contains(mimeType)) {
            throw new IllegalArgumentException(
                    "Unsupported media type '" + mimeType + "'. Allowed: images and videos.");
        }
    }

    public static boolean isAllowed(String mimeType) {
        return mimeType != null && ALLOWED.contains(mimeType);
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> result = new HashSet<>(a);
        result.addAll(b);
        return Collections.unmodifiableSet(result);
    }

    private MediaMimeType() {}
}
