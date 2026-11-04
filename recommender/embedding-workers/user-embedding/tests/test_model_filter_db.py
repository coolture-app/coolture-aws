"""Runs the real queries on the migrated database, which a mocked cursor can't. Needs `make up`, and runs with `DB_TEST=true make test-python`.

Every test stays in one transaction that is rolled back, so the database keeps what it held.
"""
import os

import psycopg
import pytest

import app

pytestmark = pytest.mark.skipif(os.environ.get("DB_TEST") != "true", reason="needs make up and DB_TEST=true")

USER = "b0000000-0000-0000-0000-000000000001"
CURRENT = app.settings.embedding_model
OTHER = "other-model"

# Each input points along its own axis, so the stored vector shows which inputs reached it.
CURRENT_POST, OTHER_POST, CURRENT_INTEREST, OTHER_INTEREST = range(4)


def axis(index: int) -> str:
    return "[" + ",".join("1" if i == index else "0" for i in range(768)) + "]"


@pytest.fixture
def conn():
    with psycopg.connect(host="localhost", port=5430, dbname="coolture", user="coolture_admin", password="admin") as connection:
        connection.execute("DELETE FROM interest_embeddings")
        connection.execute("INSERT INTO users (id, username) VALUES (%s, 'model_filter_test')", (USER,))
        yield connection
        connection.rollback()


def like_post(conn, index: int, model: str) -> None:
    post_id = conn.execute(
        "INSERT INTO posts (author_id, title, starts_at, type) VALUES (%s, 'model_filter_test', now(), 'ONLINE') RETURNING id",
        (USER,),
    ).fetchone()[0]
    conn.execute("INSERT INTO post_embeddings (post_id, embedding, model) VALUES (%s, %s, %s)", (post_id, axis(index), model))
    conn.execute("INSERT INTO post_reactions (post_id, user_id, type) VALUES (%s, %s, 'LIKE')", (post_id, USER))


def pick_interest(conn, key: str, index: int, model: str) -> None:
    conn.execute(
        "INSERT INTO interest_embeddings (interest_key, embedding, model, embedded_text) VALUES (%s, %s, %s, 'x')",
        (key, axis(index), model),
    )
    conn.execute("INSERT INTO user_interests (user_id, interest_key) VALUES (%s, %s)", (USER, key))


def test_should_blend_only_vectors_of_the_current_model(conn):
    like_post(conn, CURRENT_POST, CURRENT)
    like_post(conn, OTHER_POST, OTHER)
    pick_interest(conn, "jazz", CURRENT_INTEREST, CURRENT)
    pick_interest(conn, "rock", OTHER_INTEREST, OTHER)

    assert app.run_once(conn, [USER]) == 1

    embedding, model = conn.execute("SELECT embedding::real[], model FROM user_embeddings WHERE user_id = %s", (USER,)).fetchone()
    assert model == CURRENT
    assert embedding[CURRENT_POST] > 0 and embedding[CURRENT_INTEREST] > 0
    assert embedding[OTHER_POST] == 0 and embedding[OTHER_INTEREST] == 0


def test_should_write_nothing_when_every_input_is_from_another_model(conn):
    like_post(conn, OTHER_POST, OTHER)
    pick_interest(conn, "rock", OTHER_INTEREST, OTHER)

    assert app.run_once(conn, [USER]) == 0
