import json
import logging
from typing import Any
from urllib.parse import unquote_plus
from uuid import UUID

import numpy as np
import psycopg
from psycopg import errors

import kinds
from models import PostContent, PostEmbeddingRecord
from settings import settings

logger = logging.getLogger("post-embedding")
logger.setLevel(logging.INFO)

KEY_PREFIX = "posts/"
EMBED_INTERESTS_ACTION = "embed-interests"
DERIVE_KINDS_ACTION = "derive-kinds"

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


def find_post_content(conn: psycopg.Connection, post_id: UUID) -> PostContent | None:
    row = conn.execute(
        """
        SELECT p.title, p.description, el.venue_name, el.city
        FROM posts p
        LEFT JOIN event_locations el ON el.id = p.event_location_id
        WHERE p.id = %s AND p.status <> 'DELETED' AND p.deleted_at IS NULL
        """,
        (str(post_id),),
    ).fetchone()
    if row is None:
        return None
    title, description, venue_name, city = row
    return PostContent(title=title, description=description, venue_name=venue_name, city=city)


def upsert_embedding(conn: psycopg.Connection, record: PostEmbeddingRecord) -> None:
    conn.execute(
        """
        INSERT INTO post_embeddings (post_id, embedding, model)
        VALUES (%s, %s, %s)
        ON CONFLICT (post_id) DO UPDATE
            SET embedding  = EXCLUDED.embedding,
                model      = EXCLUDED.model,
                updated_at = now()
        """,
        (str(record.post_id), record.embedding, record.model),
    )


def process_post(post_id: UUID) -> None:
    conn = _get_connection()
    post = find_post_content(conn, post_id)
    if post is None:
        logger.warning("post %s not found or deleted, skipping", post_id)
        return

    logger.info("processing post %s: %r", post_id, post.title)
    # The model has no normalization layer, and user vectors average these, so length would act as a weight.
    vector = _get_model().encode(post.text, normalize_embeddings=True).tolist()
    try:
        upsert_embedding(
            conn,
            PostEmbeddingRecord(post_id=post_id, embedding=vector, model=settings.embedding_model),
        )
    except errors.ForeignKeyViolation:
        logger.warning("post %s no longer exists, skipping", post_id)
        return

    logger.info("stored embedding for post %s", post_id)

    kind_vectors = find_kind_vectors(conn)
    if kind_vectors is None:
        logger.warning("kinds have no vectors for %s yet, run embed-interests; post %s has no kinds", settings.embedding_model, post_id)
        return
    post_kinds = kinds.derive(kinds.cosines(vector, kind_vectors))
    try:
        upsert_post_kinds(conn, [(post_id, post_kinds)])
    except errors.ForeignKeyViolation:
        logger.warning("post %s no longer exists, skipping its kinds", post_id)
        return
    logger.info("stored kinds %s for post %s", post_kinds, post_id)


def find_kind_vectors(conn: psycopg.Connection) -> dict[str, list[float]] | None:
    """None until every kind has a vector of the current model and texts, since a missing kind would never be derived."""
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
        upsert_post_kinds(conn, [(post_id, kinds.derive(kinds.cosines(vector, kind_vectors))) for post_id, vector in posts])
        conn.execute("DELETE FROM rec_post_kinds WHERE model <> %s", (settings.embedding_model,))
    return len(posts)


def _unit(vector: np.ndarray) -> np.ndarray:
    return vector / np.linalg.norm(vector)


def embed_kinds(conn: psycopg.Connection) -> int:
    """A kind's vector: its texts' mean, blended with its labelled posts' mean where the model has one."""
    rows = conn.execute(
        """
        SELECT k.key, k.embedding_texts, pm.embedding::real[], pm.updated_at
        FROM event_kinds k
        LEFT JOIN event_kind_embeddings ke ON ke.kind_key = k.key
        LEFT JOIN event_kind_post_means pm ON pm.kind_key = k.key AND pm.model = %s
        WHERE ke.model IS DISTINCT FROM %s
           OR ke.embedded_texts IS DISTINCT FROM k.embedding_texts
           OR ke.post_mean_updated_at IS DISTINCT FROM pm.updated_at
        """,
        (settings.embedding_model, settings.embedding_model),
    ).fetchall()
    for key, texts, post_mean, post_mean_at in rows:
        vector = _unit(np.asarray(_get_model().encode(texts, normalize_embeddings=True), dtype=np.float64).mean(axis=0))
        if post_mean is not None:
            weight = settings.kind_texts_weight
            vector = _unit(weight * vector + (1 - weight) * _unit(np.asarray(post_mean, dtype=np.float64)))
        conn.execute(
            """
            INSERT INTO event_kind_embeddings (kind_key, embedding, model, embedded_texts, post_mean_updated_at)
            VALUES (%s, %s, %s, %s, %s)
            ON CONFLICT (kind_key) DO UPDATE
                SET embedding            = EXCLUDED.embedding,
                    model                = EXCLUDED.model,
                    embedded_texts       = EXCLUDED.embedded_texts,
                    post_mean_updated_at = EXCLUDED.post_mean_updated_at,
                    updated_at           = now()
            """,
            (key, vector.tolist(), settings.embedding_model, texts, post_mean_at),
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
            (key, _get_model().encode(text, normalize_embeddings=True).tolist(), settings.embedding_model, text),
        )
    return len(rows)


def lambda_handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    if event.get("action") == EMBED_INTERESTS_ACTION:
        conn = _get_connection()
        result = {"interests": embed_interests(conn), "kinds": embed_kinds(conn)}
        if result["kinds"]:
            result["posts"] = derive_all_kinds(conn)
        return result
    if event.get("action") == DERIVE_KINDS_ACTION:
        return {"posts": derive_all_kinds(_get_connection())}

    failures = []
    for record in event.get("Records", []):
        try:
            for post_id in post_ids_from_s3_event(record["body"]):
                process_post(post_id)
        except Exception:
            logger.exception("error on message %s, leaving it for retry/DLQ", record.get("messageId"))
            failures.append({"itemIdentifier": record["messageId"]})

    return {"batchItemFailures": failures}
