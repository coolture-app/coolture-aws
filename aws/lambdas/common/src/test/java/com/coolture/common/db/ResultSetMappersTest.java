package com.coolture.common.db;

import com.coolture.common.dto.EventLocationDto;
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
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResultSetMappersTest {

    @Test
    void mapInstant_whenTimestampPresent_returnsInstant() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Instant now = Instant.now();
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));

        Instant result = ResultSetMappers.mapInstant(rs, "created_at");

        assertEquals(now, result);
    }

    @Test
    void mapInstant_whenTimestampNull_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp("created_at")).thenReturn(null);

        Instant result = ResultSetMappers.mapInstant(rs, "created_at");

        assertNull(result);
    }

    @Test
    void mapEnum_whenValidString_returnsEnumValue() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("type")).thenReturn("ONLINE");

        PostType result = ResultSetMappers.mapEnum(rs, "type", PostType.class);

        assertEquals(PostType.ONLINE, result);
    }

    @Test
    void mapEnum_whenNull_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("type")).thenReturn(null);

        PostType result = ResultSetMappers.mapEnum(rs, "type", PostType.class);

        assertNull(result);
    }

    @Test
    void mapTags_whenArrayPresent_returnsList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        Array sqlArray = mock(Array.class);
        String[] tags = new String[]{"music", "festival"};
        when(sqlArray.getArray()).thenReturn(tags);
        when(rs.getArray("tags")).thenReturn(sqlArray);

        List<String> result = ResultSetMappers.mapTags(rs);

        assertEquals(List.of("music", "festival"), result);
    }

    @Test
    void mapTags_whenArrayNull_returnsEmptyList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getArray("tags")).thenReturn(null);

        List<String> result = ResultSetMappers.mapTags(rs);

        assertTrue(result.isEmpty());
    }

    @Test
    void mapUserSummary_withAvatar_mapsCorrectly() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID authorId = UUID.randomUUID();
        UUID avatarId = UUID.randomUUID();
        Instant now = Instant.now();

        when(rs.getObject("author_id")).thenReturn(authorId);
        when(rs.getString("username")).thenReturn("john_doe");
        when(rs.getString("first_name")).thenReturn("John");
        when(rs.getString("last_name")).thenReturn("Doe");
        when(rs.getTimestamp("user_created_at")).thenReturn(Timestamp.from(now));

        when(rs.getObject("avatar_id")).thenReturn(avatarId);
        when(rs.getString("avatar_purpose")).thenReturn("AVATAR");
        when(rs.getString("avatar_mime_type")).thenReturn("image/png");
        when(rs.getObject("avatar_size_bytes")).thenReturn(1024L);
        when(rs.getLong("avatar_size_bytes")).thenReturn(1024L);
        when(rs.getString("avatar_status")).thenReturn("ACTIVE");
        when(rs.getTimestamp("avatar_created_at")).thenReturn(Timestamp.from(now));

        UserSummaryDto user = ResultSetMappers.mapUserSummary(rs);

        assertNotNull(user);
        assertEquals(authorId, user.id());
        assertEquals("john_doe", user.username());
        assertEquals("John", user.firstName());
        assertEquals("Doe", user.lastName());
        assertEquals(now, user.createdAt());

        assertNotNull(user.avatar());
        assertEquals(avatarId, user.avatar().id());
        assertEquals(MediaPurpose.AVATAR, user.avatar().purpose());
        assertEquals("image/png", user.avatar().mimeType());
        assertEquals(1024L, user.avatar().sizeBytes());
        assertEquals(MediaStatus.ACTIVE, user.avatar().status());
    }

    @Test
    void mapUserSummary_withoutAvatar_mapsNullAvatar() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID authorId = UUID.randomUUID();

        when(rs.getObject("author_id")).thenReturn(authorId);
        when(rs.getString("username")).thenReturn("jane");
        when(rs.getObject("avatar_id")).thenReturn(null);

        UserSummaryDto user = ResultSetMappers.mapUserSummary(rs);

        assertNotNull(user);
        assertEquals(authorId, user.id());
        assertNull(user.avatar());
    }

    @Test
    void mapEventLocation_whenPresent_mapsFields() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID locId = UUID.randomUUID();

        when(rs.getObject("loc_id")).thenReturn(locId);
        when(rs.getString("country_code")).thenReturn("POL");
        when(rs.getString("venue_name")).thenReturn("Tauron Arena");
        when(rs.getString("building_num")).thenReturn("7");
        when(rs.getString("street")).thenReturn("Lema");
        when(rs.getString("postal_code")).thenReturn("31-571");
        when(rs.getString("city")).thenReturn("Kraków");
        when(rs.getDouble("latitude")).thenReturn(50.068);
        when(rs.getDouble("longitude")).thenReturn(19.988);

        EventLocationDto loc = ResultSetMappers.mapEventLocation(rs);

        assertNotNull(loc);
        assertEquals(locId, loc.id());
        assertEquals("POL", loc.countryCode());
        assertEquals("Tauron Arena", loc.venueName());
        assertEquals("Kraków", loc.city());
        assertEquals(50.068, loc.coordinates().latitude());
        assertEquals(19.988, loc.coordinates().longitude());
    }

    @Test
    void mapEventLocation_whenNull_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("loc_id")).thenReturn(null);

        EventLocationDto loc = ResultSetMappers.mapEventLocation(rs);

        assertNull(loc);
    }

    @Test
    void mapCoverMedia_whenPresent_mapsFields() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID coverId = UUID.randomUUID();
        when(rs.getObject("cover_id")).thenReturn(coverId);
        when(rs.getString("cover_purpose")).thenReturn("POST_MEDIA");
        when(rs.getString("cover_mime_type")).thenReturn("image/jpeg");
        when(rs.getObject("cover_size_bytes")).thenReturn(2048L);
        when(rs.getLong("cover_size_bytes")).thenReturn(2048L);
        when(rs.getString("cover_status")).thenReturn("ACTIVE");

        MediaResourceDto cover = ResultSetMappers.mapCoverMedia(rs);

        assertNotNull(cover);
        assertEquals(coverId, cover.id());
        assertEquals(MediaPurpose.POST_MEDIA, cover.purpose());
        assertEquals("image/jpeg", cover.mimeType());
    }

    @Test
    void mapPostMedia_mapsCorrectly() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID mediaId = UUID.randomUUID();

        when(rs.getObject("media_id")).thenReturn(mediaId);
        when(rs.getString("purpose")).thenReturn("POST_MEDIA");
        when(rs.getInt("position")).thenReturn(2);
        when(rs.getBoolean("is_cover")).thenReturn(true);

        PostMediaDto pm = ResultSetMappers.mapPostMedia(rs);

        assertNotNull(pm);
        assertEquals(mediaId, pm.media().id());
        assertEquals(2, pm.position());
        assertTrue(pm.isCover());
    }

    @Test
    void mapPostCard_mapsFullObject() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        UUID postId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        Instant now = Instant.now();

        when(rs.getObject("id")).thenReturn(postId);
        when(rs.getObject("author_id")).thenReturn(authorId);
        when(rs.getString("username")).thenReturn("cool_user");
        when(rs.getObject("avatar_id")).thenReturn(null);
        when(rs.getObject("loc_id")).thenReturn(null);
        when(rs.getString("title")).thenReturn("Concert");
        when(rs.getString("description")).thenReturn("Amazing concert");
        when(rs.getString("event_url")).thenReturn("https://example.com");
        when(rs.getTimestamp("starts_at")).thenReturn(Timestamp.from(now));
        when(rs.getTimestamp("ends_at")).thenReturn(Timestamp.from(now.plusSeconds(3600)));
        when(rs.getArray("tags")).thenReturn(null);
        when(rs.getInt("positive_reaction_count")).thenReturn(42);
        when(rs.getInt("negative_reaction_count")).thenReturn(1);
        when(rs.getInt("participant_count")).thenReturn(10);
        when(rs.getInt("comments_count")).thenReturn(5);
        when(rs.getString("type")).thenReturn("ONLINE");
        when(rs.getString("status")).thenReturn("ACTIVE");
        when(rs.getString("visibility")).thenReturn("PUBLIC");
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(rs.getObject("cover_id")).thenReturn(null);
        when(rs.getString("my_reaction")).thenReturn("LIKE");
        when(rs.getString("my_participation")).thenReturn("TAKES_PART");

        PostCardDto card = ResultSetMappers.mapPostCard(rs);

        assertNotNull(card);
        assertEquals(postId, card.id());
        assertEquals("Concert", card.title());
        assertEquals(42, card.positiveReactionCount());
        assertEquals(PostType.ONLINE, card.type());
        assertEquals(PostStatus.ACTIVE, card.status());
        assertEquals(PostVisibility.PUBLIC, card.visibility());
        assertEquals(ReactionType.LIKE, card.myReaction());
        assertEquals(ParticipationType.TAKES_PART, card.myParticipation());
    }
}
