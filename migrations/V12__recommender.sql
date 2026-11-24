-- The recommender's tables, beside the interests and kinds of event.

-- The feed builds the viewer's vector itself, from their stances and interests up to the session's start, so user
-- vectors aren't stored.

DROP TABLE user_embeddings;

-- Written by recommender/ppr; see recommender/README.md, Personalised PageRank. No foreign keys: rows are rebuilt every
-- run, the job deletes a run's scores with the run, and a check per row made writing a run four times slower.

CREATE TABLE rec_ppr_runs (
    run_number BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    finished_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE rec_ppr_scores (
    run_number BIGINT NOT NULL,
    user_id UUID NOT NULL,
    post_id UUID NOT NULL,
    score REAL NOT NULL CHECK (score >= 0 AND score <= 1),
    PRIMARY KEY (run_number, user_id, post_id)
);
