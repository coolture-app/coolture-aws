import json
import sys
from types import SimpleNamespace
from unittest.mock import ANY, MagicMock, call
from uuid import UUID

import numpy as np
import pytest
from psycopg import errors

import app
from models import PostContent

POST_ID = "11111111-1111-1111-1111-111111111111"
POST_KEY = f"posts/{POST_ID}"
POST = PostContent(title="Jazz night", description="Live jazz", venue_name="Klub Żak", city="Gdańsk")
EMBEDDING = [0.5, 0.25, 1.0]
MODEL = app.settings.embedding_model
NO_FAILURES = {"batchItemFailures": []}
KIND_ROWS = [("concerts", [1.0, 0.0, 0.0]), ("stage", [0.0, 0.0, 1.0])]


def s3_body(*keys: str) -> str:
    return json.dumps({
        "Records": [
            {
                "eventSource": "aws:s3",
                "eventName": "ObjectCreated:Put",
                "s3": {"bucket": {"name": "coolture-dev-embedding-requests"}, "object": {"key": key, "size": 0}},
            }
            for key in keys
        ]
    })


def sqs_event(*bodies: str) -> dict:
    return {"Records": [{"messageId": f"m{i}", "body": body} for i, body in enumerate(bodies)]}


def failures(*message_ids: str) -> dict:
    return {"batchItemFailures": [{"itemIdentifier": message_id} for message_id in message_ids]}


def opened(conn: MagicMock) -> MagicMock:
    """What `with _connect() as conn` gives back is the connection itself, as psycopg's does."""
    conn.__enter__.return_value = conn
    return conn


@pytest.fixture
def model(monkeypatch):
    m = MagicMock()
    m.encode.side_effect = lambda texts, **_: np.array([EMBEDDING] * len(texts), dtype=np.float32)
    monkeypatch.setattr(app, "_get_model", lambda: m)
    return m


@pytest.fixture
def db(monkeypatch):
    """Stands in for the database: what find_* answer is set on it, what upsert_* store is read from it."""
    db = SimpleNamespace(
        conn=MagicMock(),
        posts=[(UUID(POST_ID), POST)],
        kind_vectors=dict(KIND_ROWS),
        vectors=[],
        kinds=[],
    )
    monkeypatch.setattr(app, "_connect", lambda: opened(db.conn))
    monkeypatch.setattr(app, "find_posts", lambda conn, where, params, limit=None: db.posts)
    monkeypatch.setattr(app, "find_kind_vectors", lambda conn: db.kind_vectors)
    monkeypatch.setattr(app, "upsert_embedding", lambda conn, post_id, vector: db.vectors.append((post_id, vector)))
    monkeypatch.setattr(app, "upsert_post_kinds", lambda conn, rows: db.kinds.extend(rows))
    return db


def test_should_store_embedding_and_kinds_of_current_post_content_when_post_exists(model, db):
    # when
    result = app.lambda_handler(sqs_event(s3_body(POST_KEY)), None)

    # then
    assert result == NO_FAILURES
    assert model.encode.call_args_list == [call(["Jazz night Live jazz Klub Żak, Gdańsk"], normalize_embeddings=True)]
    assert db.vectors == [(UUID(POST_ID), EMBEDDING)]
    assert db.kinds == [(UUID(POST_ID), ["stage"])]


def test_should_store_no_kinds_when_a_kind_has_no_vector_yet(model, db):
    # given
    db.kind_vectors = None

    # when
    result = app.lambda_handler(sqs_event(s3_body(POST_KEY)), None)

    # then
    assert result == NO_FAILURES
    assert db.vectors == [(UUID(POST_ID), EMBEDDING)]
    assert db.kinds == []


@pytest.mark.parametrize("description, venue, city, text", [
    (None, None, None, "Jazz night"),
    ("Live jazz", "Klub Żak", "Gdańsk", "Jazz night Live jazz Klub Żak, Gdańsk"),
    ("Live jazz", "Klub Żak", None, "Jazz night Live jazz Klub Żak"),
    ("Live jazz", None, "Gdańsk", "Jazz night Live jazz Gdańsk"),
    ("Live jazz", "", "Gdańsk", "Jazz night Live jazz Gdańsk"),
    ("Live jazz\n", None, None, "Jazz night Live jazz"),
])
def test_should_embed_only_the_parts_a_post_has(description, venue, city, text):
    assert PostContent(title="Jazz night", description=description, venue_name=venue, city=city).text == text


