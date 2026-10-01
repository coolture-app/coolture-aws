-- V1: Initial schema

CREATE EXTENSION IF NOT EXISTS "postgis";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================
-- Tables
-- ============================================

CREATE TABLE country_codes (
    code VARCHAR(3) PRIMARY KEY
);

INSERT INTO country_codes (code) VALUES
    ('POL'), ('DEU'), ('CZE'), ('SVK'), ('UKR'), ('LTU'), ('BLR'),
    ('USA'), ('GBR'), ('FRA'), ('ITA'), ('ESP'), ('NLD')
ON CONFLICT (code) DO NOTHING;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(32) UNIQUE NOT NULL,
    first_name VARCHAR(128),
    last_name VARCHAR(128),
    bio VARCHAR(512),
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE event_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code VARCHAR(3) REFERENCES country_codes(code),
    venue_name VARCHAR(64),
    building_num VARCHAR(16),
    street VARCHAR(128),
    postal_code VARCHAR(16),
    city VARCHAR(128),
    coordinates geography(point, 4326) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE media (
    id UUID PRIMARY KEY,
    owner_id UUID REFERENCES users(id),
    object_key TEXT UNIQUE,
    file_name VARCHAR(255),
    purpose VARCHAR(64) CHECK (purpose IN ('PROFILE_IMAGE', 'PROFILE_IMAGE_THUMBNAIL', 'EVENT_COVER', 'EVENT_MEDIA')),
    mime_type VARCHAR(64),
    size_bytes BIGINT,
    status VARCHAR(32) CHECK (status IN ('PENDING', 'UPLOADED', 'ATTACHED', 'DELETED')),
    created_at TIMESTAMPTZ DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE profile_images (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    full_media_id UUID REFERENCES media(id),
    thumbnail_media_id UUID REFERENCES media(id),
    is_active BOOLEAN DEFAULT true,
    set_at TIMESTAMPTZ DEFAULT now(),
    unset_at TIMESTAMPTZ
);

CREATE TABLE posts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id UUID NOT NULL REFERENCES users(id),
    event_location_id UUID REFERENCES event_locations(id),
    title VARCHAR(32) NOT NULL,
    description VARCHAR(1024),
    event_url VARCHAR,
    tags VARCHAR(32)[],
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ,
    type VARCHAR(32) NOT NULL CHECK (type IN ('ONLINE', 'OFFLINE')),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EDITED', 'DELETED')),
    visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC' CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'FRIENDS')),
    positive_reaction_count INTEGER DEFAULT 0 CHECK (positive_reaction_count >= 0),
    negative_reaction_count INTEGER DEFAULT 0 CHECK (negative_reaction_count >= 0),
    participant_count INTEGER DEFAULT 0 CHECK (participant_count >= 0),
    comments_count INTEGER DEFAULT 0 CHECK (comments_count >= 0),
    created_at TIMESTAMPTZ DEFAULT now(),
    last_modified_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,

    CHECK (type = 'ONLINE' AND event_location_id IS NULL OR type = 'OFFLINE' AND event_location_id IS NOT NULL),
    CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE TABLE comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES posts(id),
    author_id UUID NOT NULL REFERENCES users(id),
    root_comment_id UUID REFERENCES comments(id),
    parent_comment_id UUID REFERENCES comments(id),
    ancestor_ids UUID[],
    content VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
    replies_count INTEGER DEFAULT 0 CHECK (replies_count >= 0),
    created_at TIMESTAMPTZ DEFAULT now(),
    last_edited_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE post_media (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES posts(id),
    media_id UUID NOT NULL REFERENCES media(id),
    position INTEGER DEFAULT 0 CHECK (position >= 0),
    is_cover BOOLEAN DEFAULT false
);

CREATE TABLE post_reactions (
    post_id UUID NOT NULL REFERENCES posts(id),
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(32) NOT NULL CHECK (type IN ('LIKE', 'DISLIKE')),
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE post_participations (
    post_id UUID NOT NULL REFERENCES posts(id),
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(32) NOT NULL CHECK (type IN ('INTERESTED', 'TAKES_PART')),
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

CREATE TABLE user_relations (
    source_user_id UUID NOT NULL REFERENCES users(id),
    target_user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(32) NOT NULL CHECK (type IN ('FOLLOW', 'BLOCK')),
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (source_user_id, target_user_id)
);

-- ============================================
-- Indexes
-- ============================================

CREATE INDEX idx_posts_author ON posts(author_id);
CREATE INDEX idx_posts_created_at ON posts(created_at DESC, id DESC);
CREATE INDEX idx_posts_status ON posts(status);
CREATE INDEX idx_posts_type ON posts(type);
CREATE INDEX idx_posts_visibility ON posts(visibility);
CREATE INDEX idx_posts_starts_at ON posts(starts_at);
CREATE INDEX idx_posts_location ON posts(event_location_id);

CREATE INDEX idx_comments_post ON comments(post_id);
CREATE INDEX idx_comments_author ON comments(author_id);
CREATE INDEX idx_comments_root ON comments(root_comment_id);
CREATE INDEX idx_comments_parent ON comments(parent_comment_id);

CREATE INDEX idx_media_owner ON media(owner_id);
CREATE INDEX idx_media_status ON media(status);
CREATE INDEX idx_media_purpose ON media(purpose);

CREATE INDEX idx_post_reactions_user ON post_reactions(user_id);
CREATE INDEX idx_post_participations_user ON post_participations(user_id);

CREATE INDEX idx_user_relations_source ON user_relations(source_user_id);
CREATE INDEX idx_user_relations_target ON user_relations(target_user_id);
CREATE INDEX idx_user_relations_type ON user_relations(type);

CREATE INDEX idx_profile_images_user ON profile_images(user_id);
CREATE INDEX idx_post_media_post ON post_media(post_id);

-- PostGIS index for geospatial queries
CREATE INDEX idx_event_locations_coordinates ON event_locations USING GIST (coordinates);
