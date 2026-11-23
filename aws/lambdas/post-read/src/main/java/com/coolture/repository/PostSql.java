package com.coolture.repository;

import com.coolture.common.db.Blocks;
import com.coolture.common.db.EventTime;
import com.coolture.common.db.Friends;
import com.coolture.common.db.MediaQueries;
import com.coolture.common.db.ParamBinder;
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
     * the stored counters, which leave "Nie dla mnie" out. Comments don't count: most commenters have already reacted,
     * and counting them again made the ranking worse.
     */
    public static final String ENGAGEMENT =
        "(COALESCE(p.positive_reaction_count, 0) + COALESCE(p.participant_count, 0))";

    public static List<PostCardDto> withPhotosAndFriends(Connection conn, UUID viewer, List<PostCardDto> cards)
            throws SQLException {
        return Friends.withFriendStances(conn, viewer, MediaQueries.withPhotos(conn, cards));
    }
}
