-- A post's links, shown under its description: up to 3 of {"url", "label"}, the label optional.
ALTER TABLE posts ADD COLUMN links JSONB NOT NULL DEFAULT '[]'::jsonb
    CHECK (jsonb_typeof(links) = 'array' AND jsonb_array_length(links) <= 3);

-- event_url is the page the scraper imported a post from, never shown. One a user typed was meant to be shown, so it
-- becomes their post's link.
UPDATE posts
SET links = jsonb_build_array(jsonb_build_object('url', event_url)), event_url = NULL
WHERE event_url IS NOT NULL AND event_url !~* '^https?://(www\.)?trojmiasto\.pl/';

-- The scraper skips an event when one of its posts already has its page. The index also stops a run that retries a
-- post after a timeout, or two runs at once. An earlier duplicate keeps its post, and only the oldest keeps the page.
UPDATE posts p
SET event_url = NULL
WHERE event_url IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM posts older
      WHERE older.author_id = p.author_id AND older.event_url = p.event_url
        AND (older.created_at, older.id) < (p.created_at, p.id)
  );

CREATE UNIQUE INDEX posts_author_event_url_key ON posts (author_id, event_url);
