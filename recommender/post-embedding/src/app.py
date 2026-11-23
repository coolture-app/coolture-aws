import json
import logging
import time
from collections.abc import Callable
from typing import Any
from urllib.parse import unquote_plus
from uuid import UUID

import numpy as np
import psycopg
from psycopg import errors

import kinds
from models import PostContent
from settings import settings

logger = logging.getLogger("post-embedding")
logger.setLevel(logging.INFO)

KEY_PREFIX = "posts/"
EMBED_INTERESTS_ACTION = "embed-interests"
DERIVE_KINDS_ACTION = "derive-kinds"
EMBED_POSTS_ACTION = "embed-posts"
EMBED_POSTS_BATCH = 8
# embed-posts starts no batch with less time than this left, so the last batch, its writes and the reply fit.
EMBED_POSTS_RESERVE_SECONDS = 30
NOT_DELETED = "p.status <> 'DELETED' AND p.deleted_at IS NULL"
# A vector is current when it is of this model and not older than the post's last edit. The worker's upsert sets
# updated_at, so an edit whose embedding request failed is picked up here, and a post embedded after its edit is not.
NEEDS_EMBEDDING = (
    "NOT EXISTS (SELECT 1 FROM post_embeddings pe WHERE pe.post_id = p.id AND pe.model = %s "
    "AND (p.last_modified_at IS NULL OR pe.updated_at >= p.last_modified_at))"
)

_model = None
_conn: psycopg.Connection | None = None


def _get_model():
    global _model
    if _model is None:
        from sentence_transformers import SentenceTransformer

        logger.info("loading model %s from %s", settings.embedding_model, settings.embedding_model_dir)
        _model = SentenceTransformer(settings.embedding_model_dir, device="cpu")
    return _model


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


def post_ids_from_s3_event(body: str) -> list[UUID]:
    notification = json.loads(body)
    if notification.get("Event") == "s3:TestEvent":
        return []

    post_ids = []
    for s3_record in notification["Records"]:
        key = unquote_plus(s3_record["s3"]["object"]["key"])
        if not key.startswith(KEY_PREFIX):
            raise ValueError(f"unexpected object key: {key}")
        post_ids.append(UUID(key.removeprefix(KEY_PREFIX)))
    return post_ids


def find_posts(
    conn: psycopg.Connection, where: str, params: tuple, limit: int | None = None
) -> list[tuple[UUID, PostContent]]:
    """The posts that aren't deleted and match where, newest first."""
    rows = conn.execute(
        f"""
        SELECT p.id, p.title, p.description, el.venue_name, el.city
        FROM posts p
        LEFT JOIN event_locations el ON el.id = p.event_location_id
        WHERE {NOT_DELETED} AND {where}
        ORDER BY p.created_at DESC, p.id
        """ + ("LIMIT %s" if limit else ""),
        (*params, limit) if limit else params,
    ).fetchall()
    return [
        (post_id, PostContent(title=title, description=description, venue_name=venue_name, city=city))
        for post_id, title, description, venue_name, city in rows
    ]


def _encode(texts: list[str]) -> list[list[float]]:
    # The model has no normalization layer, and user vectors average these, so length would act as a weight.
    return _get_model().encode(texts, normalize_embeddings=True).tolist()


def embed(conn: psycopg.Connection, posts: list[tuple[UUID, PostContent]]) -> None:
    vectors = _encode([post.text for _, post in posts])
    store_vectors(conn, [(post_id, vector) for (post_id, _), vector in zip(posts, vectors)])


def upsert_embedding(conn: psycopg.Connection, post_id: UUID, vector: list[float]) -> None:
    conn.execute(
        """
        INSERT INTO post_embeddings (post_id, embedding, model)
        VALUES (%s, %s, %s)
        ON CONFLICT (post_id) DO UPDATE
            SET embedding  = EXCLUDED.embedding,
                model      = EXCLUDED.model,
                updated_at = now()
        """,
        (str(post_id), vector, settings.embedding_model),
    )


def process_post(post_id: UUID) -> None:
    conn = _get_connection()
    posts = find_posts(conn, "p.id = %s", (str(post_id),))
    if not posts:
        logger.warning("post %s not found or deleted, skipping", post_id)
        return

    logger.info("processing post %s: %r", post_id, posts[0][1].title)
    embed(conn, posts)


def store_vectors(conn: psycopg.Connection, vectors: list[tuple[UUID, list[float]]]) -> None:
    """Each post's vector, then its kinds from it. A post gone in the meantime is skipped."""
    stored = []
    for post_id, vector in vectors:
        try:
            upsert_embedding(conn, post_id, vector)
        except errors.ForeignKeyViolation:
            logger.warning("post %s no longer exists, skipping", post_id)
            continue
        logger.info("stored embedding for post %s", post_id)
        stored.append((post_id, vector))
    if not stored:
        return

    kind_vectors = find_kind_vectors(conn)
    if kind_vectors is None:
        logger.warning(
            "kinds have no vectors for %s yet, run embed-interests; %d post(s) have no kinds",
            settings.embedding_model, len(stored),
        )
        return
    post_kinds = [(post_id, kinds.derive(kinds.cosines(vector, kind_vectors))) for post_id, vector in stored]
    try:
        upsert_post_kinds(conn, post_kinds)
    except errors.ForeignKeyViolation:
        logger.warning("a post no longer exists, skipping the kinds of %s", [str(post_id) for post_id, _ in stored])
        return
    for post_id, derived in post_kinds:
        logger.info("stored kinds %s for post %s", derived, post_id)


def count_posts_to_embed(conn: psycopg.Connection) -> int:
    return conn.execute(
        f"SELECT count(*) FROM posts p WHERE {NOT_DELETED} AND {NEEDS_EMBEDDING}",
        (settings.embedding_model,),
    ).fetchone()[0]


