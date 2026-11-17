-- A post with no end date ("do odwołania") never ends, so it has no ends_at. An empty ends_at without it still means
-- the default length (EventTime).

ALTER TABLE posts
    ADD COLUMN open_ended BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT posts_open_ended_has_no_end CHECK (NOT open_ended OR ends_at IS NULL);
