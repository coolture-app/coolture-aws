-- Written by recommender/graph-signal. A feed session reads the latest run finished by its asOf.

CREATE TABLE rec_graph_runs (
    run_number BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_rec_graph_runs_finished ON rec_graph_runs (finished_at);

CREATE TABLE rec_graph_scores (
    run_number BIGINT NOT NULL REFERENCES rec_graph_runs (run_number) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id UUID NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    score REAL NOT NULL CHECK (score >= 0 AND score <= 1),
    PRIMARY KEY (run_number, user_id, post_id)
);
