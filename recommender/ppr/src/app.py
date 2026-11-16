"""Reads the app's tables, runs personalised PageRank for every user, and writes one run of rec_ppr_scores."""
import logging
import time
from collections import defaultdict
from collections.abc import Sequence
from typing import Any
from uuid import UUID

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

POST_AVERAGE = """
SELECT avg(pe.embedding)::real[]
FROM post_embeddings pe
JOIN posts p ON p.id = pe.post_id
WHERE pe.model = %s AND p.created_at <= now()
"""

# Ended events stay while their stances count, to connect people and places.
EVENTS = f"""
SELECT p.id,
       vector_send(pe.embedding) AS vector,
       {EVENT_END} >= now() AS not_ended,
       p.author_id,
       CASE WHEN btrim(coalesce(el.venue_name, '')) <> ''
            THEN lower(btrim(el.venue_name)) || ' | ' || lower(btrim(coalesce(el.city, ''))) END AS venue
FROM posts p
JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
LEFT JOIN event_locations el ON el.id = p.event_location_id
WHERE p.deleted_at IS NULL AND {EVENT_END} >= now() - make_interval(days => %s)
ORDER BY p.id
"""

STANCES = """
SELECT user_id, post_id, type, floor(extract(epoch FROM now() - created_at) / 86400)::int AS age_days
FROM (SELECT user_id, post_id, type, created_at FROM post_reactions
      UNION ALL
      SELECT user_id, post_id, type, created_at FROM post_participations) s
WHERE type = ANY(%s) AND created_at > now() - make_interval(days => %s)
"""

# Runs older than the newest one finished over a day ago, which no session still reads.
DELETE_OLD_RUNS = """
WITH old AS (
    DELETE FROM rec_ppr_runs
    WHERE finished_at < (SELECT max(finished_at) FROM rec_ppr_runs WHERE finished_at < now() - interval '1 day')
    RETURNING run_number
)
DELETE FROM rec_ppr_scores WHERE run_number IN (SELECT run_number FROM old)
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


def _matrix(sent: Sequence[bytes]) -> np.ndarray:
    # pgvector's binary form: dimensions and an unused int16, then big-endian float4. Text arrays took four times as
    # long and three times the memory.
    return np.array([np.frombuffer(v, dtype=">f4", offset=4) for v in sent], dtype=float)


def _positions(items: Sequence) -> dict[Any, int]:
    return {item: i for i, item in enumerate(items)}


def _stance_weight(kind: str, age_days: int) -> float:
    return STANCE_WEIGHTS[kind] * 0.5 ** (age_days / STANCE_HALF_LIFE_DAYS)


def _hidden(user_index: dict[UUID, int], event_index: dict[UUID, int], authors: Sequence[UUID],
            reacted: list[tuple[UUID, UUID]], blocks: list[tuple[UUID, UUID]]) -> dict[int, set[int]]:
    """What the feed never shows a user as new: PostSql's NOT_MINE and NOT_BLOCKED."""
    events_by_author = defaultdict(list)
    for e, author in enumerate(authors):
        events_by_author[author].append(e)
    unseen_authors = defaultdict(set)
    for a, b in blocks:
        unseen_authors[a].add(b)
        unseen_authors[b].add(a)

    hidden = {}
    for user, u in user_index.items():
        hidden[u] = {e for author in {user} | unseen_authors[user] for e in events_by_author[author]}
    for user, post in reacted:
        if user in user_index and post in event_index:
            hidden[user_index[user]].add(event_index[post])
    return hidden


