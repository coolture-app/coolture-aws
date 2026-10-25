import json
import logging
from typing import Any
from urllib.parse import unquote_plus
from uuid import UUID

import psycopg
from psycopg import errors

from models import PostContent, PostEmbeddingRecord
from settings import settings

logger = logging.getLogger("post-embedding")
logger.setLevel(logging.INFO)

KEY_PREFIX = "posts/"

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
            password=settings.db_password,
            connect_timeout=5,
            autocommit=True,
        )
    return _conn


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
        SELECT title, description, tags
        FROM posts
        WHERE id = %s AND status <> 'DELETED' AND deleted_at IS NULL
        """,
        (str(post_id),),
    ).fetchone()
    if row is None:
        return None
    title, description, tags = row
    return PostContent(title=title, description=description, tags=tags)


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
    vector = _get_model().encode(post.text).tolist()
    try:
        upsert_embedding(
            conn,
            PostEmbeddingRecord(post_id=post_id, embedding=vector, model=settings.embedding_model),
        )
    except errors.ForeignKeyViolation:
        logger.warning("post %s no longer exists, skipping", post_id)
        return

    logger.info("stored embedding for post %s", post_id)


def lambda_handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    failures = []
    for record in event.get("Records", []):
        try:
            for post_id in post_ids_from_s3_event(record["body"]):
                process_post(post_id)
        except Exception:
            logger.exception("error on message %s, leaving it for retry/DLQ", record.get("messageId"))
            failures.append({"itemIdentifier": record["messageId"]})

    return {"batchItemFailures": failures}
