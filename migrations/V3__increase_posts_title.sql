-- V3: Increase posts.title to VARCHAR(128)

ALTER TABLE posts ALTER COLUMN title TYPE VARCHAR(128);
