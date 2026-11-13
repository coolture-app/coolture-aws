"""Reads the app's tables, runs the walk for every user, and writes one run of rec_graph_scores."""
import logging
from typing import Any

import numpy as np
import psycopg

from graph import Inputs, scores
from settings import settings

logger = logging.getLogger("graph-signal")
logger.setLevel(logging.INFO)

# The feed's own rules, from FeedConfig and PostSql. tests/test_feed_constants.py checks they agree.
EMBEDDING_MODEL = "sdadas/mmlw-roberta-base"
STANCE_WEIGHTS = {"LIKE": 1.0, "INTERESTED": 1.5, "TAKES_PART": 2.0}
STANCE_HALF_LIFE_DAYS = 14
STANCE_MAX_AGE_DAYS = 112
DEFAULT_EVENT_HOURS = 3

EVENT_END = f"COALESCE(p.ends_at, p.starts_at + interval '{DEFAULT_EVENT_HOURS} hours')"

# Runs whose scores landed but which never finished, and runs older than one finished a day ago.
PRUNE = """
DELETE FROM rec_graph_runs
WHERE (finished_at IS NULL AND started_at < now() - interval '1 hour')
   OR run_number < (SELECT max(run_number) FROM rec_graph_runs WHERE finished_at < now() - interval '1 day')
"""


def _connect() -> psycopg.Connection:
    # The same connection as post-embedding's app.py.
    return psycopg.connect(
        host=settings.db_host,
        port=settings.db_port,
        dbname=settings.db_name,
        user=settings.db_username,
        connect_timeout=settings.db_connect_timeout_seconds,
        autocommit=True,
        **_db_credentials(),
    )


def _db_credentials() -> dict[str, str]:
    if not settings.db_iam_auth:
        return {"password": settings.db_password}
    import boto3

    token = boto3.client("rds", region_name=settings.aws_region).generate_db_auth_token(
        DBHostname=settings.db_host, Port=settings.db_port, DBUsername=settings.db_username
    )
    return {"password": token, "sslmode": "require"}


def load(conn: psycopg.Connection) -> Inputs | None:
    """None when there are no post or interest vectors to build a graph from."""
    (average,) = conn.execute(
        """SELECT avg(pe.embedding)::real[] FROM post_embeddings pe JOIN posts p ON p.id = pe.post_id
           WHERE pe.model = %s AND p.created_at <= now()""", (EMBEDDING_MODEL,)).fetchone()
    # Ended events stay while their stances count, to connect people and places.
    events = conn.execute(
        f"""SELECT p.id, pe.embedding::real[], {EVENT_END} >= now(),
                   CASE WHEN btrim(coalesce(el.venue_name, '')) <> ''
                        THEN lower(btrim(el.venue_name)) || ' | ' || lower(btrim(coalesce(el.city, ''))) END
            FROM posts p
            JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.deleted_at IS NULL AND {EVENT_END} >= now() - make_interval(days => %s)
            ORDER BY p.id""", (EMBEDDING_MODEL, STANCE_MAX_AGE_DAYS)).fetchall()
    interests = conn.execute(
        "SELECT interest_key, embedding::real[] FROM interest_embeddings WHERE model = %s ORDER BY interest_key",
        (EMBEDDING_MODEL,)).fetchall()
    if average is None or not events or not interests:
        return None

    picks = conn.execute("SELECT user_id, interest_key FROM user_interests").fetchall()
    stances = conn.execute(
        """SELECT user_id, post_id, type, floor(extract(epoch FROM now() - created_at) / 86400)
           FROM (SELECT user_id, post_id, type, created_at FROM post_reactions
                 UNION ALL SELECT user_id, post_id, type, created_at FROM post_participations) s
           WHERE type = ANY(%s) AND created_at > now() - make_interval(days => %s)""",
        (list(STANCE_WEIGHTS), STANCE_MAX_AGE_DAYS)).fetchall()
    follows = conn.execute(
        """SELECT f.source_user_id, f.target_user_id FROM user_relations f
           WHERE f.type = 'FOLLOW' AND NOT EXISTS (
               SELECT 1 FROM user_relations b WHERE b.type = 'BLOCK'
                 AND ((b.source_user_id = f.source_user_id AND b.target_user_id = f.target_user_id)
                   OR (b.source_user_id = f.target_user_id AND b.target_user_id = f.source_user_id)))""").fetchall()
    # As the feed's NOT_MINE, for the events that can be scored.
    hidden = conn.execute(
        f"""SELECT s.user_id, s.post_id
            FROM (SELECT user_id, post_id FROM post_reactions UNION SELECT user_id, post_id FROM post_participations
                  UNION SELECT author_id, id FROM posts) s
            JOIN posts p ON p.id = s.post_id
            WHERE p.deleted_at IS NULL AND {EVENT_END} >= now()""").fetchall()

    event_index = {row[0]: e for e, row in enumerate(events)}
    interest_index = {row[0]: i for i, row in enumerate(interests)}
    user_ids = sorted({u for u, _ in picks} | {row[0] for row in stances} | {u for pair in follows for u in pair})
    user_index = {u: j for j, u in enumerate(user_ids)}
    hidden_by_user: dict[int, set[int]] = {}
    for user, post in hidden:
        if user in user_index and post in event_index:
            hidden_by_user.setdefault(user_index[user], set()).add(event_index[post])

    return Inputs(
        event_ids=[row[0] for row in events],
        event_vectors=np.array([row[1] for row in events], dtype=float),
        scorable=np.array([row[2] for row in events], dtype=bool),
        venues=[row[3] for row in events],
        post_average=np.array(average, dtype=float),
        interest_vectors=np.array([row[1] for row in interests], dtype=float),
        user_ids=user_ids,
        picks=[(user_index[u], interest_index[k]) for u, k in picks if k in interest_index],
        stances=[(user_index[u], event_index[p], STANCE_WEIGHTS[t] * 0.5 ** (float(age) / STANCE_HALF_LIFE_DAYS))
                 for u, p, t, age in stances if p in event_index],
        follows=[(user_index[a], user_index[b]) for a, b in follows],
        hidden=hidden_by_user,
    )


def write(conn: psycopg.Connection, inputs: Inputs) -> tuple[int, int]:
    """The scores land first and the run is marked finished after, so the feed never reads a run half written."""
    rows = 0
    with conn.transaction():
        (run,) = conn.execute("INSERT INTO rec_graph_runs DEFAULT VALUES RETURNING run_number").fetchone()
        with conn.cursor().copy("COPY rec_graph_scores (run_number, user_id, post_id, score) FROM STDIN") as copy:
            for user, event, score in scores(inputs):
                copy.write_row((run, inputs.user_ids[user], inputs.event_ids[event], score))
                rows += 1
    conn.execute("UPDATE rec_graph_runs SET finished_at = now() WHERE run_number = %s", (run,))
    conn.execute(PRUNE)
    return run, rows


def lambda_handler(_event: dict[str, Any], _context: Any) -> dict[str, Any]:
    with _connect() as conn:
        inputs = load(conn)
        if inputs is None:
            logger.info("no post or interest vectors of %s yet, nothing to score", EMBEDDING_MODEL)
            return {"run": None, "rows": 0}
        run, rows = write(conn, inputs)
    logger.info("run %s: %d events, %d users, %d rows", run, len(inputs.event_ids), len(inputs.user_ids), rows)
    return {"run": run, "rows": rows}
