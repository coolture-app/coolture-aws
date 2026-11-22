package com.coolture.user.service;

import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.storage.S3StorageService;
import com.coolture.user.dto.HomeDto;
import com.coolture.user.dto.ProfileImageResponse;
import com.coolture.user.dto.SetProfileImageRequest;
import com.coolture.user.dto.UserInterestsDto;
import com.coolture.user.dto.UserListItemDto;
import com.coolture.user.dto.UserProfileUpdateRequest;
import com.coolture.user.repository.InterestRepository;
import com.coolture.user.repository.UserRepository;
import com.coolture.user.repository.UserRepository.AvatarRecord;
import com.coolture.user.repository.UserRepository.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    private final InterestRepository interestRepository = mock(InterestRepository.class);
    private final S3StorageService s3Storage = mock(S3StorageService.class);
    private final UserService service = new UserService(repository, interestRepository, s3Storage);

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void noPicks() throws SQLException {
        when(interestRepository.findPicks(any())).thenReturn(new UserInterestsDto(List.of(), List.of()));
    }
    private static final UUID CALLER_ID = UUID.randomUUID();
    private static final UUID FULL_ID = UUID.randomUUID();
    private static final UUID THUMBNAIL_ID = UUID.randomUUID();

    @Test
    void setHome_storesTheTrimmedName_andRoundsToAboutAKilometre() throws SQLException {
        when(repository.setHome(eq(USER_ID), any())).thenReturn(true);

        HomeDto stored = service.setHome(USER_ID, USER_ID, new HomeDto("  Oliwa, Gdańsk ", new GeoPointDto(54.41123, 18.56789)));

        assertEquals(new HomeDto("Oliwa, Gdańsk", new GeoPointDto(54.41, 18.57)), stored);
        verify(repository).setHome(USER_ID, new UserRepository.HomeRecord("Oliwa, Gdańsk", 54.41, 18.57));
    }

    @Test
    void setHome_forSomeoneElse_isRefused_andStoresNothing() throws SQLException {
        HomeDto home = new HomeDto("Gdynia", new GeoPointDto(54.52, 18.54));

        assertThrows(SecurityException.class, () -> service.setHome(USER_ID, CALLER_ID, home));
        assertThrows(SecurityException.class, () -> service.clearHome(USER_ID, CALLER_ID));
        verify(repository, never()).setHome(any(), any());
        verify(repository, never()).clearHome(any());
    }

    @Test
    void setHome_forAUserWithNoRow_isNotFound() throws SQLException {
        when(repository.setHome(eq(USER_ID), any())).thenReturn(false);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.setHome(USER_ID, USER_ID, new HomeDto("Gdynia", new GeoPointDto(54.52, 18.54))));
        assertTrue(e.getMessage().contains("not found"));
    }

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
        when(repository.search(eq(CALLER_ID), eq(null), any(), any(), eq(21)))
                .thenReturn(List.of(new UserRepository.ListedUser(user, true, true)));

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

        CursorPage<UserListItemDto> page = service.search(CALLER_ID, null, null, 20);

        assertNotNull(page);
        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).isFollowing());
        assertTrue(page.items().get(0).followsYou());
        UserSummaryDto item = page.items().get(0).summary();
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
        when(repository.isFollowing(USER_ID, CALLER_ID)).thenReturn(true);
        when(repository.isBlocked(CALLER_ID, USER_ID)).thenReturn(false);
        when(interestRepository.findPicks(USER_ID)).thenReturn(new UserInterestsDto(List.of("concerts"), List.of("theatre", "cinema")));

        UserProfileDto profile = service.getByIdForCaller(USER_ID, CALLER_ID);

        assertNotNull(profile);
        assertEquals("testuser", profile.username());
        assertEquals(15, profile.followersCount());
        assertEquals(3, profile.followingCount());
        assertTrue(profile.isFollowing());
        assertFalse(profile.isBlocked());
        assertTrue(profile.followsYou());
        assertNotNull(profile.avatar());
        assertEquals("http://localhost:3900/full.jpg", profile.avatar().url());
        assertEquals(List.of("theatre", "cinema"), profile.interests());
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
        assertEquals(List.of(), profile.interests());
    }

    @Test
    void update_updatesFieldsAndReturnsEnrichedProfile() throws SQLException {
        UserProfileUpdateRequest request = new UserProfileUpdateRequest("updated_name", "Jan", "Nowak", "New bio", false);
        var updated = new UserEntity(USER_ID, "updated_name", "Jan", "Nowak", "New bio", Instant.now());

        when(repository.updateUser(USER_ID, "updated_name", "Jan", "Nowak", "New bio", false)).thenReturn(updated);
        when(repository.findActiveAvatar(USER_ID, false)).thenReturn(Optional.empty());

        UserProfileDto profile = service.update(USER_ID, USER_ID, request);

        assertNotNull(profile);
        assertEquals("updated_name", profile.username());
        assertEquals("New bio", profile.bio());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "Anna", "jan kowalski", "jan!", "", "abcdefghijabcdefghijabcdefghijabc"})
    void update_refusesAUsernameOutsideTheRules(String username) {
        UserProfileUpdateRequest request = new UserProfileUpdateRequest(username, null, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> service.update(USER_ID, USER_ID, request));
        verifyNoInteractions(repository);
    }

    @Test
    void update_trimsTheUsernameAndNames() throws SQLException {
        var updated = new UserEntity(USER_ID, "jan.k_2", "Jan", "Nowak", null, Instant.now());
        when(repository.updateUser(USER_ID, "jan.k_2", "Jan", "Nowak", null, null)).thenReturn(updated);

        service.update(USER_ID, USER_ID, new UserProfileUpdateRequest(" jan.k_2 ", " Jan", "Nowak ", null, null));

        verify(repository).updateUser(USER_ID, "jan.k_2", "Jan", "Nowak", null, null);
    }

    @Test
    void update_aTakenUsername_isAConflict() throws SQLException {
        when(repository.updateUser(USER_ID, "taken", null, null, null, null))
            .thenThrow(new SQLException("duplicate key", "23505"));

        assertThrows(IllegalStateException.class,
            () -> service.update(USER_ID, USER_ID, new UserProfileUpdateRequest("taken", null, null, null, null)));
    }

    @Test
    void update_throwsForbidden_whenNotSelf() {
        UserProfileUpdateRequest request = new UserProfileUpdateRequest("hacked", null, null, null, null);

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
    void getMe_usesFallbackUsername_whenNoUsableCandidate() throws SQLException {
        String fallback = "user_" + CALLER_ID.toString().replace("-", "").substring(0, 12);
        var created = new UserEntity(CALLER_ID, fallback, "", "", null, Instant.now());
        when(repository.findById(CALLER_ID)).thenReturn(Optional.of(created));

        service.getMe(CALLER_ID, null, null, null);

        verify(repository).createUserIfAbsent(eq(CALLER_ID), eq(fallback), eq(""), eq(""));
    }

    @Test
    void toUsername_sanitizesAndTruncatesCandidate() {
        assertEquals("jan.kowalski_99", UserService.toUsername(" Jan.Kowalski+99 ", CALLER_ID));
        assertEquals("a".repeat(32), UserService.toUsername("a".repeat(40), CALLER_ID));
        assertEquals("user_" + CALLER_ID.toString().replace("-", "").substring(0, 12),
            UserService.toUsername("+++", CALLER_ID));
    }

    @Test
    void getFollowers_throwsNotFound_whenTargetUserDoesNotExist() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service.getFollowers(USER_ID, CALLER_ID, null, null, 20));
    }

    @Test
    void getFollowers_returnsPage() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        var follower = new UserEntity(CALLER_ID, "follower1", "F", "L", null, Instant.now());
        when(repository.findFollowers(eq(USER_ID), eq(CALLER_ID), any(), any(), eq(21)))
                .thenReturn(List.of(new UserRepository.FollowRow(follower, false, true, "f l")));

        var page = service.getFollowers(USER_ID, CALLER_ID, null, null, 20);

        assertNotNull(page);
        assertEquals(1, page.items().size());
        assertEquals("follower1", page.items().get(0).summary().username());
        assertFalse(page.items().get(0).isFollowing());
        assertTrue(page.items().get(0).followsYou());
    }

    @Test
    void getFollowing_asksWhoInThePageFollowsTheCaller() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        var followed = new UserEntity(UUID.randomUUID(), "followed1", "F", "L", null, Instant.now());
        when(repository.findFollowing(eq(USER_ID), eq(CALLER_ID), any(), any(), eq(21)))
                .thenReturn(List.of(new UserRepository.FollowRow(followed, true, false, "f l")));

        var page = service.getFollowing(USER_ID, CALLER_ID, null, null, 20);

        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).isFollowing());
        assertFalse(page.items().get(0).followsYou());
    }

    @Test
    void getByIdForCaller_followsYouIsFalse_forAGuestAndForYourself() throws SQLException {
        var user = new UserEntity(USER_ID, "testuser", "Jan", "Kowalski", "Bio", Instant.now());
        when(repository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertFalse(service.getByIdForCaller(USER_ID, null).followsYou());
        assertFalse(service.getByIdForCaller(USER_ID, USER_ID).followsYou());
        verify(repository, never()).isFollowing(any(), any());
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
    void block_endsFollowsBothWays_keepsTheOthersBlock_andInsertsBlock() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.isBlocked(CALLER_ID, USER_ID)).thenReturn(false);

        service.block(CALLER_ID, USER_ID);

        verify(repository).deleteRelationAny(CALLER_ID, USER_ID);
        verify(repository).deleteRelation(USER_ID, CALLER_ID, "FOLLOW");
        verify(repository, never()).deleteRelationAny(USER_ID, CALLER_ID);
        verify(repository).insertRelation(eq(CALLER_ID), eq(USER_ID), eq("BLOCK"), any());
    }

    @Test
    void unblock_throwsNotFound_whenNoBlockRelation() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.deleteRelation(CALLER_ID, USER_ID, "BLOCK")).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.unblock(CALLER_ID, USER_ID));
    }

    @Test
    void getByIdForCaller_givesTheBlockerASlimProfile() throws SQLException {
        var user = new UserEntity(USER_ID, "testuser", "Jan", "Kowalski", "Bio", Instant.now());
        when(repository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(repository.isBlocked(CALLER_ID, USER_ID)).thenReturn(true);
        when(repository.findActiveAvatar(USER_ID, false)).thenReturn(Optional.empty());

        UserProfileDto profile = service.getByIdForCaller(USER_ID, CALLER_ID);

        assertTrue(profile.isBlocked());
        assertEquals("testuser", profile.username());
        assertEquals("Jan", profile.firstName());
        assertNull(profile.followersCount());
        assertNull(profile.followingCount());
        assertNull(profile.bio());
        assertNull(profile.interests());
        verify(interestRepository, never()).findPicks(any());
    }

    @Test
    void getByUsernameForCaller_isNotFound_forWhoeverWasBlocked() throws SQLException {
        var user = new UserEntity(USER_ID, "testuser", "Jan", "Kowalski", "Bio", Instant.now());
        when(repository.findByUsername("testuser")).thenReturn(Optional.of(user));
        when(repository.isBlocked(USER_ID, CALLER_ID)).thenReturn(true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> service.getByUsernameForCaller("testuser", CALLER_ID));

        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void getFollowersAndFollowing_areNotFound_acrossABlock() throws SQLException {
        when(repository.existsById(USER_ID)).thenReturn(true);
        when(repository.isBlockedEitherDirection(CALLER_ID, USER_ID)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.getFollowers(USER_ID, CALLER_ID, null, null, 20));
        assertThrows(IllegalArgumentException.class, () -> service.getFollowing(USER_ID, CALLER_ID, null, null, 20));
        verify(repository, never()).findFollowers(any(), any(), any(), any(), anyInt());
        verify(repository, never()).findFollowing(any(), any(), any(), any(), anyInt());
    }
}
