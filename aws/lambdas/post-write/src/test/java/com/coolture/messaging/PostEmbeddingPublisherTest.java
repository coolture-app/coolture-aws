package com.coolture.messaging;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PostEmbeddingPublisherTest {

    private static final String BUCKET = "coolture-dev-embedding-requests-123456789012";

    private final S3Client s3 = mock(S3Client.class);

    @Test
    void publish_writesObjectKeyedByPostId() {
        UUID postId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        new PostEmbeddingPublisher(BUCKET, s3).publish(postId);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertEquals(BUCKET, captor.getValue().bucket());
        assertEquals("posts/11111111-1111-1111-1111-111111111111", captor.getValue().key());
    }

    @Test
    void publish_whenS3Fails_doesNotThrow() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(SdkClientException.create("boom"));

        assertDoesNotThrow(() -> new PostEmbeddingPublisher(BUCKET, s3).publish(UUID.randomUUID()));
    }

    @Test
    void publish_whenBucketMissing_skips() {
        new PostEmbeddingPublisher("", s3).publish(UUID.randomUUID());

        verifyNoInteractions(s3);
    }
}
