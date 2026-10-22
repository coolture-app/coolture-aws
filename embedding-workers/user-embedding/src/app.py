import logging
from collections import defaultdict
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
            password=settings.db_password,
            connect_timeout=5,
            autocommit=True,
        )
    return _conn


def compute_user_vectors(
    interactions: list[tuple[Any, list[float], str]],
) -> dict[Any, np.ndarray]:
    user_data: dict[Any, list[tuple[np.ndarray, float]]] = defaultdict(list)
    for user_id, embedding, interaction_type in interactions:
        weight = WEIGHTS.get(interaction_type, 0.0)
        if weight != 0.0:
            user_data[user_id].append((np.array(embedding, dtype=np.float32), weight))

    result: dict[Any, np.ndarray] = {}
    for user_id, weighted_vecs in user_data.items():
        vectors = np.array([v for v, _ in weighted_vecs])
        weights = np.array([w for _, w in weighted_vecs])

        total_weight = weights.sum()
        if total_weight == 0:
            continue

        vector = (vectors * weights[:, np.newaxis]).sum(axis=0) / total_weight
        norm = np.linalg.norm(vector)
        if norm > 1e-9:
            vector /= norm
        result[user_id] = vector

    return result


def run_once(conn: psycopg.Connection) -> int:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT interactions.user_id, pe.embedding::real[], interactions.type
            FROM (
                SELECT user_id, post_id, type FROM post_reactions
                UNION ALL
                SELECT user_id, post_id, type FROM post_participations
            ) interactions
            JOIN post_embeddings pe ON pe.post_id = interactions.post_id
            """
        )
        user_vectors = compute_user_vectors(cur.fetchall())

    if not user_vectors:
        logger.info("no interactions with embeddings found, skipping")
        return 0

    rows = [
        (str(user_id), "[" + ",".join(map(str, vector.tolist())) + "]", settings.embedding_model)
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
            """,
            rows,
        )

    logger.info("upserted user embeddings for %d users", len(rows))
    return len(rows)


def lambda_handler(_event: dict[str, Any], _context: Any) -> dict[str, int]:
    return {"upserted": run_once(_get_connection())}
