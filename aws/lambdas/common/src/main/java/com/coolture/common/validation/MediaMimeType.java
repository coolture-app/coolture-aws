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

    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;

    private static final Set<String> ALLOWED = union(IMAGES, VIDEOS);

    public static void validate(String mimeType) {
        if (mimeType == null || !ALLOWED.contains(mimeType)) {
            throw new IllegalArgumentException(
                    "Unsupported media type '" + mimeType + "'. Allowed: images and videos.");
        }
    }

    public static void validateSize(String mimeType, long sizeBytes) {
        long max = VIDEOS.contains(mimeType) ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
        if (sizeBytes > max) {
            throw new IllegalArgumentException(
                    "File is too large: " + sizeBytes + " bytes. Maximum for " + mimeType + " is " + max + " bytes.");
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
