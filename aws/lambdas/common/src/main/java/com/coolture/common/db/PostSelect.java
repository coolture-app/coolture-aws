package com.coolture.common.db;

import java.sql.SQLException;
import java.util.UUID;

/** A post as cards and its page show it, up to the WHERE. Bind with bindViewer first. */
public final class PostSelect {

    private PostSelect() {}

    /** Everything ResultSetMappers.mapPostCard reads. */
    private static final String CARD_COLUMNS = """
        p.id, p.title, p.description, p.event_url, p.links, p.type, p.status,
               p.positive_reaction_count, p.comments_count,
               stances.participant_count, stances.interested_count, stances.going_count,
               p.starts_at, p.ends_at, p.open_ended, p.created_at, p.last_modified_at, p.deleted_at,
               u.id AS author_id, u.username, u.first_name, u.last_name, u.created_at AS user_created_at,
               avatar_m.id AS avatar_id, avatar_m.purpose AS avatar_purpose, avatar_m.mime_type AS avatar_mime_type,
               avatar_m.size_bytes AS avatar_size_bytes, avatar_m.status AS avatar_status,
               avatar_m.created_at AS avatar_created_at, avatar_m.deleted_at AS avatar_deleted_at,
               avatar_m.object_key AS avatar_object_key,
               el.id AS loc_id, el.country_code, el.venue_name, el.building_num,
               el.street, el.postal_code, el.city,
               ST_Y(el.coordinates::geometry) AS latitude,
               ST_X(el.coordinates::geometry) AS longitude,
               pr_my.type AS my_reaction,
               pp_my.type AS my_participation""";

    private static String select(String moreColumns) {
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

    public static final String CARD = select("");

    /** Everything ResultSetMappers.mapPostDetail reads. */
    public static final String DETAIL = select(",\n       " + PostKinds.OF_POST + " AS kinds");

    /** The viewer's own reaction and stance. */
    public static void bindViewer(ParamBinder b, UUID viewer) throws SQLException {
        b.bindNullableUUID(viewer)
         .bindNullableUUID(viewer);
    }
}
