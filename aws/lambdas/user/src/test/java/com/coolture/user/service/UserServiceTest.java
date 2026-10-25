package com.coolture.user.service;

import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.storage.S3StorageService;
import com.coolture.user.dto.ProfileImageResponse;
import com.coolture.user.dto.SetProfileImageRequest;
import com.coolture.user.dto.UserProfileUpdateRequest;
import com.coolture.user.repository.UserRepository;
import com.coolture.user.repository.UserRepository.AvatarRecord;
import com.coolture.user.repository.UserRepository.UserEntity;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private final UserRepository repository = mock(UserRepository.class);
    private final S3StorageService s3Storage = mock(S3StorageService.class);
    private final UserService service = new UserService(repository, s3Storage);

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID FULL_ID = UUID.randomUUID();
    private static final UUID THUMBNAIL_ID = UUID.randomUUID();

    @Test
    void setProfileImage_validatesMedia_andInsertsTransactional() throws SQLException {
        SetProfileImageRequest request = new SetProfileImageRequest(FULL_ID, THUMBNAIL_ID);

        ProfileImageResponse response = service.setProfileImage(USER_ID, USER_ID, request);

        assertNotNull(response);
        assertEquals(FULL_ID, response.fullMediaId());
        assertEquals(THUMBNAIL_ID, response.thumbnailMediaId());
        assertNotNull(response.setAt());

        verify(repository).validateMediaOwnedAndNotDeleted(FULL_ID, USER_ID);
        verify(repository).validateMediaOwnedAndNotDeleted(THUMBNAIL_ID, USER_ID);
        verify(repository).setProfileImageTransactional(eq(USER_ID), eq(FULL_ID), eq(THUMBNAIL_ID), any());
    }

    @Test
    void setProfileImage_throwsForbidden_whenNotSelf() {
        SetProfileImageRequest request = new SetProfileImageRequest(FULL_ID, THUMBNAIL_ID);

        assertThrows(SecurityException.class, () -> service.setProfileImage(USER_ID, CALLER_ID, request));
        verifyNoInteractions(repository);
    }

    @Test
    void removeProfileImage_deactivatesAllActive_whenSelf() throws SQLException {
        service.removeProfileImage(USER_ID, USER_ID);

        verify(repository).deactivateProfileImages(eq(USER_ID), any());
    }

    @Test
    void removeProfileImage_throwsForbidden_whenNotSelf() {
        assertThrows(SecurityException.class, () -> service.removeProfileImage(USER_ID, CALLER_ID));
        verifyNoInteractions(repository);
    }

    @Test
    void search_enrichesWithAvatarsAndFollowerCounts() throws SQLException {
        var user = new UserEntity(USER_ID, "testuser", "Jan", "Kowalski", "Bio", Instant.now());
        when(repository.search(eq(null), any(), any(), eq(21))).thenReturn(List.of(user));

        var avatarRecord = new AvatarRecord(
                THUMBNAIL_ID,
                "PROFILE_IMAGE_THUMBNAIL/key.jpg",
                MediaPurpose.PROFILE_IMAGE_THUMBNAIL,
                "image/jpeg",
                2048L,
                MediaStatus.UPLOADED,
                Instant.now(),
                null
        );
        when(repository.findActiveThumbnails(List.of(USER_ID))).thenReturn(Map.of(USER_ID, avatarRecord));
        when(s3Storage.presignGet("PROFILE_IMAGE_THUMBNAIL/key.jpg")).thenReturn("http://localhost:3900/thumb.jpg");
        when(repository.countFollowers(USER_ID)).thenReturn(5);
        when(repository.countFollowing(USER_ID)).thenReturn(10);

        CursorPage<UserSummaryDto> page = service.search(CALLER_ID, null, null, 20);

        assertNotNull(page);
        assertEquals(1, page.items().size());
        UserSummaryDto item = page.items().get(0);
        assertEquals("testuser", item.username());
        assertEquals(5, item.followersCount());
        assertEquals(10, item.followingCount());
        assertNotNull(item.avatar());
        assertEquals("http://localhost:3900/thumb.jpg", item.avatar().url());
    }

    @Test
    void search_throwsUnauthorized_whenNotAuthenticated() {
        assertThrows(SecurityException.class, () -> service.search(null, null, null, 20));
        verifyNoInteractions(repository);
    }

    @Test
    void getByIdForCaller_enrichesWithFullAvatarAndRelations() throws SQLException {
        var user = new UserEntity(USER_ID, "testuser", "Jan", "Kowalski", "Bio", Instant.now());
        when(repository.findById(USER_ID)).thenReturn(Optional.of(user));

        var avatarRecord = new AvatarRecord(
                FULL_ID,
                "PROFILE_IMAGE/full.jpg",
                MediaPurpose.PROFILE_IMAGE,
                "image/jpeg",
                204800L,
                MediaStatus.UPLOADED,
                Instant.now(),
                null
        );
        when(repository.findActiveAvatar(USER_ID, false)).thenReturn(Optional.of(avatarRecord));
        when(s3Storage.presignGet("PROFILE_IMAGE/full.jpg")).thenReturn("http://localhost:3900/full.jpg");
        when(repository.countFollowers(USER_ID)).thenReturn(15);
        when(repository.countFollowing(USER_ID)).thenReturn(3);
        when(repository.isFollowing(CALLER_ID, USER_ID)).thenReturn(true);
        when(repository.isBlocked(CALLER_ID, USER_ID)).thenReturn(false);

        UserProfileDto profile = service.getByIdForCaller(USER_ID, CALLER_ID);

        assertNotNull(profile);
        assertEquals("testuser", profile.username());
        assertEquals(15, profile.followersCount());
        assertEquals(3, profile.followingCount());
        assertTrue(profile.isFollowing());
        assertFalse(profile.isBlocked());
        assertNotNull(profile.avatar());
        assertEquals("http://localhost:3900/full.jpg", profile.avatar().url());
    }

    @Test
    void getByIdForCaller_throwsNotFound_whenUserMissing() throws SQLException {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getByIdForCaller(USER_ID, CALLER_ID));
    }

    @Test
    void getByUsernameForCaller_enrichesWithFullAvatar() throws SQLException {
        var user = new UserEntity(USER_ID, "testuser", "Jan", "Kowalski", "Bio", Instant.now());
        when(repository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(repository.findActiveAvatar(USER_ID, false)).thenReturn(Optional.empty());

        UserProfileDto profile = service.getByUsernameForCaller("testuser", CALLER_ID);

        assertNotNull(profile);
        assertEquals("testuser", profile.username());
        assertNull(profile.avatar());
    }

    @Test
    void update_updatesFieldsAndReturnsEnrichedProfile() throws SQLException {
        UserProfileUpdateRequest request = new UserProfileUpdateRequest("updated_name", "Jan", "Nowak", "New bio");
        var updated = new UserEntity(USER_ID, "updated_name", "Jan", "Nowak", "New bio", Instant.now());

        when(repository.updateUser(USER_ID, "updated_name", "Jan", "Nowak", "New bio")).thenReturn(updated);
        when(repository.findActiveAvatar(USER_ID, false)).thenReturn(Optional.empty());

        UserProfileDto profile = service.update(USER_ID, USER_ID, request);

        assertNotNull(profile);
        assertEquals("updated_name", profile.username());
        assertEquals("New bio", profile.bio());
    }

    @Test
    void update_throwsForbidden_whenNotSelf() {
        UserProfileUpdateRequest request = new UserProfileUpdateRequest("hacked", null, null, null);

        assertThrows(SecurityException.class, () -> service.update(USER_ID, CALLER_ID, request));
        verifyNoInteractions(repository);
    }

    @Test
    void getMe_createsUser_whenNotExists() throws SQLException {
        var created = new UserEntity(CALLER_ID, "autouser", "Jan", "K", null, Instant.now());
        when(repository.createUserIfAbsent(eq(CALLER_ID), eq("autouser"), eq("Jan"), eq("K"))).thenReturn(created);
        when(repository.findById(CALLER_ID)).thenReturn(Optional.of(created));

        UserProfileDto profile = service.getMe(CALLER_ID, "autouser", "Jan", "K");

        assertNotNull(profile);
        assertEquals("autouser", profile.username());
        verify(repository).createUserIfAbsent(eq(CALLER_ID), eq("autouser"), eq("Jan"), eq("K"));
    }

    @Test
    void getFollowers_throwsNotFound_whenTargetUserDoesNotExist() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.getFollowers(USER_ID, null, 20));
    }

    @Test
    void getFollowers_returnsPage() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        var follower = new UserEntity(CALLER_ID, "follower1", "F", "L", null, Instant.now());
        when(repository.findFollowers(eq(USER_ID), any(), any(), eq(21)))
                .thenReturn(List.of(new UserRepository.UserRelationRow(follower, Instant.now())));

        var page = service.getFollowers(USER_ID, null, 20);

        assertNotNull(page);
        assertEquals(1, page.items().size());
        assertEquals("follower1", page.items().get(0).username());
    }

    @Test
    void getBlocking_throwsForbidden_whenCallerIsNotTarget() {
        assertThrows(SecurityException.class, () -> service.getBlocking(USER_ID, CALLER_ID, null, 20));
    }

    @Test
    void follow_throwsConflict_whenSelf() {
        assertThrows(IllegalStateException.class, () -> service.follow(USER_ID, USER_ID));
    }

    @Test
    void follow_throwsForbidden_whenBlocked() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.isBlockedEitherDirection(CALLER_ID, USER_ID)).thenReturn(true);

        assertThrows(SecurityException.class, () -> service.follow(CALLER_ID, USER_ID));
        verify(repository, never()).insertRelation(any(), any(), any(), any());
    }

    @Test
    void follow_throwsConflict_whenAlreadyFollowing() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.isFollowing(CALLER_ID, USER_ID)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.follow(CALLER_ID, USER_ID));
    }

    @Test
    void follow_succeeds_whenValid() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.isFollowing(CALLER_ID, USER_ID)).thenReturn(false);

        service.follow(CALLER_ID, USER_ID);

        verify(repository).insertRelation(eq(CALLER_ID), eq(USER_ID), eq("FOLLOW"), any());
    }

    @Test
    void unfollow_throwsNotFound_whenNoRelation() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.deleteRelation(CALLER_ID, USER_ID, "FOLLOW")).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.unfollow(CALLER_ID, USER_ID));
    }

    @Test
    void unfollow_succeeds_whenRelationExists() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.deleteRelation(CALLER_ID, USER_ID, "FOLLOW")).thenReturn(1);

        service.unfollow(CALLER_ID, USER_ID);

        verify(repository).deleteRelation(CALLER_ID, USER_ID, "FOLLOW");
    }

    @Test
    void block_clearsRelationsBothWays_andInsertsBlock() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.isBlocked(CALLER_ID, USER_ID)).thenReturn(false);

        service.block(CALLER_ID, USER_ID);

        verify(repository).deleteRelationAny(CALLER_ID, USER_ID);
        verify(repository).deleteRelationAny(USER_ID, CALLER_ID);
        verify(repository).insertRelation(eq(CALLER_ID), eq(USER_ID), eq("BLOCK"), any());
    }

    @Test
    void unblock_throwsNotFound_whenNoBlockRelation() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.deleteRelation(CALLER_ID, USER_ID, "BLOCK")).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.unblock(CALLER_ID, USER_ID));
    }
}

