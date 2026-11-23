package com.coolture.messaging;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

public class PostEmbeddingPublisher {

    private static final String BUCKET_ENV = "EMBEDDING_REQUESTS_BUCKET";
    static final String KEY_PREFIX = "posts/";

    private final String bucket;
    private final S3Client s3;

    public PostEmbeddingPublisher() {
        this(System.getenv(BUCKET_ENV));
    }

    private PostEmbeddingPublisher(String bucket) {
        this(bucket, isBlank(bucket) ? null : S3Client.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build());
    }

    PostEmbeddingPublisher(String bucket, S3Client s3) {
        this.bucket = bucket;
        this.s3 = s3;
    }

    public void publish(UUID postId) {
        if (s3 == null || isBlank(bucket)) {
            System.out.println("Embedding requests bucket not configured, skipping embedding request for post " + postId);
            return;
        }

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(KEY_PREFIX + postId)
                .build();
            s3.putObject(request, RequestBody.empty());
            System.out.println("Requested embedding for post " + postId);
        } catch (Exception e) {
            System.err.println("Failed to request embedding for post " + postId + ": " + e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
