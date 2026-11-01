package com.coolture.common.messaging;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

/**
 * Requests embedding work by writing an empty object to the embedding requests bucket.
 * S3 forwards {@code posts/} keys to the post embedding queue and {@code users/} keys to the user embedding queue.
 */
public class EmbeddingRequestPublisher {

    private static final String BUCKET_ENV = "EMBEDDING_REQUESTS_BUCKET";
    static final String POST_PREFIX = "posts/";
    static final String USER_PREFIX = "users/";

    private final String bucket;
    private final S3Client s3;

    public EmbeddingRequestPublisher() {
        this(System.getenv(BUCKET_ENV));
    }

    private EmbeddingRequestPublisher(String bucket) {
        this(bucket, isBlank(bucket) ? null : S3Client.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build());
    }

    public EmbeddingRequestPublisher(String bucket, S3Client s3) {
        this.bucket = bucket;
        this.s3 = s3;
    }

    public void requestPost(UUID postId) {
        publish(POST_PREFIX + postId);
    }

    public void requestUser(UUID userId) {
        publish(USER_PREFIX + userId);
    }

    private void publish(String key) {
        if (s3 == null || isBlank(bucket)) {
            System.out.println("Embedding requests bucket not configured, skipping embedding request " + key);
            return;
        }

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
            s3.putObject(request, RequestBody.empty());
            System.out.println("Requested embedding " + key);
        } catch (Exception e) {
            System.err.println("Failed to request embedding " + key + ": " + e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
