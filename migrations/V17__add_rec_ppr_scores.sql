-- Written by recommender/ppr; see its README. No keys to users or posts: rows are rebuilt every run, and a
-- cascade from a user or post delete would scan this table.

CREATE TABLE rec_ppr_runs (
    run_number BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    finished_at TIMESTAMPTZ
);

CREATE TABLE rec_ppr_scores (
    run_number BIGINT NOT NULL REFERENCES rec_ppr_runs (run_number) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    post_id UUID NOT NULL,
    score REAL NOT NULL CHECK (score >= 0 AND score <= 1),
    PRIMARY KEY (run_number, user_id, post_id)
);
