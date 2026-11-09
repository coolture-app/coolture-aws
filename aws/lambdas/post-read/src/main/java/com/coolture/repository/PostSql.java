package com.coolture.repository;

import com.coolture.common.db.ParamBinder;
import com.coolture.feed.FeedConfig;

import java.sql.SQLException;
import java.util.UUID;

/**
 * SQL fragments shared by /posts, /posts/map and /feed. Each ? must be bound in the order it appears here.
 */
public final class PostSql {

    private PostSql() {}

    /** When an event has ended. Binds nothing. */
    public static final String EVENT_END =
        "COALESCE(p.ends_at, p.starts_at + interval '" + FeedConfig.DEFAULT_EVENT_HOURS + " hours')";

    /** Binds nothing. */
    public static final String NOT_ENDED = EVENT_END + " >= now()";

    /** Binds whether to show every past event. */
    public static final String NOT_LONG_ENDED =
        "(?::boolean OR " + EVENT_END + " >= now() - interval '" + FeedConfig.PAST_WINDOW_HOURS + " hours')";

    /** Binds the viewer twice. */
    public static final String VISIBLE = """
        (p.visibility = 'PUBLIC'
             OR p.author_id = ?::uuid
             OR (p.visibility = 'FRIENDS' AND EXISTS (SELECT 1 FROM user_relations ur WHERE ur.source_user_id = ?::uuid AND ur.target_user_id = p.author_id AND ur.type = 'FOLLOW')))""";

    /** Binds the viewer twice. Holds for a guest, who has no blocks. */
    public static final String NOT_BLOCKED = """
        NOT EXISTS (SELECT 1 FROM user_relations b
                    WHERE b.type = 'BLOCK'
                      AND ((b.source_user_id = ?::uuid AND b.target_user_id = p.author_id)
                        OR (b.source_user_id = p.author_id AND b.target_user_id = ?::uuid)))""";

    /** Not the viewer's own, and not reacted to or taken part in: what makes the feed "new for you". Bind with bindNotMine. */
    public static final String NOT_MINE = """
        p.author_id IS DISTINCT FROM ?::uuid
          AND NOT EXISTS (SELECT 1 FROM post_reactions r WHERE r.post_id = p.id AND r.user_id = ?::uuid)
          AND NOT EXISTS (SELECT 1 FROM post_participations r WHERE r.post_id = p.id AND r.user_id = ?::uuid)""";

    public static void bindNotMine(ParamBinder b, UUID viewer) throws SQLException {
        b.bindNullableUUID(viewer)
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer);
    }

    /** Everything ResultSetMappers.mapPostCard reads, up to the WHERE. Binds the viewer twice. */
    public static final String CARD_SELECT = """
        SELECT p.id, p.title, p.description, p.event_url, p.tags, p.type, p.status, p.visibility,
               p.positive_reaction_count, p.negative_reaction_count, p.participant_count, p.comments_count,
               p.starts_at, p.ends_at, p.created_at, p.last_modified_at, p.deleted_at,
               u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
               avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose, avatar_m.mime_type AS avatar_mime_type,
               avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
               avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at, avatar_m.object_key AS avatar_object_key,
               el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
               el.street, el.postal_code, el.city,
               ST_Y(el.coordinates::geometry) AS latitude,
               ST_X(el.coordinates::geometry) AS longitude,
               cover_m.id AS cover_id, cover_m.purpose AS cover_purpose, cover_m.mime_type AS cover_mime_type,
               cover_m.size_bytes AS cover_size_bytes, cover_m.status AS cover_status,
               cover_m.created_at AS cover_created_at, cover_m.deleted_at AS cover_deleted_at, cover_m.object_key AS cover_object_key,
               cover_m.width AS cover_width, cover_m.height AS cover_height,
               pr_my.type AS my_reaction,
               pp_my.type AS my_participation
        FROM posts p
        JOIN users u ON u.id = p.author_id
        LEFT JOIN profile_images pi ON pi.user_id = u.id AND pi.is_active = true
        LEFT JOIN media avatar_m ON avatar_m.id = pi.thumbnail_media_id
        LEFT JOIN event_locations el ON el.id = p.event_location_id
        LEFT JOIN LATERAL (
            SELECT m.id, m.object_key, m.purpose, m.mime_type, m.size_bytes, m.status, m.created_at, m.deleted_at,
                   m.width, m.height
            FROM post_media pm
            JOIN media m ON m.id = pm.media_id
            WHERE pm.post_id = p.id
            ORDER BY pm.position ASC
            LIMIT 1
        ) cover_m ON true
        LEFT JOIN post_reactions pr_my ON pr_my.post_id = p.id AND pr_my.user_id = ?::uuid
        LEFT JOIN post_participations pp_my ON pp_my.post_id = p.id AND pp_my.user_id = ?::uuid
        """;
}
