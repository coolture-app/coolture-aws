package com.coolture.common.storage;

public final class MediaUrls {

    private static S3StorageService storage;

    private MediaUrls() {}

    public static String presign(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return storage().presignGet(objectKey);
    }

    public static synchronized void useStorage(S3StorageService override) {
        storage = override;
    }

    private static synchronized S3StorageService storage() {
        if (storage == null) {
            storage = new S3StorageService();
        }
        return storage;
    }
}
