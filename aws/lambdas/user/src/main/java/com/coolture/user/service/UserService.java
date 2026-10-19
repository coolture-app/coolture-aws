package com.coolture.user.service;

import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.UserProfileDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.pagination.CursorCodec;
import com.coolture.common.pagination.CursorPage;
import com.coolture.common.pagination.CursorPayload;
import com.coolture.common.storage.S3StorageService;
import com.coolture.user.dto.CreateUserRequest;
import com.coolture.user.dto.ProfileImageResponse;
import com.coolture.user.dto.SetProfileImageRequest;
import com.coolture.user.dto.UserProfileUpdateRequest;
import com.coolture.user.repository.UserRepository;
import com.coolture.user.repository.UserRepository.AvatarRecord;
import com.coolture.user.repository.UserRepository.UserEntity;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public class UserService {

    private final UserRepository userRepository;
    private final S3StorageService s3StorageService;

    public UserService() {
        this(new UserRepository(), new S3StorageService());
    }

    public UserService(UserRepository userRepository, S3StorageService s3StorageService) {
        this.userRepository = userRepository;
        this.s3StorageService = s3StorageService;
    }

    public CursorPage<UserSummaryDto> search(UUID callerId, String q, String cursor, int limit) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }

        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        List<UserEntity> users = userRepository.search(q, cursorCreatedAt, cursorId, limit + 1);

        List<UUID> userIds = users.stream().map(UserEntity::id).toList();
        Map<UUID, AvatarRecord> avatarMap = userRepository.findActiveThumbnails(userIds);

        List<UserSummaryDto> dtos = new ArrayList<>();
        for (UserEntity user : users) {
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

            int followersCount = userRepository.countFollowers(user.id());
            int followingCount = userRepository.countFollowing(user.id());

            dtos.add(new UserSummaryDto(
                    user.id(),
                    user.username(),
                    user.firstName(),
                    user.lastName(),
                    avatar,
                    user.createdAt(),
                    followersCount,
                    followingCount
            ));
        }

        return CursorPage.of(dtos, limit, UserSummaryDto::id, UserSummaryDto::createdAt);
    }

    public UserProfileDto getByIdForCaller(UUID targetId, UUID callerId) throws SQLException {
        UserEntity user = userRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("User " + targetId + " not found"));

        return toProfileDto(user, callerId);
    }

    public UserProfileDto getByUsernameForCaller(String username, UUID callerId) throws SQLException {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User " + username + " not found"));

        return toProfileDto(user, callerId);
    }

    public UserProfileDto update(UUID targetId, UUID callerId, UserProfileUpdateRequest request) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (!targetId.equals(callerId)) {
            throw new SecurityException("You can only update your own profile");
        }
        if (request.username() != null && request.username().length() > 32) {
            throw new IllegalArgumentException("Username must not exceed 32 characters");
        }

        UserEntity user = userRepository.updateUser(
                targetId,
                request.username(),
                request.firstName(),
                request.lastName(),
                request.bio()
        );

        return toProfileDto(user, callerId);
    }

    public UserEntity createUser(CreateUserRequest request) throws SQLException {
        return userRepository.createUser(
                null,
                request.username(),
                request.firstName(),
                request.lastName(),
                request.bio()
        );
    }

    public ProfileImageResponse setProfileImage(UUID targetId, UUID callerId, SetProfileImageRequest request) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (!targetId.equals(callerId)) {
            throw new SecurityException("You can only manage your own profile image");
        }

        userRepository.validateMediaOwnedAndNotDeleted(request.fullMediaId(), targetId);
        userRepository.validateMediaOwnedAndNotDeleted(request.thumbnailMediaId(), targetId);

        Instant now = Instant.now();
        userRepository.deactivateProfileImages(targetId, now);
        userRepository.insertProfileImage(targetId, request.fullMediaId(), request.thumbnailMediaId(), now);

        return new ProfileImageResponse(request.fullMediaId(), request.thumbnailMediaId(), now);
    }

    public void removeProfileImage(UUID targetId, UUID callerId) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        if (!targetId.equals(callerId)) {
            throw new SecurityException("You can only manage your own profile image");
        }

        userRepository.deactivateProfileImages(targetId, Instant.now());
    }

    public UserProfileDto getMe(UUID callerId, String username, String firstName, String lastName) throws SQLException {
        if (callerId == null) {
            throw new SecurityException("User not authenticated: missing 'sub' claim");
        }
        Optional<UserEntity> userOpt = userRepository.findById(callerId);
        if (userOpt.isEmpty()) {
            String uname = (username != null && !username.isBlank()) ? username : callerId.toString();
            String fName = firstName != null ? firstName : "";
            String lName = lastName != null ? lastName : "";
            userRepository.createUser(callerId, uname, fName, lName, null);
        }
        return getByIdForCaller(callerId, callerId);
    }

    public CursorPage<UserSummaryDto> getFollowers(UUID targetUserId, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        List<UserRepository.UserRelationRow> rows = userRepository.findFollowers(targetUserId, cursorCreatedAt, cursorId, limit + 1);
        return toRelationPage(rows, limit);
    }

    public CursorPage<UserSummaryDto> getFollowing(UUID targetUserId, String cursor, int limit) throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Limit must be between 1 and 100");
        }
        if (!userRepository.existsById(targetUserId)) {
            throw new IllegalArgumentException("User " + targetUserId + " not found");
        }
        Optional<CursorPayload> payload = CursorCodec.decode(cursor);
        Instant cursorCreatedAt = payload.map(CursorPayload::createdAt).orElse(null);
        UUID cursorId = payload.map(CursorPayload::id).orElse(null);

        List<UserRepository.UserRelationRow> rows = userRepository.findFollowing(targetUserId, cursorCreatedAt, cursorId, limit + 1);
        return toRelationPage(rows, limit);
    }

    public CursorPage<UserSummaryDto> getBlocking(UUID targetUserId, UUID callerId, String cursor, int limit) throws SQLException {
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

        List<UserRepository.UserRelationRow> rows = userRepository.findBlocking(targetUserId, cursorCreatedAt, cursorId, limit + 1);
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
        userRepository.deleteRelationAny(callerId, targetUserId);
        userRepository.deleteRelationAny(targetUserId, callerId);
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

    private CursorPage<UserSummaryDto> toRelationPage(List<UserRepository.UserRelationRow> rows, int limit) throws SQLException {
        List<UUID> userIds = rows.stream().map(r -> r.user().id()).toList();
        Map<UUID, AvatarRecord> avatarMap = userRepository.findActiveThumbnails(userIds);

        Map<UUID, Instant> relationCreatedAtMap = new HashMap<>();
        List<UserSummaryDto> dtos = new ArrayList<>();

        for (UserRepository.UserRelationRow row : rows) {
            UserEntity user = row.user();
            relationCreatedAtMap.put(user.id(), row.relationCreatedAt());

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

            int followersCount = userRepository.countFollowers(user.id());
            int followingCount = userRepository.countFollowing(user.id());

            dtos.add(new UserSummaryDto(
                    user.id(),
                    user.username(),
                    user.firstName(),
                    user.lastName(),
                    avatar,
                    user.createdAt(),
                    followersCount,
                    followingCount
            ));
        }

        return CursorPage.of(dtos, limit, UserSummaryDto::id, dto -> relationCreatedAtMap.get(dto.id()));
    }

    private UserProfileDto toProfileDto(UserEntity user, UUID callerId) throws SQLException {
        MediaResourceDto avatar = null;
        Optional<AvatarRecord> avatarOpt = userRepository.findActiveAvatar(user.id(), false);
        if (avatarOpt.isPresent()) {
            AvatarRecord record = avatarOpt.get();
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

        int followersCount = userRepository.countFollowers(user.id());
        int followingCount = userRepository.countFollowing(user.id());

        boolean isFollowing = callerId != null && !callerId.equals(user.id()) && userRepository.isFollowing(callerId, user.id());
        boolean isBlocked = callerId != null && !callerId.equals(user.id()) && userRepository.isBlocked(callerId, user.id());

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
                isBlocked
        );
    }
}
