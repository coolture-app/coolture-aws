package com.coolture.repository;

import com.coolture.common.db.EventTime;
import com.coolture.common.db.Friends;
import com.coolture.common.db.ParamBinder;
import com.coolture.common.dto.PostFeedFilters;

import java.sql.SQLException;
import java.util.UUID;

/**
 * The filters a person sets: search, author, status, people I follow, type, dates and a radius. /posts, /posts/map
 * and /feed all narrow their posts with exactly this, so a filter means the same everywhere. Needs `p` and `el`
 * (posts and event_locations) in the query.
 */
public final class PostFilterSql {

    private PostFilterSql() {}

    /** A point from a longitude and a latitude, in that order. */
    public static final String POINT = "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography";

    /**
     * Bind with {@link #bind}. Search reads the place too, so "Sopot" finds events held there. The newline between the
     * fields keeps a search from matching across two of them.
     */
    public static final String MATCHES = """
        (?::varchar IS NULL OR concat_ws(chr(10), p.title, p.description, el.city, el.venue_name) ILIKE '%' || ?::varchar || '%')
          AND (?::uuid IS NULL OR p.author_id = ?::uuid)
          AND (?::varchar IS NULL OR p.status = ?::varchar)
          AND (?::boolean IS NOT TRUE
               OR EXISTS (SELECT 1 FROM user_relations fw WHERE fw.source_user_id = ?::uuid AND fw.target_user_id = p.author_id AND fw.type = 'FOLLOW')
               OR EXISTS (SELECT 1 FROM post_participations fs WHERE fs.post_id = p.id AND fs.type IN ('INTERESTED', 'TAKES_PART')
                          AND """ + Friends.sharingFriend("fs.user_id") + """
        ))
          AND (?::varchar IS NULL OR p.type = ?::varchar)
          AND """ + EventTime.OVERLAPS + """

          AND (?::double precision IS NULL
               OR (el.coordinates IS NOT NULL AND ST_DWithin(el.coordinates, """ + POINT + """
        , ?::double precision)))""";

    public static void bind(ParamBinder b, PostFeedFilters f, UUID viewer) throws SQLException {
        b.bindStringFilterPair(blankToNull(f.q()));

        b.bindUUIDFilterPair(f.authorId());
        b.bindStringFilterPair(f.status() != null ? f.status().name() : null);

        // A guest follows nobody, so with followed on they get nothing.
        b.bindBoolean(Boolean.TRUE.equals(f.followed()))
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer)
         .bindNullableUUID(viewer);

        b.bindStringFilterPair(f.type() != null ? f.type().name() : null);
        EventTime.bindOverlap(b, f.from(), f.to());

        boolean radius = hasRadius(f);
        Double meters = radius ? f.radiusKm() * 1000.0 : null;
        b.bindDouble(meters)
         .bindDouble(radius ? f.longitude() : null)
         .bindDouble(radius ? f.latitude() : null)
         .bindDouble(meters);
    }

    /** A radius counts only with a full position. The handler rejects half a position. */
    public static boolean hasRadius(PostFeedFilters f) {
        return f.radiusKm() != null && f.latitude() != null && f.longitude() != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
