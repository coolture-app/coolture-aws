-- V4: Add pgvector extension and embedding tables

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE post_embeddings (
    post_id UUID PRIMARY KEY REFERENCES posts(id) ON DELETE CASCADE,
    embedding vector(768) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_post_embeddings_hnsw ON post_embeddings
    USING hnsw (embedding vector_cosine_ops);

CREATE TABLE user_embeddings (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    embedding vector(768) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_user_embeddings_hnsw ON user_embeddings
    USING hnsw (embedding vector_cosine_ops);