def embed_pending_posts(conn: psycopg.Connection, seconds_left: Callable[[], float]) -> dict[str, int]:
    """Newest first, in batches, until none are left or the time is nearly up. Says how many are left."""
    embedded = 0
    while seconds_left() > EMBED_POSTS_RESERVE_SECONDS:
        posts = find_posts(conn, NEEDS_EMBEDDING, (settings.embedding_model,), EMBED_POSTS_BATCH)
        if not posts:
            break
        started = time.monotonic()
        embed(conn, posts)
        embedded += len(posts)
        logger.info("embedded %d post(s) in %.1f s", len(posts), time.monotonic() - started)
    return {"posts": embedded, "left": count_posts_to_embed(conn)}


def find_kind_vectors(conn: psycopg.Connection) -> dict[str, list[float]] | None:
    """None until every kind has a vector of the current model and texts: a missing kind would never be derived."""
    rows = conn.execute(
        """
        SELECT k.key, ke.embedding::real[]
        FROM event_kinds k
        LEFT JOIN event_kind_embeddings ke
            ON ke.kind_key = k.key AND ke.model = %s AND ke.embedded_texts = k.embedding_texts
        ORDER BY k.position, k.key
        """,
        (settings.embedding_model,),
    ).fetchall()
    if not rows or any(vector is None for _, vector in rows):
        return None
    return dict(rows)


def upsert_post_kinds(conn: psycopg.Connection, rows: list[tuple[UUID, list[str]]]) -> None:
    with conn.cursor() as cur:
        cur.executemany(
            """
            INSERT INTO rec_post_kinds (post_id, kinds, model)
            VALUES (%s, %s, %s)
            ON CONFLICT (post_id) DO UPDATE
                SET kinds      = EXCLUDED.kinds,
                    model      = EXCLUDED.model,
                    updated_at = now()
            """,
            [(str(post_id), post_kinds, settings.embedding_model) for post_id, post_kinds in rows],
        )


def derive_all_kinds(conn: psycopg.Connection) -> int:
    """Every post with a vector of the current model, from that vector. Kinds of posts without one are removed."""
    kind_vectors = find_kind_vectors(conn)
    if kind_vectors is None:
        raise RuntimeError(f"kinds have no vectors for {settings.embedding_model}, run embed-interests first")
    posts = conn.execute(
        "SELECT post_id, embedding::real[] FROM post_embeddings WHERE model = %s",
        (settings.embedding_model,),
    ).fetchall()
    with conn.transaction():
        upsert_post_kinds(
            conn, [(post_id, kinds.derive(kinds.cosines(vector, kind_vectors))) for post_id, vector in posts]
        )
        conn.execute("DELETE FROM rec_post_kinds WHERE model <> %s", (settings.embedding_model,))
    return len(posts)


def embed_kinds(conn: psycopg.Connection) -> int:
    rows = conn.execute(
        """
        SELECT k.key, k.embedding_texts
        FROM event_kinds k
        LEFT JOIN event_kind_embeddings ke ON ke.kind_key = k.key
        WHERE ke.model IS DISTINCT FROM %s OR ke.embedded_texts IS DISTINCT FROM k.embedding_texts
        """,
        (settings.embedding_model,),
    ).fetchall()
    for key, texts in rows:
        mean = np.asarray(_encode(texts), dtype=np.float64).mean(axis=0)
        conn.execute(
            """
            INSERT INTO event_kind_embeddings (kind_key, embedding, model, embedded_texts)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (kind_key) DO UPDATE
                SET embedding      = EXCLUDED.embedding,
                    model          = EXCLUDED.model,
                    embedded_texts = EXCLUDED.embedded_texts,
                    updated_at     = now()
            """,
            (key, (mean / np.linalg.norm(mean)).tolist(), settings.embedding_model, texts),
        )
    return len(rows)


def embed_interests(conn: psycopg.Connection) -> int:
    rows = conn.execute(
        """
        SELECT i.key, i.embedding_text
        FROM interests i
        LEFT JOIN interest_embeddings ie ON ie.interest_key = i.key
        WHERE ie.model IS DISTINCT FROM %s OR ie.embedded_text IS DISTINCT FROM i.embedding_text
        """,
        (settings.embedding_model,),
    ).fetchall()
    for key, text in rows:
        conn.execute(
            """
            INSERT INTO interest_embeddings (interest_key, embedding, model, embedded_text)
            VALUES (%s, %s, %s, %s)
            ON CONFLICT (interest_key) DO UPDATE
                SET embedding     = EXCLUDED.embedding,
                    model         = EXCLUDED.model,
                    embedded_text = EXCLUDED.embedded_text,
                    updated_at    = now()
            """,
            (key, _encode([text])[0], settings.embedding_model, text),
        )
    return len(rows)


def lambda_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    if event.get("action") == EMBED_INTERESTS_ACTION:
        conn = _get_connection()
        return {"interests": embed_interests(conn), "kinds": embed_kinds(conn)}
    if event.get("action") == DERIVE_KINDS_ACTION:
        return {"posts": derive_all_kinds(_get_connection())}
    if event.get("action") == EMBED_POSTS_ACTION:
        return embed_pending_posts(_get_connection(), lambda: context.get_remaining_time_in_millis() / 1000)

    failures = []
    for record in event.get("Records", []):
        try:
            for post_id in post_ids_from_s3_event(record["body"]):
                process_post(post_id)
        except Exception:
            logger.exception("error on message %s, leaving it for retry/DLQ", record.get("messageId"))
            failures.append({"itemIdentifier": record["messageId"]})

    return {"batchItemFailures": failures}
