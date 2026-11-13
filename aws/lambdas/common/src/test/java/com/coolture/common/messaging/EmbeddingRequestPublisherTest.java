package com.coolture.common.messaging;

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

class EmbeddingRequestPublisherTest {

    private static final String BUCKET = "coolture-dev-embedding-requests-123456789012";
    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final S3Client s3 = mock(S3Client.class);

    @Test
    void requestPost_writesObjectKeyedByPostId() {
        new EmbeddingRequestPublisher(BUCKET, s3).requestPost(ID);

        PutObjectRequest request = capturedRequest();
        assertEquals(BUCKET, request.bucket());
        assertEquals("posts/11111111-1111-1111-1111-111111111111", request.key());
    }

    @Test
    void request_whenS3Fails_doesNotThrow() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(SdkClientException.create("boom"));

        assertDoesNotThrow(() -> new EmbeddingRequestPublisher(BUCKET, s3).requestPost(UUID.randomUUID()));
    }

    @Test
    void request_whenBucketMissing_skips() {
        new EmbeddingRequestPublisher("", s3).requestPost(UUID.randomUUID());

        verifyNoInteractions(s3);
    }

    private PutObjectRequest capturedRequest() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        return captor.getValue();
    }
}
