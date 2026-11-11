package com.coolture.media.service;

import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.storage.S3StorageService;
import com.coolture.media.dto.MediaCompleteRequest;
import com.coolture.media.dto.MediaUploadInitRequest;
import com.coolture.media.dto.MediaUploadInitResponse;
import com.coolture.media.repository.MediaRepository;
import com.coolture.media.repository.MediaRepository.MediaRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MediaServiceTest {

    private final MediaRepository repository = mock(MediaRepository.class);
    private final S3StorageService s3Storage = mock(S3StorageService.class);
    private final MediaService service = new MediaService(repository, s3Storage);

    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID MEDIA_ID = UUID.randomUUID();

    @Test
    void initUpload_createsMediaRecord_andReturnsPresignedPutUrl() throws SQLException {
        MediaUploadInitRequest request = new MediaUploadInitRequest(
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                "cover.jpg"
        );

        when(s3Storage.presignPut(any(), eq("image/jpeg"), eq(102400L))).thenReturn(
                new S3StorageService.PresignedPutResult(
                        "http://localhost:3900/bucket/key?sig=xyz",
                        "PUT",
                        Instant.now().plusSeconds(900),
                        Map.of("Content-Type", "image/jpeg")
                )
        );

        MediaUploadInitResponse response = service.initUpload(CALLER_ID, request);

        assertNotNull(response);
        assertNotNull(response.mediaId());
        assertTrue(response.objectKey().contains("EVENT_COVER/" + CALLER_ID));
        assertTrue(response.objectKey().endsWith(".jpg"));
        assertEquals("http://localhost:3900/bucket/key?sig=xyz", response.uploadUrl());
        assertEquals("PUT", response.httpMethod());

        ArgumentCaptor<MediaRecord> recordCaptor = ArgumentCaptor.forClass(MediaRecord.class);
        verify(repository).insert(recordCaptor.capture());
        MediaRecord saved = recordCaptor.getValue();
        assertEquals(CALLER_ID, saved.ownerId());
        assertEquals(MediaPurpose.EVENT_COVER, saved.purpose());
        assertEquals(MediaStatus.PENDING, saved.status());
    }

    @Test
    void initUpload_throwsException_whenMimeTypeNotAllowed() throws SQLException {
        MediaUploadInitRequest request = new MediaUploadInitRequest(
                MediaPurpose.EVENT_COVER,
                "application/pdf",
                102400L,
                "document.pdf"
        );

        assertThrows(IllegalArgumentException.class, () -> service.initUpload(CALLER_ID, request));
        verify(repository, never()).insert(any());
    }

    @Test
    void completeUpload_updatesStatusToUploaded_andReturnsDtoWithPresignedGetUrl() throws SQLException {
        MediaRecord record = new MediaRecord(
                MEDIA_ID,
                CALLER_ID,
                "EVENT_COVER/123/abc.jpg",
                "cover.jpg",
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                MediaStatus.PENDING,
                Instant.now(),
                null
        );

        when(repository.findByIdAndOwnerId(MEDIA_ID, CALLER_ID)).thenReturn(Optional.of(record));
        when(s3Storage.headObject("EVENT_COVER/123/abc.jpg"))
                .thenReturn(Optional.of(new S3StorageService.StoredObject(102400L, "image/jpeg")));
        when(s3Storage.presignGet("EVENT_COVER/123/abc.jpg")).thenReturn("http://localhost:3900/get-url");

        MediaResourceDto dto = service.completeUpload(MEDIA_ID, CALLER_ID, new MediaCompleteRequest(null));

        assertNotNull(dto);
        assertEquals(MEDIA_ID, dto.id());
        assertEquals(MediaStatus.UPLOADED, dto.status());
        assertEquals("http://localhost:3900/get-url", dto.url());
        verify(repository).updateStatus(MEDIA_ID, MediaStatus.UPLOADED);
    }

    @Test
    void initUpload_throwsBadRequest_whenImageExceedsSizeLimit() throws SQLException {
        MediaUploadInitRequest request = new MediaUploadInitRequest(
                MediaPurpose.EVENT_COVER,
                "image/png",
                10L * 1024 * 1024 + 1,
                "huge.png"
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.initUpload(CALLER_ID, request));
        assertTrue(ex.getMessage().startsWith("File is too large"));
        verify(repository, never()).insert(any());
        verify(s3Storage, never()).presignPut(any(), any(), anyLong());
    }

    @Test
    void initUpload_allowsLargerVideos_upToVideoLimit() throws SQLException {
        MediaUploadInitRequest request = new MediaUploadInitRequest(
                MediaPurpose.EVENT_COVER,
                "video/mp4",
                100L * 1024 * 1024,
                "clip.mp4"
        );
        when(s3Storage.presignPut(any(), eq("video/mp4"), eq(100L * 1024 * 1024))).thenReturn(
                new S3StorageService.PresignedPutResult("http://u", "PUT", Instant.now(), Map.of()));

        assertDoesNotThrow(() -> service.initUpload(CALLER_ID, request));
    }

    @Test
    void completeUpload_throwsBadRequest_whenObjectWasNotUploaded() throws SQLException {
        MediaRecord record = pendingRecord();
        when(repository.findByIdAndOwnerId(MEDIA_ID, CALLER_ID)).thenReturn(Optional.of(record));
        when(s3Storage.headObject(record.objectKey())).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.completeUpload(MEDIA_ID, CALLER_ID, null));
        assertTrue(ex.getMessage().startsWith("Uploaded file is missing"));
        verify(repository, never()).updateStatus(any(), any());
    }

    @Test
    void completeUpload_deletesObjectAndMedia_whenUploadedFileDoesNotMatch() throws SQLException {
        MediaRecord record = pendingRecord();
        when(repository.findByIdAndOwnerId(MEDIA_ID, CALLER_ID)).thenReturn(Optional.of(record));
        when(s3Storage.headObject(record.objectKey()))
                .thenReturn(Optional.of(new S3StorageService.StoredObject(999L, "image/jpeg")));

        assertThrows(IllegalArgumentException.class, () -> service.completeUpload(MEDIA_ID, CALLER_ID, null));
        verify(s3Storage).deleteObject(record.objectKey());
        verify(repository).markDeleted(eq(MEDIA_ID), any(Instant.class));
        verify(repository, never()).updateStatus(any(), any());
    }

    private static MediaRecord pendingRecord() {
        return new MediaRecord(
                MEDIA_ID,
                CALLER_ID,
                "EVENT_COVER/123/abc.jpg",
                "cover.jpg",
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                MediaStatus.PENDING,
                Instant.now(),
                null
        );
    }

    @Test
    void completeUpload_throwsConflict_whenNotPending() throws SQLException {
        MediaRecord record = new MediaRecord(
                MEDIA_ID,
                CALLER_ID,
                "EVENT_COVER/123/abc.jpg",
                "cover.jpg",
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                MediaStatus.UPLOADED,
                Instant.now(),
                null
        );

        when(repository.findByIdAndOwnerId(MEDIA_ID, CALLER_ID)).thenReturn(Optional.of(record));

        assertThrows(IllegalStateException.class, () -> service.completeUpload(MEDIA_ID, CALLER_ID, null));
        verify(repository, never()).updateStatus(any(), any());
    }

    @Test
    void completeUpload_throwsForbidden_whenNotOwner() throws SQLException {
        when(repository.findByIdAndOwnerId(MEDIA_ID, CALLER_ID)).thenReturn(Optional.empty());
        when(repository.existsById(MEDIA_ID)).thenReturn(true);

        assertThrows(SecurityException.class, () -> service.completeUpload(MEDIA_ID, CALLER_ID, null));
    }

    @Test
    void getById_returnsDtoWithPresignedGetUrl_whenFound() throws SQLException {
        MediaRecord record = new MediaRecord(
                MEDIA_ID,
                CALLER_ID,
                "EVENT_COVER/123/abc.jpg",
                "cover.jpg",
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                MediaStatus.UPLOADED,
                Instant.now(),
                null
        );

        when(repository.findById(MEDIA_ID)).thenReturn(Optional.of(record));
        when(s3Storage.presignGet("EVENT_COVER/123/abc.jpg")).thenReturn("http://localhost:3900/view");

        MediaResourceDto dto = service.getById(MEDIA_ID);

        assertNotNull(dto);
        assertEquals(MEDIA_ID, dto.id());
        assertEquals("http://localhost:3900/view", dto.url());
    }

    @Test
    void getById_throwsNotFound_whenDeleted() throws SQLException {
        MediaRecord record = new MediaRecord(
                MEDIA_ID,
                CALLER_ID,
                "EVENT_COVER/123/abc.jpg",
                "cover.jpg",
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                MediaStatus.DELETED,
                Instant.now(),
                Instant.now()
        );

        when(repository.findById(MEDIA_ID)).thenReturn(Optional.of(record));

        assertThrows(IllegalArgumentException.class, () -> service.getById(MEDIA_ID));
    }

    @Test
    void delete_callsS3Delete_andMarksDeletedInDb() throws SQLException {
        MediaRecord record = new MediaRecord(
                MEDIA_ID,
                CALLER_ID,
                "EVENT_COVER/123/abc.jpg",
                "cover.jpg",
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                MediaStatus.UPLOADED,
                Instant.now(),
                null
        );

        when(repository.findByIdAndOwnerId(MEDIA_ID, CALLER_ID)).thenReturn(Optional.of(record));
        when(repository.isMediaInUse(MEDIA_ID)).thenReturn(false);

        service.delete(MEDIA_ID, CALLER_ID);

        verify(s3Storage).deleteObject("EVENT_COVER/123/abc.jpg");
        verify(repository).markDeleted(eq(MEDIA_ID), any());
    }

    @Test
    void delete_throwsConflict_whenAlreadyDeleted() throws SQLException {
        MediaRecord record = new MediaRecord(
                MEDIA_ID,
                CALLER_ID,
                "EVENT_COVER/123/abc.jpg",
                "cover.jpg",
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                MediaStatus.DELETED,
                Instant.now(),
                Instant.now()
        );

        when(repository.findByIdAndOwnerId(MEDIA_ID, CALLER_ID)).thenReturn(Optional.of(record));

        assertThrows(IllegalStateException.class, () -> service.delete(MEDIA_ID, CALLER_ID));
        verify(s3Storage, never()).deleteObject(any());
        verify(repository, never()).markDeleted(any(), any());
    }

    @Test
    void delete_throwsConflict_whenInUse() throws SQLException {
        MediaRecord record = new MediaRecord(
                MEDIA_ID,
                CALLER_ID,
                "EVENT_COVER/123/abc.jpg",
                "cover.jpg",
                MediaPurpose.EVENT_COVER,
                "image/jpeg",
                102400L,
                MediaStatus.UPLOADED,
                Instant.now(),
                null
        );

        when(repository.findByIdAndOwnerId(MEDIA_ID, CALLER_ID)).thenReturn(Optional.of(record));
        when(repository.isMediaInUse(MEDIA_ID)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.delete(MEDIA_ID, CALLER_ID));
        verify(s3Storage, never()).deleteObject(any());
        verify(repository, never()).markDeleted(any(), any());
    }
}
