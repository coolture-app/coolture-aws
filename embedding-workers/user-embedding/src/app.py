import logging
import math
from collections import defaultdict
from collections.abc import Iterable
from typing import Any

import numpy as np
import psycopg

from settings import settings

logger = logging.getLogger("user-embedding")
logger.setLevel(logging.INFO)

WEIGHTS: dict[str, float] = {
    "LIKE": settings.weight_like,
    "DISLIKE": settings.weight_dislike,
    "INTERESTED": settings.weight_interested,
    "TAKES_PART": settings.weight_takes_part,
}

_conn: psycopg.Connection | None = None


def _get_connection() -> psycopg.Connection:
    global _conn
    if _conn is None or _conn.closed:
        _conn = psycopg.connect(
            host=settings.db_host,
            port=settings.db_port,
            dbname=settings.db_name,
            user=settings.db_username,
            connect_timeout=settings.db_connect_timeout_seconds,
            autocommit=True,
            **_db_credentials(),
        )
    return _conn


def _db_credentials() -> dict[str, str]:
    if not settings.db_iam_auth:
        return {"password": settings.db_password}
    import boto3

    token = boto3.client("rds", region_name=settings.aws_region).generate_db_auth_token(
        DBHostname=settings.db_host, Port=settings.db_port, DBUsername=settings.db_username
    )
    return {"password": token, "sslmode": "require"}


def weighted_vectors(rows: Iterable[tuple[Any, np.ndarray, float]]) -> dict[Any, np.ndarray]:
    totals: dict[Any, np.ndarray] = {}
    for user_id, vector, weight in rows:
        totals[user_id] = totals.get(user_id, 0.0) + vector * weight

    result: dict[Any, np.ndarray] = {}
    for user_id, total in totals.items():
        norm = np.linalg.norm(total)
        if norm > 1e-9:
            result[user_id] = total / norm
    return result


def interest_share(engagement: float) -> float:
    return max(settings.interest_weight_minimum, math.exp(-engagement / settings.interest_decay_scale))


def blend_user_vectors(
    behaviour_vectors: dict[Any, np.ndarray],
    interest_vectors: dict[Any, np.ndarray],
    engagement: dict[Any, float],
) -> dict[Any, np.ndarray]:
    blended = []
    for user_id, interests in interest_vectors.items():
        share = interest_share(engagement.get(user_id, 0.0))
        blended.append((user_id, interests, share))
        if user_id in behaviour_vectors:
            blended.append((user_id, behaviour_vectors[user_id], 1 - share))
    return behaviour_vectors | weighted_vectors(blended)


def load_interactions(
    cur: psycopg.Cursor, post_vectors: dict[Any, np.ndarray]
) -> tuple[dict[Any, np.ndarray], dict[Any, float]]:
    cur.execute(
        """
        SELECT user_id, post_id, type, created_at >= now() - make_interval(days => %s)
        FROM (
            SELECT user_id, post_id, type, created_at FROM post_reactions
            UNION ALL
            SELECT user_id, post_id, type, created_at FROM post_participations
        ) interactions
        """,
        (settings.engagement_window_days,),
    )
    engagement: dict[Any, float] = defaultdict(float)
    rows = []
    for user_id, post_id, interaction_type, is_recent in cur:
        vector = post_vectors.get(post_id)
        weight = WEIGHTS.get(interaction_type, 0.0)
        if vector is not None:
            rows.append((user_id, vector, weight))
            if is_recent:
                engagement[user_id] += abs(weight)
    return weighted_vectors(rows), engagement


def load_interest_vectors(cur: psycopg.Cursor) -> dict[Any, np.ndarray]:
    cur.execute("SELECT key, embedding::real[], parent_key IS NULL FROM interests WHERE embedding IS NOT NULL")
    interests = {
        key: (np.array(embedding, dtype=np.float32), settings.interest_weight_category if is_category else 1.0)
        for key, embedding, is_category in cur
    }
    cur.execute("SELECT user_id, interest_key FROM user_interests")
    return weighted_vectors((user_id, *interests[key]) for user_id, key in cur if key in interests)


def run_once(conn: psycopg.Connection) -> int:
    with conn.cursor() as cur:
        started_at = cur.execute("SELECT now()").fetchone()[0]
        cur.execute("SELECT post_id, embedding::real[] FROM post_embeddings")
        post_vectors = {post_id: np.array(embedding, dtype=np.float32) for post_id, embedding in cur}
        behaviour_vectors, engagement = load_interactions(cur, post_vectors)
        interest_vectors = load_interest_vectors(cur)

    user_vectors = blend_user_vectors(behaviour_vectors, interest_vectors, engagement)

    if not user_vectors:
        logger.info("no interactions or interests with embeddings found, skipping")
        return 0

    rows = [
        (str(user_id), "[" + ",".join(map(str, vector.tolist())) + "]", settings.embedding_model, started_at)
        for user_id, vector in user_vectors.items()
    ]

    with conn.transaction(), conn.cursor() as cur:
        cur.executemany(
            """
            INSERT INTO user_embeddings (user_id, embedding, model, updated_at)
            VALUES (%s, %s::vector, %s, now())
            ON CONFLICT (user_id) DO UPDATE
                SET embedding  = EXCLUDED.embedding,
                    model      = EXCLUDED.model,
                    updated_at = now()
                WHERE user_embeddings.updated_at < %s
            """,
            rows,
        )

    logger.info("upserted user embeddings for %d users", len(rows))
    return len(rows)


def lambda_handler(_event: dict[str, Any], _context: Any) -> dict[str, int]:
    return {"upserted": run_once(_get_connection())}