def test_should_skip_post_when_post_is_missing_or_deleted(model, db):
    # given
    db.posts = []

    # when
    result = app.lambda_handler(sqs_event(s3_body(POST_KEY)), None)

    # then
    assert result == NO_FAILURES
    assert model.encode.call_args_list == []
    assert db.vectors == []


def test_should_ignore_message_when_it_is_s3_test_event(model, db):
    # given
    event = sqs_event(json.dumps({"Service": "Amazon S3", "Event": "s3:TestEvent", "Bucket": "b"}))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == NO_FAILURES
    assert model.encode.call_args_list == []


def test_should_report_only_failed_record_when_db_write_fails_for_one_message(model, db, monkeypatch):
    # given
    writes = iter([None, RuntimeError("db down")])

    def write(conn, post_id, vector):
        if (outcome := next(writes)) is not None:
            raise outcome

    monkeypatch.setattr(app, "upsert_embedding", write)

    # when
    result = app.lambda_handler(sqs_event(s3_body(POST_KEY), s3_body(POST_KEY)), None)

    # then
    assert result == failures("m1")


def test_should_report_every_record_as_failed_when_messages_are_malformed(model, db):
    # given
    event = sqs_event("{not json", s3_body("posts/not-a-uuid"), s3_body(f"other/{POST_ID}"))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == failures("m0", "m1", "m2")
    assert model.encode.call_args_list == []


def test_should_not_retry_and_derive_no_kinds_when_post_was_hard_deleted_before_upsert(model, db, monkeypatch):
    # given
    def gone(conn, post_id, vector):
        raise errors.ForeignKeyViolation("post gone")

    monkeypatch.setattr(app, "upsert_embedding", gone)

    # when
    result = app.lambda_handler(sqs_event(s3_body(POST_KEY)), None)

    # then
    assert result == NO_FAILURES
    assert db.kinds == []


def test_should_not_retry_when_post_was_hard_deleted_before_its_kinds_were_stored(model, db, monkeypatch):
    # given
    def gone(conn, rows):
        raise errors.ForeignKeyViolation("post gone")

    monkeypatch.setattr(app, "upsert_post_kinds", gone)

    # when, then
    assert app.lambda_handler(sqs_event(s3_body(POST_KEY)), None) == NO_FAILURES


def test_should_return_post_ids_when_s3_event_contains_post_keys():
    # given
    body = s3_body(POST_KEY, "posts/22222222-2222-2222-2222-222222222222")

    # when
    post_ids = app.post_ids_from_s3_event(body)

    # then
    assert post_ids == [UUID(POST_ID), UUID("22222222-2222-2222-2222-222222222222")]


def test_should_use_password_when_iam_auth_is_disabled(monkeypatch):
    # given
    monkeypatch.setattr(app.settings, "db_iam_auth", False)
    monkeypatch.setattr(app.settings, "db_password", "local-secret")

    # when
    credentials = app._db_credentials()

    # then
    assert credentials == {"password": "local-secret"}


def test_should_sign_iam_token_and_require_ssl_when_iam_auth_is_enabled(monkeypatch):
    # given
    rds = MagicMock()
    rds.generate_db_auth_token.return_value = "signed-token"
    boto3 = MagicMock()
    boto3.client.return_value = rds
    monkeypatch.setitem(sys.modules, "boto3", boto3)
    monkeypatch.setattr(app.settings, "db_iam_auth", True)
    monkeypatch.setattr(app.settings, "db_host", "cluster.example.com")
    monkeypatch.setattr(app.settings, "db_port", 5432)
    monkeypatch.setattr(app.settings, "db_username", "coolture_app")

    # when
    credentials = app._db_credentials()

    # then
    assert (credentials, boto3.client.call_args_list, rds.generate_db_auth_token.call_args_list) == (
        {"password": "signed-token", "sslmode": "require"},
        [call("rds", region_name=app.settings.aws_region)],
        [call(DBHostname="cluster.example.com", Port=5432, DBUsername="coolture_app")],
    )


def test_should_embed_only_interests_returned_when_invoked_with_embed_interests(model, monkeypatch):
    conn = MagicMock()
    conn.execute.return_value.fetchall.side_effect = [[("jazz", "Jazz: koncerty")], []]
    monkeypatch.setattr(app, "_connect", lambda: opened(conn))

    result = app.lambda_handler({"action": "embed-interests"}, None)

    assert result == {"interests": 1, "kinds": 0}
    assert model.encode.call_args_list == [call(["Jazz: koncerty"], normalize_embeddings=True)]
    assert conn.execute.call_args_list[1] == call(ANY, ("jazz", EMBEDDING, MODEL, "Jazz: koncerty"))


