package com.coolture.repository;

import com.coolture.common.db.Blocks;
import com.coolture.common.db.EventTime;
import com.coolture.common.db.Friends;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.db.StanceCounts;
import com.coolture.common.dto.PostCardDto;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * SQL fragments shared by /posts, /posts/map and /feed, and what every list of cards adds after its query. Each ? must
 * be bound in the order it appears here.
 */
public final class PostSql {

    private PostSql() {}

    /** Binds whether to show past events too. */
    public static final String NOT_ENDED_UNLESS_PAST = "(?::boolean OR " + EventTime.NOT_ENDED + ")";

    /** Bind with Blocks.bindViewer. */
    public static final String NOT_BLOCKED = Blocks.notWithViewer("p.author_id");

    /** What "new for you" means, on the feed and on the map's excludeMine. Bind with bindNotMine. */
    public static final String NOT_MINE = """
        p.author_id IS DISTINCT FROM ?::uuid
          AND NOT EXISTS (SELECT 1 FROM post_reactions r WHERE r.post_id = p.id AND r.user_id = ?::uuid)
          AND NOT EXISTS (SELECT 1 FROM post_participations r WHERE r.post_id = p.id AND r.user_id = ?::uuid)""";

    public static void bindNotMine(ParamBinder b, UUID viewer) throws SQLException {
        b.bindNullableUUID(viewer)
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer);
    }

    /**
     * What popularity counts, for the feed's score and Popularne's order: likes, Interesuje mnie and Biorę udział, from
     * the stored counters, which leave "Nie dla mnie" out. Comments don't count: most commenters have already reacted, and
     * counting them again made the ranking worse.
     */
    public static final String ENGAGEMENT = "(COALESCE(p.positive_reaction_count, 0) + COALESCE(p.participant_count, 0))";

    /** Everything ResultSetMappers.mapPostCard reads. */
    private static final String CARD_COLUMNS = """
        p.id, p.title, p.description, p.event_url, p.links, p.type, p.status,
               p.positive_reaction_count, p.comments_count,
               stances.participant_count, stances.interested_count, stances.going_count,
               p.starts_at, p.ends_at, p.open_ended, p.created_at, p.last_modified_at, p.deleted_at,
               u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
               avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose, avatar_m.mime_type AS avatar_mime_type,
               avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
               avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at, avatar_m.object_key AS avatar_object_key,
               el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
               el.street, el.postal_code, el.city,
               ST_Y(el.coordinates::geometry) AS latitude,
               ST_X(el.coordinates::geometry) AS longitude,
               pr_my.type AS my_reaction,
               pp_my.type AS my_participation""";

    /** The card's columns and any more, up to the WHERE. Bind with bindCardViewer. */
    public static String cardSelect(String moreColumns) {
        return "SELECT " + CARD_COLUMNS + moreColumns + "\n" + """
        FROM posts p
        JOIN users u ON u.id = p.author_id
        """ + StanceCounts.JOIN + """
        LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
        LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
        LEFT JOIN event_locations el ON el.id = p.event_location_id
        LEFT JOIN post_reactions pr_my ON pr_my.post_id = p.id AND pr_my.user_id = ?::uuid
        LEFT JOIN post_participations pp_my ON pp_my.post_id = p.id AND pp_my.user_id = ?::uuid
        """;
    }

    public static final String CARD_SELECT = cardSelect("");

    /** The viewer's own reaction and stance on each card. */
    public static void bindCardViewer(ParamBinder b, UUID viewer) throws SQLException {
        b.bindNullableUUID(viewer)
         .bindNullableUUID(viewer);
    }

    public static List<PostCardDto> withPhotosAndFriends(Connection conn, UUID viewer, List<PostCardDto> cards)
            throws SQLException {
        return Friends.withFriendStances(conn, viewer, MediaQueries.withPhotos(conn, cards));
    }
}
