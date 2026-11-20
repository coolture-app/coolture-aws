package com.coolture.user.service;

import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.pagination.CursorCodec;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.CursorPayload;
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
import com.coolture.user.repository.UserRepository.HomeRecord;
import com.coolture.user.repository.UserRepository.ListedUser;
import com.coolture.user.repository.UserRepository.UserEntity;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public class UserService {

    private static final int MAX_USERNAME_LENGTH = 32;

    private final UserRepository userRepository;
    private final InterestRepository interestRepository;
    private final S3StorageService s3StorageService;

    public UserService() {
        this(new UserRepository(), new InterestRepository(), new S3StorageService());
    }

    public UserService(UserRepository userRepository, InterestRepository interestRepository, S3StorageService s3StorageService) {
        this.userRepository = userRepository;
        this.interestRepository = interestRepository;
        this.s3StorageService = s3StorageService;
    }

    public CursorPage<UserListItemDto> search(UUID callerId, String q, String cursor, int limit) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }

        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        List<ListedUser> users = userRepository.search(callerId, q, cursorCreatedAt, cursorId, limit + 1);

        Map<UUID, AvatarRecord> avatarMap = userRepository.findActiveThumbnails(users.stream().map(u -> u.user().id()).toList());
        List<UserListItemDto> dtos = new ArrayList<>();
        for (ListedUser listed : users) {
            dtos.add(toListItem(listed.user(), listed.followsYou(), avatarMap));
        }

        return CursorPage.of(dtos, limit, dto -> dto.summary().id(), dto -> dto.summary().createdAt());
    }

    public UserProfileDto getByIdForCaller(UUID targetId, UUID callerId) throws SQLException {
        UserEntity user = userRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("User " + targetId + " not found"));

        return profileFor(user, callerId, targetId.toString());
    }

    public UserProfileDto getByUsernameForCaller(String username, UUID callerId) throws SQLException {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User " + username + " not found"));

        return profileFor(user, callerId, username);
    }

    /**
     * The blocker gets a slim profile so its Odblokuj keeps working; whoever was blocked gets what a missing account
     * gives. In a mutual block both are blockers.
     */
    private UserProfileDto profileFor(UserEntity user, UUID callerId, String asked) throws SQLException {
        if (callerId != null && !callerId.equals(user.id())) {
            if (userRepository.isBlocked(callerId, user.id())) {
                return new UserProfileDto(user.id(), user.username(), user.firstName(), user.lastName(), avatarOf(user.id()),
                    null, null, null, null, false, true, false, null, null);
            }
            if (userRepository.isBlocked(user.id(), callerId)) {
                throw new IllegalArgumentException("User " + asked + " not found");
            }
        }
        return toProfileDto(user, callerId);
    }

    /** Someone's followers, following and interests are as hidden across a block as their profile. */
    private void requireNoBlock(UUID targetUserId, UUID callerId) throws SQLException {
        if (userRepository.isBlockedEitherDirection(callerId, targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
    }

    public UserProfileDto update(UUID targetId, UUID callerId, UserProfileUpdateRequest request) throws SQLException {
        requireSelf(targetId, callerId, "You can only update your own profile");
        if (request.username() != null && request.username().length() > 32) {
            throw new IllegalArgumentException("Username must not exceed 32 characters");
        }

        UserEntity user = userRepository.updateUser(
                targetId,
                request.username(),
                request.firstName(),
                request.lastName(),
                request.bio(),
                request.shareStances()
        );

        return toProfileDto(user, callerId);
    }

    public ProfileImageResponse setProfileImage(UUID targetId, UUID callerId, SetProfileImageRequest request) throws SQLException {
        requireSelf(targetId, callerId, "You can only manage your own profile image");

        userRepository.validateMediaOwnedAndNotDeleted(request.fullMediaId(), targetId);
        userRepository.validateMediaOwnedAndNotDeleted(request.thumbnailMediaId(), targetId);

        Instant now = Instant.now();
        userRepository.setProfileImageTransactional(targetId, request.fullMediaId(), request.thumbnailMediaId(), now);

        return new ProfileImageResponse(request.fullMediaId(), request.thumbnailMediaId(), now);
    }

    public void removeProfileImage(UUID targetId, UUID callerId) throws SQLException {
        requireSelf(targetId, callerId, "You can only manage your own profile image");

        userRepository.deactivateProfileImages(targetId, Instant.now());
    }

    public UserProfileDto getMe(UUID callerId, String username, String firstName, String lastName) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        String uname = toUsername(username, callerId);
        String fName = firstName != null ? firstName : "";
        String lName = lastName != null ? lastName : "";
        userRepository.createUserIfAbsent(callerId, uname, fName, lName);
        return getByIdForCaller(callerId, callerId);
    }

    public boolean sharesStances(UUID callerId) throws SQLException {
        return userRepository.sharesStances(callerId);
    }

    public Optional<HomeDto> getHome(UUID callerId) throws SQLException {
        return userRepository.findHome(callerId)
                .map(home -> new HomeDto(home.name(), new GeoPointDto(home.latitude(), home.longitude())));
    }

    /**
     * Stores the place as the client picked it: Lambdas can't reach a geocoder to check it, and a wrong point only
     * affects the owner's own ranking.
     */
    public HomeDto setHome(UUID targetId, UUID callerId, HomeDto request) throws SQLException {
        requireSelf(targetId, callerId, "You can only set your own home");
        HomeRecord home = new HomeRecord(request.name().trim(),
                roundToKilometre(request.coordinates().latitude()), roundToKilometre(request.coordinates().longitude()));
        if (!userRepository.setHome(targetId, home)) {
            throw new IllegalArgumentException("User " + targetId + " not found");
        }
        return new HomeDto(home.name(), new GeoPointDto(home.latitude(), home.longitude()));
    }

    public void clearHome(UUID targetId, UUID callerId) throws SQLException {
        requireSelf(targetId, callerId, "You can only remove your own home");
        userRepository.clearHome(targetId);
    }

    private static void requireSelf(UUID targetId, UUID callerId, String refusal) {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (!targetId.equals(callerId)) {
            throw new SecurityException(refusal);
        }
    }

    /** 2 decimals, about 1.1 km, as the feed rounds a browser position. */
    private static double roundToKilometre(double coordinate) {
        return Math.round(coordinate * 100) / 100.0;
    }

    public CursorPage<UserListItemDto> getFollowers(UUID targetUserId, UUID callerId, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
        requireNoBlock(targetUserId, callerId);
        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        List<UserRepository.UserRelationRow> rows = userRepository.findFollowers(targetUserId, callerId, cursorCreatedAt, cursorId, limit + 1);
        return toRelationPage(rows, limit);
    }

    public CursorPage<UserListItemDto> getFollowing(UUID targetUserId, UUID callerId, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
        requireNoBlock(targetUserId, callerId);
        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        List<UserRepository.UserRelationRow> rows = userRepository.findFollowing(targetUserId, callerId, cursorCreatedAt, cursorId, limit + 1);
        return toRelationPage(rows, limit);
    }

    public CursorPage<UserListItemDto> getBlocking(UUID targetUserId, UUID callerId, String cursor, int limit) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (!callerId.equals(targetUserId)) {
            throw new SecurityException("You can only view your own block list");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        List<UserRepository.UserRelationRow> rows = userRepository.findBlocking(targetUserId, callerId, cursorCreatedAt, cursorId, limit + 1);
        return toRelationPage(rows, limit);
    }

    public void follow(UUID callerId, UUID targetUserId) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (callerId.equals(targetUserId)) {
            throw new IllegalStateException("Cannot FOLLOW yourself");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
        if (userRepository.isBlockedEitherDirection(callerId, targetUserId)) {
            throw new SecurityException("Cannot follow: there is a block between you and this user");
        }
        if (userRepository.isFollowing(callerId, targetUserId)) {
            throw new IllegalStateException("Already following this user");
        }
        userRepository.insertRelation(callerId, targetUserId, "FOLLOW", Instant.now());
    }

    public void unfollow(UUID callerId, UUID targetUserId) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
        int deleted = userRepository.deleteRelation(callerId, targetUserId, "FOLLOW");
        if (deleted == 0) {
            throw new IllegalArgumentException("Follow relation not found: " + targetUserId);
        }
    }

    public void block(UUID callerId, UUID targetUserId) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (callerId.equals(targetUserId)) {
            throw new IllegalStateException("Cannot BLOCK yourself");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
        if (userRepository.isBlocked(callerId, targetUserId)) {
            throw new IllegalStateException("Already blocking this user");
        }
        // One row per direction: the caller's follow gives way to the block, and the other's block, if any, stays.
        userRepository.deleteRelationAny(callerId, targetUserId);
        userRepository.deleteRelation(targetUserId, callerId, "FOLLOW");
        userRepository.insertRelation(callerId, targetUserId, "BLOCK", Instant.now());
    }

    public void unblock(UUID callerId, UUID targetUserId) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
        int deleted = userRepository.deleteRelation(callerId, targetUserId, "BLOCK");
        if (deleted == 0) {
            throw new IllegalArgumentException("Block relation not found: " + targetUserId);
        }
    }

    private CursorPage<UserListItemDto> toRelationPage(List<UserRepository.UserRelationRow> rows, int limit) throws SQLException {
        Map<UUID, AvatarRecord> avatarMap = userRepository.findActiveThumbnails(rows.stream().map(r -> r.user().id()).toList());

        Map<UUID, Instant> relationCreatedAtMap = new HashMap<>();
        List<UserListItemDto> dtos = new ArrayList<>();
        for (UserRepository.UserRelationRow row : rows) {
            relationCreatedAtMap.put(row.user().id(), row.relationCreatedAt());
            dtos.add(toListItem(row.user(), row.followsYou(), avatarMap));
        }

        return CursorPage.of(dtos, limit, dto -> dto.summary().id(), dto -> relationCreatedAtMap.get(dto.summary().id()));
    }

    private UserListItemDto toListItem(UserEntity user, boolean followsYou, Map<UUID, AvatarRecord> avatarMap) throws SQLException {
        MediaResourceDto avatar = null;
        AvatarRecord record = avatarMap.get(user.id());
        if (record != null) {
            String url = s3StorageService.presignGet(record.objectKey());
            avatar = new MediaResourceDto(
                    record.id(),
                    record.purpose(),
                    record.mimeType(),
                    record.sizeBytes(),
                    record.status(),
                    url,
                    record.createdAt(),
                    record.deletedAt()
            );
        }

        UserSummaryDto summary = new UserSummaryDto(
                user.id(),
                user.username(),
                user.firstName(),
                user.lastName(),
                avatar,
                user.createdAt(),
                userRepository.countFollowers(user.id()),
                userRepository.countFollowing(user.id())
        );
        return new UserListItemDto(summary, followsYou);
    }

    private MediaResourceDto avatarOf(UUID userId) throws SQLException {
        Optional<AvatarRecord> avatarOpt = userRepository.findActiveAvatar(userId, false);
        if (avatarOpt.isEmpty()) {
            return null;
        }
        AvatarRecord record = avatarOpt.get();
        return new MediaResourceDto(
                record.id(),
                record.purpose(),
                record.mimeType(),
                record.sizeBytes(),
                record.status(),
                s3StorageService.presignGet(record.objectKey()),
                record.createdAt(),
                record.deletedAt()
        );
    }

    private UserProfileDto toProfileDto(UserEntity user, UUID callerId) throws SQLException {
        MediaResourceDto avatar = avatarOf(user.id());

        int followersCount = userRepository.countFollowers(user.id());
        int followingCount = userRepository.countFollowing(user.id());

        boolean isFollowing = callerId != null && !callerId.equals(user.id()) && userRepository.isFollowing(callerId, user.id());
        boolean isBlocked = callerId != null && !callerId.equals(user.id()) && userRepository.isBlocked(callerId, user.id());
        boolean followsYou = callerId != null && !callerId.equals(user.id()) && userRepository.isFollowing(user.id(), callerId);

        UserInterestsDto picks = interestRepository.findPicks(user.id());

        return new UserProfileDto(
                user.id(),
                user.username(),
                user.firstName(),
                user.lastName(),
                avatar,
                followersCount,
                followingCount,
                user.bio(),
                user.createdAt(),
                isFollowing,
                isBlocked,
                followsYou,
                picks.interests(),
                picks.kinds()
        );
    }

    static String toUsername(String candidate, UUID callerId) {
        String normalized = candidate == null ? "" : candidate.trim().toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._]", "_");
        if (normalized.length() > MAX_USERNAME_LENGTH) {
            normalized = normalized.substring(0, MAX_USERNAME_LENGTH);
        }
        if (normalized.replace("_", "").isEmpty()) {
            return "user_" + callerId.toString().replace("-", "").substring(0, 12);
        }
        return normalized;
    }
}