def test_should_use_default_database_address_when_it_is_empty(monkeypatch):
    monkeypatch.setenv("DB_HOST", "")
    monkeypatch.setenv("DB_PORT", "")

    settings = type(app.settings)()

    assert (settings.db_host, settings.db_port) == ("host.docker.internal", 5430)


def test_should_embed_changed_kinds_as_their_texts_mean(model, monkeypatch):
    # given
    conn = MagicMock()
    texts = ["Koncert w klubie", "Recital"]
    conn.execute.return_value.fetchall.side_effect = [[], [("concerts", texts)]]
    model.encode.side_effect = None
    model.encode.return_value = np.array([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]], dtype=np.float32)
    monkeypatch.setattr(app, "_connect", lambda: opened(conn))

    # when
    result = app.lambda_handler({"action": "embed-interests"}, None)

    # then
    assert result == {"interests": 0, "kinds": 1}
    assert model.encode.call_args_list == [call(texts, normalize_embeddings=True)]
    stored = conn.execute.call_args_list[2].args[1]
    assert (stored[0], np.round(stored[1], 6).tolist(), stored[2:]) == ("concerts", [0.707107, 0.707107, 0.0], (MODEL, texts))


def test_should_rederive_every_post_and_drop_other_models_kinds_when_invoked_with_derive_kinds(monkeypatch):
    # given
    conn = MagicMock()
    other = "22222222-2222-2222-2222-222222222222"
    conn.execute.return_value.fetchall.side_effect = [KIND_ROWS, [(POST_ID, [1.0, 0.0, 0.0]), (other, [0.0, 0.0, 1.0])]]
    monkeypatch.setattr(app, "_connect", lambda: opened(conn))

    # when
    result = app.lambda_handler({"action": "derive-kinds"}, None)

    # then
    assert result == {"posts": 2}
    assert conn.cursor.return_value.__enter__.return_value.executemany.call_args_list == [
        call(ANY, [(["concerts"], MODEL, POST_ID), (["stage"], MODEL, other)]),
    ]
    delete = conn.execute.call_args_list[-1]
    assert "DELETE FROM rec_post_kinds" in delete.args[0]
    assert delete.args[1] == (MODEL,)


def test_should_fail_derive_kinds_when_kinds_have_no_vectors(monkeypatch):
    # given
    conn = MagicMock()
    conn.execute.return_value.fetchall.return_value = [("concerts", None)]
    monkeypatch.setattr(app, "_connect", lambda: opened(conn))

    # when, then
    with pytest.raises(RuntimeError):
        app.lambda_handler({"action": "derive-kinds"}, None)


def time_left(*seconds: float) -> MagicMock:
    context = MagicMock()
    context.get_remaining_time_in_millis.side_effect = [s * 1000 for s in seconds]
    return context


def test_should_embed_posts_without_a_current_vector_and_their_kinds_until_none_are_left(model, db, monkeypatch):
    # given
    other = UUID("22222222-2222-2222-2222-222222222222")
    batches = iter([[*db.posts, (other, PostContent(title="Stand-up"))], []])
    monkeypatch.setattr(app, "find_posts", lambda conn, where, params, limit=None: next(batches))
    model.encode.side_effect = lambda texts, **_: np.array([[1.0, 0.0, 0.0], [0.0, 0.0, 1.0]], dtype=np.float32)
    db.conn.execute.return_value.fetchone.return_value = (0,)

    # when
    result = app.lambda_handler({"action": "embed-posts"}, time_left(120, 110, 100))

    # then
    assert result == {"posts": 2, "left": 0}
    assert model.encode.call_args_list == [
        call(["Jazz night Live jazz Klub Żak, Gdańsk", "Stand-up"], normalize_embeddings=True),
    ]
    assert db.vectors == [(UUID(POST_ID), [1.0, 0.0, 0.0]), (other, [0.0, 0.0, 1.0])]
    assert db.kinds == [(UUID(POST_ID), ["concerts"]), (other, ["stage"])]


