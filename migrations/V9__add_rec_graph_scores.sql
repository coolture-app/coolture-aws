CREATE SCHEMA IF NOT EXISTS rec;

CREATE TABLE rec.graph_scores (
  node_id text PRIMARY KEY,
  score double precision NOT NULL,
  run_number bigint NOT NULL
);
