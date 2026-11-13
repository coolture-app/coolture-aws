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
