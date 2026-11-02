import json
import logging
import math
from collections import defaultdict
from collections.abc import Iterable
from typing import Any
from urllib.parse import unquote_plus
from uuid import UUID

import numpy as np
import psycopg

from settings import settings

logger = logging.getLogger("user-embedding")
logger.setLevel(logging.INFO)

KEY_PREFIX = "users/"

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
    cur: psycopg.Cursor, user_ids: list[UUID] | None
) -> tuple[dict[Any, np.ndarray], dict[Any, float]]:
    cur.execute(
        """
        SELECT interactions.user_id, pe.embedding::real[], interactions.type,
               interactions.created_at >= now() - make_interval(days => %(days)s)
        FROM (
            SELECT user_id, post_id, type, created_at FROM post_reactions
            UNION ALL
            SELECT user_id, post_id, type, created_at FROM post_participations
        ) interactions
        JOIN post_embeddings pe ON pe.post_id = interactions.post_id
        WHERE %(users)s::uuid[] IS NULL OR interactions.user_id = ANY(%(users)s)
        """,
        {"days": settings.engagement_window_days, "users": user_ids},
    )
    engagement: dict[Any, float] = defaultdict(float)
    rows = []
    for user_id, embedding, interaction_type, is_recent in cur:
        weight = WEIGHTS.get(interaction_type, 0.0)
        rows.append((user_id, np.array(embedding, dtype=np.float32), weight))
        if is_recent:
            engagement[user_id] += abs(weight)
    return weighted_vectors(rows), engagement


def load_interest_vectors(cur: psycopg.Cursor, user_ids: list[UUID] | None) -> dict[Any, np.ndarray]:
    cur.execute(
        """
        SELECT i.key, i.category_key, ie.embedding::real[]
        FROM interests i
        JOIN interest_embeddings ie ON ie.interest_key = i.key
        """
    )
    interests = {key: (category_key, np.array(embedding, dtype=np.float32)) for key, category_key, embedding in cur}
    categories = weighted_vectors((category_key, vector, 1.0) for category_key, vector in interests.values())

    cur.execute(
        "SELECT user_id, interest_key FROM user_interests WHERE %(users)s::uuid[] IS NULL OR user_id = ANY(%(users)s)",
        {"users": user_ids},
    )
    rows = []
    for user_id, key in cur:
        if key in interests:
            category_key, vector = interests[key]
            rows.append((user_id, vector, 1.0))
            rows.append((user_id, categories[category_key], settings.interest_weight_category))
    return weighted_vectors(rows)


def run_once(conn: psycopg.Connection, user_ids: list[UUID] | None = None) -> int:
    """Recomputes the vectors of the given users, or of every user when user_ids is None."""
    with conn.cursor() as cur:
        started_at = cur.execute("SELECT now()").fetchone()[0]
        behaviour_vectors, engagement = load_interactions(cur, user_ids)
        interest_vectors = load_interest_vectors(cur, user_ids)

    user_vectors = blend_user_vectors(behaviour_vectors, interest_vectors, engagement)

    if not user_vectors:
        logger.info("no interactions or interests with embeddings found, skipping")
        return 0

    rows = [
        (str(user_id), "[" + ",".join(map(str, vector.tolist())) + "]", settings.embedding_model, started_at)
        for user_id, vector in user_vectors.items()
    ]

    # A run that read its data before a newer run wrote must not overwrite the newer vector.
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


def user_ids_from_s3_event(body: str) -> list[UUID]:
    notification = json.loads(body)
    if notification.get("Event") == "s3:TestEvent":
        return []

    user_ids = []
    for s3_record in notification["Records"]:
        key = unquote_plus(s3_record["s3"]["object"]["key"])
        if not key.startswith(KEY_PREFIX):
            raise ValueError(f"unexpected object key: {key}")
        user_ids.append(UUID(key.removeprefix(KEY_PREFIX)))
    return user_ids


def lambda_handler(event: dict[str, Any], _context: Any) -> dict[str, int]:
    """The schedule refreshes every user; SQS messages from S3 refresh the users whose interests changed."""
    if "Records" not in event:
        return {"upserted": run_once(_get_connection())}

    user_ids = [user_id for record in event["Records"] for user_id in user_ids_from_s3_event(record["body"])]
    return {"upserted": run_once(_get_connection(), user_ids) if user_ids else 0}
