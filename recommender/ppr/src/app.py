"""Reads the app's tables, runs personalised PageRank for every user, and writes one run of rec_ppr_scores."""
import logging
from typing import Any

import numpy as np
import psycopg

from ppr import Inputs, scores
from settings import settings

logger = logging.getLogger("ppr")
logger.setLevel(logging.INFO)

# The feed's own rules, from FeedConfig and PostSql. tests/test_feed_constants.py checks they agree.
EMBEDDING_MODEL = "sdadas/mmlw-roberta-base"
STANCE_WEIGHTS = {"LIKE": 1.0, "INTERESTED": 1.5, "TAKES_PART": 2.0}
STANCE_HALF_LIFE_DAYS = 14
STANCE_MAX_AGE_DAYS = 112
DEFAULT_EVENT_HOURS = 3

EVENT_END = f"COALESCE(p.ends_at, p.starts_at + interval '{DEFAULT_EVENT_HOURS} hours')"

# Runs older than the newest one finished over a day ago, which no session still reads.
PRUNE = """
DELETE FROM rec_ppr_runs
WHERE finished_at < (SELECT max(finished_at) FROM rec_ppr_runs WHERE finished_at < now() - interval '1 day')
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
        f"""SELECT p.id, pe.embedding::real[], {EVENT_END} >= now(), p.author_id,
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
    relations = conn.execute("SELECT source_user_id, target_user_id, type FROM user_relations").fetchall()
    sharing = {u for (u,) in conn.execute("SELECT id FROM users WHERE share_stances")}
    reacted = conn.execute(
        "SELECT user_id, post_id FROM post_reactions UNION SELECT user_id, post_id FROM post_participations").fetchall()

    following = {(a, b) for a, b, t in relations if t == "FOLLOW"}
    blocked = {pair for a, b, t in relations if t == "BLOCK" for pair in ((a, b), (b, a))}
    # Only to friends who share their stances, the people whose stances the cards show: a one-way follow must not
    # reveal where someone goes.
    follows = sorted((a, b) for a, b in following if (b, a) in following and b in sharing and (a, b) not in blocked)
    event_index = {row[0]: e for e, row in enumerate(events)}
    interest_index = {row[0]: i for i, row in enumerate(interests)}
    user_ids = sorted({u for u, _ in picks} | {row[0] for row in stances} | {u for pair in follows for u in pair})
    user_index = {u: j for j, u in enumerate(user_ids)}

    # What the feed never shows a user as new: PostSql's NOT_MINE and NOT_BLOCKED.
    hidden: dict[int, set[int]] = {}
    for user, post in reacted:
        if user in user_index and post in event_index:
            hidden.setdefault(user_index[user], set()).add(event_index[post])
    by_author: dict[Any, list[int]] = {}
    for e, (_, _, scorable, author, _) in enumerate(events):
        if scorable:
            by_author.setdefault(author, []).append(e)
    for user, u in user_index.items():
        for author in {user} | {b for a, b in blocked if a == user}:
            hidden.setdefault(u, set()).update(by_author.get(author, ()))

    return Inputs(
        event_ids=[row[0] for row in events],
        event_vectors=np.array([row[1] for row in events], dtype=float),
        scorable=np.array([row[2] for row in events], dtype=bool),
        venues=[row[4] for row in events],
        post_average=np.array(average, dtype=float),
        interest_vectors=np.array([row[1] for row in interests], dtype=float),
        user_ids=user_ids,
        picks=[(user_index[u], interest_index[k]) for u, k in picks if k in interest_index],
        stances=[(user_index[u], event_index[p], STANCE_WEIGHTS[t] * 0.5 ** (float(age) / STANCE_HALF_LIFE_DAYS))
                 for u, p, t, age in stances if p in event_index],
        follows=[(user_index[a], user_index[b]) for a, b in follows],
        hidden=hidden,
    )


def write(conn: psycopg.Connection, inputs: Inputs) -> tuple[int, int]:
    rows = 0
    with conn.transaction():
        (run,) = conn.execute("INSERT INTO rec_ppr_runs DEFAULT VALUES RETURNING run_number").fetchone()
        with conn.cursor().copy("COPY rec_ppr_scores (run_number, user_id, post_id, score) FROM STDIN") as copy:
            for user, event, score in scores(inputs):
                copy.write_row((run, inputs.user_ids[user], inputs.event_ids[event], score))
                rows += 1
        # Not now(), which is when the transaction began, before the scores were written.
        conn.execute("UPDATE rec_ppr_runs SET finished_at = clock_timestamp() WHERE run_number = %s", (run,))
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
