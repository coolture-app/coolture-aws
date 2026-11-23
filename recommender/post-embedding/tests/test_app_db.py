import os

import psycopg
import pytest

import app

pytestmark = pytest.mark.skipif(os.environ.get("DB_TEST") != "true", reason="needs make up and make seed; DB_TEST=true")


@pytest.fixture
def conn():
    """Everything a test does is rolled back, so the developer's own runs stay."""
    with psycopg.connect(host="localhost", port=5430, dbname="coolture", user="coolture_admin", password="admin",
                         autocommit=True) as connection, connection.transaction(force_rollback=True):
        yield connection


def picked(conn):
    return {post_id for (post_id,) in conn.execute(
        f"SELECT p.id FROM posts p WHERE {app.NOT_DELETED} AND {app.NEEDS_EMBEDDING}", (app.settings.embedding_model,))}


def test_a_post_is_picked_when_it_has_no_current_vector_and_only_then(conn):
    edited, embedded_after_edit, other_model, never_edited, no_vector = [
        post_id for (post_id,) in conn.execute(
            "SELECT post_id FROM post_embeddings JOIN posts p ON p.id = post_id WHERE p.deleted_at IS NULL "
            "ORDER BY post_id LIMIT 5")]
    conn.execute("UPDATE post_embeddings SET model = %s", (app.settings.embedding_model,))
    conn.execute("""UPDATE post_embeddings SET updated_at = now() - interval '1 hour' WHERE post_id = %s""", (edited,))
    conn.execute("UPDATE posts SET last_modified_at = now() WHERE id = %s", (edited,))
    conn.execute("UPDATE post_embeddings SET updated_at = now() WHERE post_id = %s", (embedded_after_edit,))
    conn.execute("UPDATE posts SET last_modified_at = now() - interval '1 hour' WHERE id = %s", (embedded_after_edit,))
    conn.execute("UPDATE post_embeddings SET model = 'another-model' WHERE post_id = %s", (other_model,))
    conn.execute("UPDATE posts SET last_modified_at = NULL WHERE id = %s", (never_edited,))
    conn.execute("DELETE FROM post_embeddings WHERE post_id = %s", (no_vector,))

    stale = picked(conn)

    assert {edited, other_model, no_vector} <= stale
    assert not ({embedded_after_edit, never_edited} & stale)


def test_find_posts_gives_the_live_posts_newest_first_up_to_the_limit(conn):
    live = conn.execute(
        f"SELECT p.id FROM posts p WHERE {app.NOT_DELETED} ORDER BY p.created_at DESC, p.id").fetchall()
    gone = live[0][0]
    conn.execute("UPDATE posts SET status = 'DELETED', deleted_at = now() WHERE id = %s", (gone,))

    everything = app.find_posts(conn, "true", ())
    newest_three = app.find_posts(conn, "true", (), 3)
    one = app.find_posts(conn, "p.id = %s", (str(live[1][0]),))

    assert [post_id for post_id, _ in everything] == [post_id for (post_id,) in live[1:]]
    assert newest_three == everything[:3]
    assert [post_id for post_id, _ in one] == [live[1][0]]
    assert app.find_posts(conn, "p.id = %s", (str(gone),)) == []


def test_kinds_are_stored_for_the_posts_that_exist_and_a_missing_post_does_not_fail_the_others(conn):
    (post_id,) = conn.execute(f"SELECT p.id FROM posts p WHERE {app.NOT_DELETED} LIMIT 1").fetchone()
    missing = "99999999-9999-9999-9999-999999999999"

    app.upsert_post_kinds(conn, [(missing, ["concerts"]), (post_id, ["stage"])])

    stored = dict(conn.execute("SELECT post_id::text, kinds FROM rec_post_kinds WHERE post_id IN (%s, %s)",
                               (str(post_id), missing)).fetchall())
    assert stored == {str(post_id): ["stage"]}
