"""Reads the app's tables, runs the walk for every user, and writes one run of rec_graph_scores."""
import logging
from typing import Any

import numpy as np
import psycopg

from graph import Inputs, scores
from settings import settings

logger = logging.getLogger("graph-signal")
logger.setLevel(logging.INFO)

# Runs that died, and runs older than one finished over a day ago, which no session still reads.
PRUNE = """
DELETE FROM rec_graph_runs
WHERE (finished_at IS NULL AND started_at < now() - interval '1 hour')
   OR run_number < (SELECT max(run_number) FROM rec_graph_runs WHERE finished_at < now() - interval '1 day')
"""


def _connect() -> psycopg.Connection:
    return psycopg.connect(
        host=settings.db_host,
        port=settings.db_port,
        dbname=settings.db_name,
        user=settings.db_username,
        connect_timeout=settings.db_connect_timeout_seconds,
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


def _vectors(rows, column: int) -> np.ndarray:
    return np.array([row[column] for row in rows], dtype=float).reshape(len(rows), -1)


def load(conn: psycopg.Connection) -> Inputs:
    model = settings.embedding_model
    event_end = f"COALESCE(p.ends_at, p.starts_at + interval '{settings.default_event_hours} hours')"
    weights = {"LIKE": settings.weight_like, "INTERESTED": settings.weight_interested,
               "TAKES_PART": settings.weight_takes_part}

    (average,) = conn.execute(
        """SELECT avg(pe.embedding)::real[] FROM post_embeddings pe JOIN posts p ON p.id = pe.post_id
           WHERE pe.model = %s AND p.created_at <= now()""", (model,)).fetchone()
    # Ended events stay while their stances count, to connect people and places.
    events = conn.execute(
        f"""SELECT p.id, pe.embedding::real[], {event_end} >= now(),
                   CASE WHEN btrim(coalesce(el.venue_name, '')) <> ''
                        THEN lower(btrim(el.venue_name)) || ' | ' || lower(btrim(coalesce(el.city, ''))) END
            FROM posts p
            JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
            LEFT JOIN event_locations el ON el.id = p.event_location_id
            WHERE p.deleted_at IS NULL AND {event_end} >= now() - make_interval(days => %s)
            ORDER BY p.id""", (model, settings.stance_max_age_days)).fetchall()
    interests = conn.execute(
        "SELECT interest_key, embedding::real[] FROM interest_embeddings WHERE model = %s ORDER BY interest_key",
        (model,)).fetchall()
    picks = conn.execute("SELECT user_id, interest_key FROM user_interests").fetchall()
    stances = conn.execute(
        """SELECT user_id, post_id, type, floor(extract(epoch FROM now() - created_at) / 86400)
           FROM (SELECT user_id, post_id, type, created_at FROM post_reactions
                 UNION ALL SELECT user_id, post_id, type, created_at FROM post_participations) s
           WHERE type = ANY(%s) AND created_at > now() - make_interval(days => %s)""",
        (list(weights), settings.stance_max_age_days)).fetchall()
    follows = conn.execute(
        """SELECT f.source_user_id, f.target_user_id FROM user_relations f
           WHERE f.type = 'FOLLOW' AND NOT EXISTS (
               SELECT 1 FROM user_relations b WHERE b.type = 'BLOCK'
                 AND ((b.source_user_id = f.source_user_id AND b.target_user_id = f.target_user_id)
                   OR (b.source_user_id = f.target_user_id AND b.target_user_id = f.source_user_id)))""").fetchall()
    # As the feed's NOT_MINE: any reaction or stance, and the user's own posts.
    hidden = conn.execute(
        """SELECT user_id, post_id FROM post_reactions UNION SELECT user_id, post_id FROM post_participations
           UNION SELECT author_id, id FROM posts WHERE author_id IS NOT NULL""").fetchall()

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
        event_vectors=_vectors(events, 1),
        scorable=np.array([bool(row[2]) for row in events], dtype=bool),
        venues=[row[3] for row in events],
        post_average=np.array(average, dtype=float) if average else np.zeros(0),
        interest_vectors=_vectors(interests, 1),
        user_ids=user_ids,
        picks=[(user_index[u], interest_index[k]) for u, k in picks if k in interest_index],
        stances=[(user_index[u], event_index[p], weights[t] * 0.5 ** (float(age) / settings.stance_half_life_days))
                 for u, p, t, age in stances if p in event_index],
        follows=[(user_index[a], user_index[b]) for a, b in follows],
        hidden=hidden_by_user,
    )


def write(conn: psycopg.Connection, inputs: Inputs, rows: list[tuple[int, int, float]]) -> int:
    with conn.transaction():
        (run,) = conn.execute("INSERT INTO rec_graph_runs DEFAULT VALUES RETURNING run_number").fetchone()
        with conn.cursor().copy("COPY rec_graph_scores (run_number, user_id, post_id, score) FROM STDIN") as copy:
            for user, event, score in rows:
                copy.write_row((run, inputs.user_ids[user], inputs.event_ids[event], score))
        conn.execute("UPDATE rec_graph_runs SET finished_at = clock_timestamp() WHERE run_number = %s", (run,))
        conn.execute(PRUNE)
    return run


def lambda_handler(_event: dict[str, Any], _context: Any) -> dict[str, Any]:
    with _connect() as conn:
        inputs = load(conn)
        if inputs.post_average.size == 0 or len(inputs.interest_vectors) == 0:
            logger.info("no post or interest vectors of %s yet, nothing to score", settings.embedding_model)
            return {"run": None, "users": 0, "rows": 0}
        rows = scores(inputs)
        run = write(conn, inputs, rows)
    users = len({u for u, _, _ in rows})
    logger.info("run %s: %d events, %d users, %d with scores, %d rows",
                run, len(inputs.event_ids), len(inputs.user_ids), users, len(rows))
    return {"run": run, "users": users, "rows": len(rows)}