def load(conn: psycopg.Connection) -> Inputs | None:
    """None when there are no events or no interests with vectors of EMBEDDING_MODEL."""
    (average,) = conn.execute(POST_AVERAGE, (EMBEDDING_MODEL,)).fetchone()
    events = conn.execute(EVENTS, (EMBEDDING_MODEL, STANCE_MAX_AGE_DAYS)).fetchall()
    interests = conn.execute(
        "SELECT interest_key, vector_send(embedding) FROM interest_embeddings WHERE model = %s ORDER BY interest_key",
        (EMBEDDING_MODEL,)).fetchall()
    if average is None or not events or not interests:
        return None
    event_ids, event_vectors, not_ended, authors, venues = zip(*events)
    interest_keys, interest_vectors = zip(*interests)

    picks = conn.execute("SELECT user_id, interest_key FROM user_interests").fetchall()
    stances = conn.execute(STANCES, (list(STANCE_WEIGHTS), STANCE_MAX_AGE_DAYS)).fetchall()
    relations = conn.execute("SELECT source_user_id, target_user_id, type FROM user_relations").fetchall()
    sharing = {u for (u,) in conn.execute("SELECT id FROM users WHERE share_stances")}
    reacted = conn.execute(
        "SELECT user_id, post_id FROM post_reactions UNION SELECT user_id, post_id FROM post_participations").fetchall()

    following = {(a, b) for a, b, kind in relations if kind == "FOLLOW"}
    blocks = [(a, b) for a, b, kind in relations if kind == "BLOCK"]
    # Only to friends who share their stances, the people whose stances the cards show: a one-way follow must not
    # reveal where someone goes. A block replaces the blocker's follow, so friends never block each other.
    follows = sorted((a, b) for a, b in following if (b, a) in following and b in sharing)

    event_index = _positions(event_ids)
    interest_index = _positions(interest_keys)
    user_ids = sorted({u for u, _ in picks} | {u for u, _, _, _ in stances} | {u for pair in follows for u in pair})
    user_index = _positions(user_ids)

    return Inputs(
        event_ids=list(event_ids),
        event_vectors=_matrix(event_vectors),
        not_ended=np.array(not_ended, dtype=bool),
        venues=list(venues),
        post_average=np.array(average, dtype=float),
        interest_vectors=_matrix(interest_vectors),
        user_ids=user_ids,
        picks=[(user_index[u], interest_index[k]) for u, k in picks if k in interest_index],
        stances=[(user_index[u], event_index[p], _stance_weight(kind, age_days))
                 for u, p, kind, age_days in stances if p in event_index],
        follows=[(user_index[a], user_index[b]) for a, b in follows],
        hidden=_hidden(user_index, event_index, authors, reacted, blocks),
    )


def write(conn: psycopg.Connection, inputs: Inputs) -> tuple[int, int]:
    """Scores while it copies, so the rows never all sit in memory."""
    # Before the run's transaction, which then ends right after the run is stamped finished.
    conn.execute(DELETE_OLD_RUNS)
    rows = 0
    with conn.transaction():
        (run,) = conn.execute("INSERT INTO rec_ppr_runs DEFAULT VALUES RETURNING run_number").fetchone()
        with conn.cursor() as cursor:
            with cursor.copy("COPY rec_ppr_scores (run_number, user_id, post_id, score) FROM STDIN") as copy:
                for user, event, score in scores(inputs):
                    copy.write_row((run, inputs.user_ids[user], inputs.event_ids[event], score))
                    rows += 1
        # Not now(), which is when the transaction began, before the scores were written.
        conn.execute("UPDATE rec_ppr_runs SET finished_at = clock_timestamp() WHERE run_number = %s", (run,))
    return run, rows


def lambda_handler(_event: dict[str, Any], context: Any) -> dict[str, Any]:
    started = time.monotonic()
    with _connect() as conn:
        logger.info("connected in %.1f s", time.monotonic() - started)
        inputs = load(conn)
        if inputs is None:
            logger.info("nothing to score: no events or no interests with %s vectors", EMBEDDING_MODEL)
            return {"run": None, "rows": 0}
        loaded = time.monotonic()
        logger.info("loaded %d events (%d not ended), %d interests, %d users in %.1f s", len(inputs.event_ids),
                    inputs.not_ended.sum(), len(inputs.interest_vectors), len(inputs.user_ids), loaded - started)
        # The database ends the run's transaction itself, before Lambda stops this process.
        conn.execute("SELECT set_config('statement_timeout', %s, false)",
                     (f"{context.get_remaining_time_in_millis() - 15_000}ms",))
        run, rows = write(conn, inputs)
    logger.info("run %s: %d rows, scored and written in %.1f s", run, rows, time.monotonic() - loaded)
    return {"run": run, "rows": rows}
