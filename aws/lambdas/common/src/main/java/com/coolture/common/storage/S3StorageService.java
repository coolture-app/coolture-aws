package com.coolture.common.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class S3StorageService {

    private static final Duration PUT_EXPIRY = Duration.ofMinutes(15);
    private static final Duration GET_EXPIRY = Duration.ofHours(1);

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucketName;

    public record PresignedPutResult(
            String url,
            String httpMethod,
            Instant expiresAt,
            Map<String, String> requiredHeaders
    ) {}

    public record StoredObject(long sizeBytes, String contentType) {}

    public S3StorageService() {
        this(createDefaultS3Client(), createDefaultPresigner(), resolveBucketName());
    }

    public S3StorageService(S3Client s3Client, S3Presigner presigner, String bucketName) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucketName = bucketName;
    }

    public String getBucketName() {
        return bucketName;
    }

    public PresignedPutResult presignPut(String objectKey, String mimeType, long sizeBytes) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(mimeType)
                .contentLength(sizeBytes)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(PUT_EXPIRY)
                .putObjectRequest(putRequest)
                .build());

        Map<String, String> requiredHeaders = presigned.signedHeaders().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> String.join(",", e.getValue())
                ));

        return new PresignedPutResult(
                presigned.url().toString(),
                "PUT",
                presigned.expiration(),
                requiredHeaders
        );
    }

    public String presignGet(String objectKey) {
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();

        PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(GET_EXPIRY)
                .getObjectRequest(getRequest)
                .build());

        return presigned.url().toString();
    }

    public Optional<StoredObject> headObject(String objectKey) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build());
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void deleteObject(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build());
    }

    private static String resolveBucketName() {
        String bucket = System.getenv("MEDIA_BUCKET_NAME");
        if (bucket != null && !bucket.isBlank()) {
            return bucket;
        }
        String env = System.getenv("ENVIRONMENT");
        if (env != null && !env.isBlank()) {
            return "coolture-" + env + "-media";
        }
        return "coolture-bucket";
    }

    private static S3Client createDefaultS3Client() {
        String internalEndpoint = getEnvAny("S3_INTERNAL_ENDPOINT", "S3_ENDPOINT_URL");
        Region region = resolveRegion();
        AwsCredentialsProvider credentialsProvider = resolveCredentialsProvider();

        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider);

        if (internalEndpoint != null && !internalEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(internalEndpoint));
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .checksumValidationEnabled(false)
                    .build());
        }

        return builder.build();
    }

    private static S3Presigner createDefaultPresigner() {
        String publicEndpoint = getEnvAny("S3_PUBLIC_ENDPOINT_URL", "S3_ENDPOINT_URL", "S3_INTERNAL_ENDPOINT");
        Region region = resolveRegion();
        AwsCredentialsProvider credentialsProvider = resolveCredentialsProvider();

        S3Presigner.Builder builder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentialsProvider);

        if (publicEndpoint != null && !publicEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(publicEndpoint));
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .checksumValidationEnabled(false)
                    .build());
        }

        return builder.build();
    }

    private static Region resolveRegion() {
        String regionStr = getEnvAny("S3_REGION", "AWS_REGION", "AWS_DEFAULT_REGION");
        if (regionStr != null && !regionStr.isBlank()) {
            return Region.of(regionStr);
        }
        return Region.EU_CENTRAL_1;
    }

    private static AwsCredentialsProvider resolveCredentialsProvider() {
        String accessKey = getEnvAny("GARAGE_ACCESS_KEY_ID", "AWS_ACCESS_KEY_ID");
        String secretKey = getEnvAny("GARAGE_ACCESS_KEY_SECRET", "AWS_SECRET_ACCESS_KEY");

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }

        return DefaultCredentialsProvider.create();
    }

    private static String getEnvAny(String... names) {
        for (String name : names) {
            String val = System.getenv(name);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }
}
