package com.coolture.common.db;

import com.coolture.common.dto.EventLocationDto;
import com.coolture.common.dto.GeoPointDto;
import com.coolture.common.dto.MediaResourceDto;
import com.coolture.common.dto.PostCardDto;
import com.coolture.common.dto.PostMediaDto;
import com.coolture.common.dto.UserSummaryDto;
import com.coolture.common.dto.enums.MediaPurpose;
import com.coolture.common.dto.enums.MediaStatus;
import com.coolture.common.dto.enums.ParticipationType;
import com.coolture.common.dto.enums.PostStatus;
import com.coolture.common.dto.enums.PostType;
import com.coolture.common.dto.enums.PostVisibility;
import com.coolture.common.dto.enums.ReactionType;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Shared utility methods for mapping JDBC ResultSet rows to common DTOs.
 */
public final class ResultSetMappers {

    private ResultSetMappers() {}

    public static Instant mapInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts != null ? ts.toInstant() : null;
    }

    public static <T extends Enum<T>> T mapEnum(ResultSet rs, String column, Class<T> enumClass) throws SQLException {
        String value = rs.getString(column);
        return value != null ? Enum.valueOf(enumClass, value) : null;
    }

    public static List<String> mapTags(ResultSet rs) throws SQLException {
        Array tagsArray = rs.getArray("tags");
        return tagsArray != null
            ? List.of((String[]) tagsArray.getArray())
            : Collections.emptyList();
    }

    /**
     * Maps a ResultSet row to UserSummaryDto, including avatar if present.
     * Expects user columns: author_id, username, first_name, last_name, user_created_at
     * Expects avatar columns: avatar_id, avatar_purpose, avatar_mime_type, avatar_size_bytes, avatar_status, avatar_created_at, avatar_deleted_at
     */
    public static UserSummaryDto mapUserSummary(ResultSet rs) throws SQLException {
        MediaResourceDto avatar = null;
        UUID avatarId = (UUID) rs.getObject("avatar_id");
        if (avatarId != null) {
            avatar = new MediaResourceDto(
                avatarId,
                mapEnum(rs, "avatar_purpose", MediaPurpose.class),
                rs.getString("avatar_mime_type"),
                rs.getObject("avatar_size_bytes") != null ? rs.getLong("avatar_size_bytes") : null,
                mapEnum(rs, "avatar_status", MediaStatus.class),
                null,
                mapInstant(rs, "avatar_created_at"),
                mapInstant(rs, "avatar_deleted_at")
            );
        }

        return new UserSummaryDto(
            (UUID) rs.getObject("author_id"),
            rs.getString("username"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            avatar,
            mapInstant(rs, "user_created_at"),
            0,
            0
        );
    }

    /**
     * Maps cover media from ResultSet if present.
     * Expects cover columns: cover_id, cover_purpose, cover_mime_type, cover_size_bytes, cover_status, cover_created_at, cover_deleted_at
     */
    public static MediaResourceDto mapCoverMedia(ResultSet rs) throws SQLException {
        UUID coverId = (UUID) rs.getObject("cover_id");
        if (coverId == null) return null;

        return new MediaResourceDto(
            coverId,
            mapEnum(rs, "cover_purpose", MediaPurpose.class),
            rs.getString("cover_mime_type"),
            rs.getObject("cover_size_bytes") != null ? rs.getLong("cover_size_bytes") : null,
            mapEnum(rs, "cover_status", MediaStatus.class),
            null,
            mapInstant(rs, "cover_created_at"),
            mapInstant(rs, "cover_deleted_at")
        );
    }

    /**
     * Maps a ResultSet row to EventLocationDto.
     * Expects columns: loc_id, country_code, venue_name, building_num, street, postal_code, city, latitude, longitude.
     */
    public static EventLocationDto mapEventLocation(ResultSet rs) throws SQLException {
        UUID locId = (UUID) rs.getObject("loc_id");
        if (locId == null) return null;

        return new EventLocationDto(
            locId,
            rs.getString("country_code"),
            rs.getString("venue_name"),
            rs.getString("building_num"),
            rs.getString("street"),
            rs.getString("postal_code"),
            rs.getString("city"),
            new GeoPointDto(
                rs.getDouble("latitude"),
                rs.getDouble("longitude")
            ),
            null
        );
    }

    /**
     * Maps a ResultSet row to MediaResourceDto.
     */
    public static MediaResourceDto mapMediaResource(ResultSet rs, String idColumn) throws SQLException {
        UUID id = (UUID) rs.getObject(idColumn);
        if (id == null) return null;

        return new MediaResourceDto(
            id,
            mapEnum(rs, "purpose", MediaPurpose.class),
            rs.getString("mime_type"),
            rs.getObject("size_bytes") != null ? rs.getLong("size_bytes") : null,
            mapEnum(rs, "status", MediaStatus.class),
            null,
            mapInstant(rs, "created_at"),
            mapInstant(rs, "deleted_at")
        );
    }

    /**
     * Maps a ResultSet row to PostMediaDto.
     */
    public static PostMediaDto mapPostMedia(ResultSet rs) throws SQLException {
        return new PostMediaDto(
            mapMediaResource(rs, "media_id"),
            rs.getInt("position"),
            rs.getBoolean("is_cover")
        );
    }

    public static ReactionType mapMyReaction(ResultSet rs) throws SQLException {
        return mapEnum(rs, "my_reaction", ReactionType.class);
    }

    public static ParticipationType mapMyParticipation(ResultSet rs) throws SQLException {
        return mapEnum(rs, "my_participation", ParticipationType.class);
    }

    /**
     * Maps a full PostCardDto directly from a single ResultSet row.
     */
    public static PostCardDto mapPostCard(ResultSet rs) throws SQLException {
        return new PostCardDto(
            (UUID) rs.getObject("id"),
            mapUserSummary(rs),
            mapEventLocation(rs),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("event_url"),
            mapInstant(rs, "starts_at"),
            mapInstant(rs, "ends_at"),
            mapTags(rs),
            rs.getInt("positive_reaction_count"),
            rs.getInt("negative_reaction_count"),
            rs.getInt("participant_count"),
            rs.getInt("comments_count"),
            mapEnum(rs, "type", PostType.class),
            mapEnum(rs, "status", PostStatus.class),
            mapEnum(rs, "visibility", PostVisibility.class),
            mapInstant(rs, "created_at"),
            mapInstant(rs, "last_modified_at"),
            mapInstant(rs, "deleted_at"),
            mapCoverMedia(rs),
            mapMyReaction(rs),
            mapMyParticipation(rs)
        );
    }
}
