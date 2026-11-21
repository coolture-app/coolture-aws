-- Posts, their photos and places, and stances.

-- Search folds diacritics on both sides ("spiew" finds "Śpiew"). unaccent isn't immutable, so it can't back an index.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- The image's size in pixels, so a post's photo frame can take the photo's shape before it loads.
-- Null for media uploaded before this, and for files that aren't images.
ALTER TABLE media
    ADD COLUMN width INTEGER CHECK (width > 0),
    ADD COLUMN height INTEGER CHECK (height > 0);

-- A post's photos are ordered, and the first one is its cover, so position alone says which it is.

WITH ordered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY is_cover DESC, position ASC, id ASC) - 1 AS new_position
    FROM post_media
)
UPDATE post_media pm
SET position = ordered.new_position
FROM ordered
WHERE pm.id = ordered.id AND pm.position IS DISTINCT FROM ordered.new_position;

ALTER TABLE post_media DROP COLUMN is_cover;

UPDATE media SET purpose = 'EVENT_MEDIA' WHERE purpose = 'EVENT_COVER';

ALTER TABLE media DROP CONSTRAINT media_purpose_check;
ALTER TABLE media ADD CONSTRAINT media_purpose_check
    CHECK (purpose IN ('PROFILE_IMAGE', 'PROFILE_IMAGE_THUMBNAIL', 'EVENT_MEDIA'));

-- Country codes are two letters, as the geocoder gives them, and any code is accepted, so the dictionary goes.

ALTER TABLE event_locations DROP CONSTRAINT event_locations_country_code_fkey;
DROP TABLE country_codes;

UPDATE event_locations SET country_code = CASE country_code
    WHEN 'POL' THEN 'PL' WHEN 'DEU' THEN 'DE' WHEN 'CZE' THEN 'CZ' WHEN 'SVK' THEN 'SK' WHEN 'UKR' THEN 'UA'
    WHEN 'LTU' THEN 'LT' WHEN 'BLR' THEN 'BY' WHEN 'USA' THEN 'US' WHEN 'GBR' THEN 'GB' WHEN 'FRA' THEN 'FR'
    WHEN 'ITA' THEN 'IT' WHEN 'ESP' THEN 'ES' WHEN 'NLD' THEN 'NL'
END
WHERE length(country_code) = 3;

ALTER TABLE event_locations ALTER COLUMN country_code TYPE VARCHAR(2);

-- Rows no post points at: left by posts switched to online, or made through the removed /event-locations routes.
DELETE FROM event_locations el WHERE NOT EXISTS (SELECT 1 FROM posts p WHERE p.event_location_id = el.id);

-- "Nie dla mnie" is a private stance about the viewer's taste, beside interested and going, so a dislike becomes one. A post
-- already saved or attended keeps that stance. Nothing counts these marks publicly.

ALTER TABLE post_participations DROP CONSTRAINT post_participations_type_check;
ALTER TABLE post_participations ADD CONSTRAINT post_participations_type_check
    CHECK (type IN ('INTERESTED', 'TAKES_PART', 'NOT_INTERESTED'));

INSERT INTO post_participations (post_id, user_id, type, created_at)
SELECT post_id, user_id, 'NOT_INTERESTED', created_at
FROM post_reactions
WHERE type = 'DISLIKE'
ON CONFLICT (post_id, user_id) DO NOTHING;

DELETE FROM post_reactions WHERE type = 'DISLIKE';

ALTER TABLE post_reactions DROP CONSTRAINT post_reactions_type_check;
ALTER TABLE post_reactions ADD CONSTRAINT post_reactions_type_check CHECK (type = 'LIKE');

ALTER TABLE posts DROP COLUMN negative_reaction_count;

-- Every post is public. Posts saved as friends-only or private become public with the column.
ALTER TABLE posts DROP COLUMN visibility;

-- A post with no end date ("do odwołania") never ends, so it has no ends_at. An empty ends_at without it still means
-- the default length (EventTime).

ALTER TABLE posts
    ADD COLUMN open_ended BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT posts_open_ended_has_no_end CHECK (NOT open_ended OR ends_at IS NULL);

-- Authors don't label their posts; the kind of event comes from the post's embedding.
ALTER TABLE posts DROP COLUMN tags;

-- The SQL side of EventTime, which holds the rule. STABLE, as timestamptz + interval is: a SQL function no stricter than
-- its body is inlined, so a query plans as with the CASE written out.

CREATE FUNCTION event_end(starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ, open_ended BOOLEAN) RETURNS TIMESTAMPTZ
    LANGUAGE sql STABLE PARALLEL SAFE
    RETURN CASE WHEN open_ended THEN 'infinity'::timestamptz ELSE COALESCE(ends_at, starts_at + interval '3 hours') END;
