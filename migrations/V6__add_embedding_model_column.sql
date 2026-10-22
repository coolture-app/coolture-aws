ALTER TABLE post_embeddings ADD COLUMN model VARCHAR(128) NOT NULL DEFAULT 'all-mpnet-base-v2';
ALTER TABLE post_embeddings ALTER COLUMN model DROP DEFAULT;

ALTER TABLE user_embeddings ADD COLUMN model VARCHAR(128) NOT NULL DEFAULT 'all-mpnet-base-v2';
ALTER TABLE user_embeddings ALTER COLUMN model DROP DEFAULT;
