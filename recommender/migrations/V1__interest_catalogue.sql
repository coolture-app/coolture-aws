CREATE TABLE interest_catalogue_versions (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    sha256 TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE interests (
    key VARCHAR(64) PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9_]*$'),
    label_pl VARCHAR(128) NOT NULL,
    label_en VARCHAR(128) NOT NULL,
    embedding_text TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('active', 'retired')),
    catalogue_version INTEGER NOT NULL REFERENCES interest_catalogue_versions(version)
);

CREATE TABLE interest_embeddings (
    interest_key VARCHAR(64) NOT NULL REFERENCES interests(key),
    model_key VARCHAR(128) NOT NULL,
    text_sha256 TEXT NOT NULL,
    embedding vector(768) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (interest_key, model_key)
);

CREATE TABLE user_interest_profiles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL CHECK (revision > 0),
    catalogue_version INTEGER NOT NULL REFERENCES interest_catalogue_versions(version),
    model_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, revision)
);

CREATE TABLE user_interest_selections (
    user_id UUID NOT NULL,
    revision INTEGER NOT NULL,
    interest_key VARCHAR(64) NOT NULL REFERENCES interests(key),
    weight REAL NOT NULL CHECK (weight > 0),
    PRIMARY KEY (user_id, revision, interest_key),
    FOREIGN KEY (user_id, revision) REFERENCES user_interest_profiles(user_id, revision) ON DELETE CASCADE
);
