package com.coolture.repository;

import com.coolture.common.db.ParamBinder;
import com.coolture.common.dto.PostFeedFilters;
import com.coolture.common.dto.enums.PostVisibility;

import java.sql.SQLException;
import java.util.UUID;

/**
 * The filters a person sets: search, tags, author, status, visibility, type, dates and a radius. /posts, /posts/map
 * and /feed all narrow their posts with exactly this, so a filter means the same everywhere. Needs `p` and `el`
 * (posts and event_locations) in the query.
 */
public final class PostFilterSql {

    private PostFilterSql() {}

    /** A point from a longitude and a latitude, in that order. */
    public static final String POINT = "ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography";

    /** Bind with {@link #bind}. */
    public static final String MATCHES = """
        (?::varchar IS NULL OR LOWER(p.title) LIKE LOWER('%' || ?::varchar || '%') OR LOWER(p.description) LIKE LOWER('%' || ?::varchar || '%'))
          AND (?::varchar[] IS NULL OR p.tags && ?::varchar[])
          AND (?::uuid IS NULL OR p.author_id = ?::uuid)
          AND (?::varchar IS NULL OR p.status = ?::varchar)
          AND (?::varchar IS NULL OR p.visibility = ?::varchar)
          AND (?::varchar IS NULL OR p.type = ?::varchar)
          AND (?::timestamptz IS NULL OR p.starts_at >= ?::timestamptz)
          AND (?::timestamptz IS NULL OR p.starts_at <= ?::timestamptz)
          AND (?::double precision IS NULL
               OR (el.coordinates IS NOT NULL AND ST_DWithin(el.coordinates, """ + POINT + """
        , ?::double precision)))""";

    public static void bind(ParamBinder b, PostFeedFilters f, UUID viewer) throws SQLException {
        b.bindStringFilterTriple(blankToNull(f.q()));

        String[] tags = f.tags() != null && !f.tags().isEmpty() ? f.tags().toArray(new String[0]) : null;
        b.bindVarcharArrayFilterPair(tags);

        b.bindUUIDFilterPair(f.authorId());
        b.bindStringFilterPair(f.status() != null ? f.status().name() : null);

        // A guest sees public posts only. VISIBLE says so too; this keeps the plan simple for guests.
        String visibility = f.visibility() != null ? f.visibility().name() : (viewer == null ? PostVisibility.PUBLIC.name() : null);
        b.bindStringFilterPair(visibility);

        b.bindStringFilterPair(f.type() != null ? f.type().name() : null);
        b.bindTimestampFilterPair(f.startsFrom());
        b.bindTimestampFilterPair(f.startsTo());

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
