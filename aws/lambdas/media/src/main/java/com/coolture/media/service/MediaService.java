package com.coolture.media.service;

import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.storage.S3StorageService;
import com.coolture.common.validation.MediaMimeType;
import com.coolture.media.dto.MediaCompleteRequest;
import com.coolture.media.dto.MediaUploadInitRequest;
import com.coolture.media.dto.MediaUploadInitResponse;
import com.coolture.media.repository.MediaRepository;
import com.coolture.media.repository.MediaRepository.MediaRecord;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public class MediaService {

    private final MediaRepository mediaRepository;
    private final S3StorageService s3StorageService;

    public MediaService() {
        this(new MediaRepository(), new S3StorageService());
    }

    public MediaService(MediaRepository mediaRepository, S3StorageService s3StorageService) {
        this.mediaRepository = mediaRepository;
        this.s3StorageService = s3StorageService;
    }

    public MediaUploadInitResponse initUpload(UUID ownerId, MediaUploadInitRequest request) throws SQLException {
        if (ownerId == null) {
            throw new SecurityException("User not authenticated");
        }

        MediaMimeType.validate(request.mimeType());
        MediaMimeType.validateSize(request.mimeType(), request.sizeBytes());

        UUID mediaId = UUID.randomUUID();
        String ext = extractExtension(request.fileName());
        String objectKey = String.format("%s/%s/%s.%s", request.purpose(), ownerId, UUID.randomUUID(), ext);

        MediaRecord record = new MediaRecord(
                mediaId,
                ownerId,
                objectKey,
                request.fileName(),
                request.purpose(),
                request.mimeType(),
                request.sizeBytes(),
                MediaStatus.PENDING,
                Instant.now(),
                null
        );

        mediaRepository.insert(record);

        S3StorageService.PresignedPutResult presigned =
                s3StorageService.presignPut(objectKey, request.mimeType(), request.sizeBytes());

        return new MediaUploadInitResponse(
                mediaId,
                objectKey,
                presigned.url(),
                presigned.httpMethod(),
                presigned.expiresAt(),
                presigned.requiredHeaders()
        );
    }

    public MediaResourceDto completeUpload(UUID mediaId, UUID callerId, MediaCompleteRequest request) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated");
        }

        MediaRecord media = findOwnedOrThrow(mediaId, callerId);

        if (!MediaStatus.PENDING.equals(media.status())) {
            throw new IllegalStateException("Media upload is already completed or invalid");
        }

        S3StorageService.StoredObject stored = s3StorageService.headObject(media.objectKey())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Uploaded file is missing - PUT it to the upload URL before completing"));
        if (stored.sizeBytes() != media.sizeBytes() || !sameMimeType(stored.contentType(), media.mimeType())) {
            s3StorageService.deleteObject(media.objectKey());
            mediaRepository.markDeleted(mediaId, Instant.now());
            throw new IllegalArgumentException("Uploaded file does not match the declared size or type");
        }

        mediaRepository.updateStatus(mediaId, MediaStatus.UPLOADED);

        String url = s3StorageService.presignGet(media.objectKey());
        return new MediaResourceDto(
                media.id(),
                media.purpose(),
                media.mimeType(),
                media.sizeBytes(),
                MediaStatus.UPLOADED,
                url,
                media.createdAt(),
                null
        );
    }

    public MediaResourceDto getById(UUID mediaId) throws SQLException {
        MediaRecord media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media " + mediaId + " not found"));

        if (MediaStatus.DELETED.equals(media.status())) {
            throw new IllegalArgumentException("Media " + mediaId + " not found");
        }

        String url = s3StorageService.presignGet(media.objectKey());
        return media.toDto(url);
    }

    public void delete(UUID mediaId, UUID callerId) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated");
        }

        MediaRecord media = findOwnedOrThrow(mediaId, callerId);

        if (MediaStatus.DELETED.equals(media.status())) {
            throw new IllegalStateException("Media is already deleted");
        }

        if (MediaStatus.ATTACHED.equals(media.status()) || mediaRepository.isMediaInUse(mediaId)) {
            throw new IllegalStateException("Media is currently in use and cannot be deleted");
        }

        s3StorageService.deleteObject(media.objectKey());
        mediaRepository.markDeleted(mediaId, Instant.now());
    }

    private MediaRecord findOwnedOrThrow(UUID mediaId, UUID callerId) throws SQLException {
        return mediaRepository.findByIdAndOwnerId(mediaId, callerId)
                .orElseThrow(() -> {
                    try {
                        if (mediaRepository.existsById(mediaId)) {
                            return new SecurityException("You do not own this media");
                        }
                    } catch (SQLException ignored) {
                    }
                    return new IllegalArgumentException("Media " + mediaId + " not found");
                });
    }

    private static String extractExtension(String fileName) {
        if (fileName == null) return "bin";
        int dot = fileName.lastIndexOf('.');
        return (dot >= 0 && dot < fileName.length() - 1)
                ? fileName.substring(dot + 1).toLowerCase()
                : "bin";
    }

    private static boolean sameMimeType(String stored, String declared) {
        if (stored == null) {
            return false;
        }
        return stored.split(";", 2)[0].trim().equalsIgnoreCase(declared);
    }
}