def test_should_stop_before_the_timeout_and_say_how_many_posts_are_left(model, db):
    # given
    db.conn.execute.return_value.fetchone.return_value = (40,)

    # when
    result = app.lambda_handler({"action": "embed-posts"}, time_left(120, app.EMBED_POSTS_RESERVE_SECONDS))

    # then
    assert result == {"posts": 1, "left": 40}
    assert len(model.encode.call_args_list) == 1


@pytest.fixture
def steps(monkeypatch):
    """Records which of reconcile's steps ran, in order. Each answers what `answers` holds when it runs."""
    ran = []
    answers = {"embed_interests": 2, "embed_kinds": 0, "derive_all_kinds": 30, "derive_missing_kinds": 1}

    def step(name):
        def run(conn):
            ran.append(name)
            return answers[name]
        return run

    def embed_pending_posts(conn, seconds_left):
        ran.append(f"embed_pending_posts with {seconds_left():.0f} s left")
        return {"posts": 3, "left": 0}

    monkeypatch.setattr(app, "_connect", lambda: opened(MagicMock()))
    for name in answers:
        monkeypatch.setattr(app, name, step(name))
    monkeypatch.setattr(app, "embed_pending_posts", embed_pending_posts)
    return SimpleNamespace(ran=ran, answers=answers)


def test_should_derive_only_missing_kinds_and_embed_pending_posts_when_reconciling_and_no_kind_changed(steps):
    # when
    result = app.lambda_handler({"action": "reconcile"}, time_left(100))

    # then
    assert result == {"interests": 2, "kinds": 0, "derived": 1, "posts": 3, "left": 0}
    assert steps.ran == [
        "embed_interests", "embed_kinds", "derive_missing_kinds", "embed_pending_posts with 100 s left",
    ]


def test_should_derive_every_posts_kinds_when_reconciling_embedded_a_changed_kind(steps):
    # given
    steps.answers["embed_kinds"] = 15

    # when
    result = app.lambda_handler({"action": "reconcile"}, time_left(100))

    # then
    assert result == {"interests": 2, "kinds": 15, "derived": 30, "posts": 3, "left": 0}
    assert steps.ran == ["embed_interests", "embed_kinds", "derive_all_kinds", "embed_pending_posts with 100 s left"]


def test_should_derive_missing_kinds_from_stored_vectors(monkeypatch):
    # given
    conn = MagicMock()
    conn.execute.return_value.fetchall.side_effect = [KIND_ROWS, [(POST_ID, [0.0, 0.0, 1.0])]]
    monkeypatch.setattr(app, "_connect", lambda: opened(conn))

    # when
    derived = app.derive_missing_kinds(conn)

    # then
    assert derived == 1
    assert conn.cursor.return_value.__enter__.return_value.executemany.call_args_list == [
        call(ANY, [(["stage"], MODEL, POST_ID)]),
    ]
    assert "NOT EXISTS" in conn.execute.call_args_list[1].args[0]
    assert not any("DELETE" in c.args[0] for c in conn.execute.call_args_list)


def test_should_open_one_connection_per_invocation_and_close_it(model, db):
    # when
    app.lambda_handler(sqs_event(s3_body(POST_KEY), s3_body(POST_KEY)), None)

    # then
    assert (db.conn.__enter__.call_count, db.conn.__exit__.call_count) == (1, 1)


def test_should_close_the_connection_when_the_action_fails(monkeypatch):
    # given
    conn = MagicMock()
    conn.execute.return_value.fetchall.return_value = [("concerts", None)]
    monkeypatch.setattr(app, "_connect", lambda: opened(conn))

    # when, then
    with pytest.raises(RuntimeError):
        app.lambda_handler({"action": "derive-kinds"}, None)
    assert conn.__exit__.call_count == 1


def test_should_fail_without_connecting_when_the_action_is_unknown(monkeypatch):
    # given
    def never():
        raise AssertionError("connected")

    monkeypatch.setattr(app, "_connect", never)

    # when, then
    with pytest.raises(ValueError, match="embed_posts"):
        app.lambda_handler({"action": "embed_posts"}, None)


def test_should_fail_when_the_texts_of_a_kind_have_no_direction(model, monkeypatch):
    # given
    conn = MagicMock()
    conn.execute.return_value.fetchall.return_value = [("concerts", ["a", "b"])]
    model.encode.side_effect = None
    model.encode.return_value = np.array([[1.0, 0.0], [-1.0, 0.0]], dtype=np.float32)

    # when, then
    with pytest.raises(ValueError, match="concerts"):
        app.embed_kinds(conn)
