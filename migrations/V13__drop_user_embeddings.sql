-- The feed builds the viewer's vector itself, from their stances and interests up to the session's start, so nothing stores
-- or reads user vectors any more.

DROP TABLE user_embeddings;
