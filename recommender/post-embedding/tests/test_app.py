import json
import sys
from unittest.mock import ANY, MagicMock, call
from uuid import UUID

import numpy as np
import pytest
from psycopg import errors

import app

POST_ID = "11111111-1111-1111-1111-111111111111"
POST_KEY = f"posts/{POST_ID}"
POST_ROW = ("Jazz night", "Live jazz", "Klub Żak", "Gdańsk")
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


@pytest.fixture
def model(monkeypatch):
    m = MagicMock()
    m.encode.return_value = np.array(EMBEDDING, dtype=np.float32)
    monkeypatch.setattr(app, "_get_model", lambda: m)
    return m


@pytest.fixture
def conn(monkeypatch):
    c = MagicMock()
    c.execute.return_value.fetchone.return_value = POST_ROW
    c.execute.return_value.fetchall.return_value = KIND_ROWS
    monkeypatch.setattr(app, "_get_connection", lambda: c)
    return c


def test_should_store_embedding_of_current_post_content_when_post_exists(model, conn):
    # given
    event = sqs_event(s3_body(POST_KEY))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == NO_FAILURES
    assert model.encode.call_args_list == [call("Jazz night Live jazz Klub Żak, Gdańsk", normalize_embeddings=True)]
    assert conn.execute.call_args_list == [
        call(ANY, (POST_ID,)),
        call(ANY, (POST_ID, EMBEDDING, MODEL)),
        call(ANY, (MODEL,)),
    ]
    assert conn.cursor.return_value.__enter__.return_value.executemany.call_args_list == [
        call(ANY, [(POST_ID, ["stage"], MODEL)]),
    ]


def test_should_store_no_kinds_when_a_kind_has_no_vector_yet(model, conn):
    # given
    conn.execute.return_value.fetchall.return_value = [("concerts", [1.0, 0.0, 0.0]), ("stage", None)]
    event = sqs_event(s3_body(POST_KEY))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == NO_FAILURES
    assert conn.cursor.call_args_list == []


def test_should_embed_title_only_when_post_has_no_description_or_place(model, conn):
    # given
    conn.execute.return_value.fetchone.return_value = ("Jazz night", None, None, None)
    event = sqs_event(s3_body(POST_KEY))

    # when
    app.lambda_handler(event, None)

    # then
    assert model.encode.call_args_list == [call("Jazz night", normalize_embeddings=True)]


@pytest.mark.parametrize("description, venue, city, text", [
    ("Live jazz", "Klub Żak", None, "Jazz night Live jazz Klub Żak"),
    ("Live jazz", None, "Gdańsk", "Jazz night Live jazz Gdańsk"),
    ("Live jazz", "", "Gdańsk", "Jazz night Live jazz Gdańsk"),
    ("Live jazz\n", None, None, "Jazz night Live jazz"),
])
def test_should_embed_only_the_parts_it_has(model, conn, description, venue, city, text):
    # given
    conn.execute.return_value.fetchone.return_value = ("Jazz night", description, venue, city)
    event = sqs_event(s3_body(POST_KEY))

    # when
    app.lambda_handler(event, None)

    # then
    assert model.encode.call_args_list == [call(text, normalize_embeddings=True)]


def test_should_skip_post_when_post_is_missing_or_deleted(model, conn):
    # given
    conn.execute.return_value.fetchone.return_value = None
    event = sqs_event(s3_body(POST_KEY))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == NO_FAILURES
    assert model.encode.call_args_list == []
    assert conn.execute.call_args_list == [call(ANY, (POST_ID,))]


def test_should_ignore_message_when_it_is_s3_test_event(model, conn):
    # given
    event = sqs_event(json.dumps({"Service": "Amazon S3", "Event": "s3:TestEvent", "Bucket": "b"}))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == NO_FAILURES
    assert conn.execute.call_args_list == []


def test_should_report_only_failed_record_when_db_write_fails_for_one_message(model, conn):
    # given
    rv = conn.execute.return_value
    conn.execute.side_effect = [rv, None, rv, rv, RuntimeError("db down")]
    event = sqs_event(s3_body(POST_KEY), s3_body(POST_KEY))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == failures("m1")


def test_should_report_every_record_as_failed_when_messages_are_malformed(model, conn):
    # given
    event = sqs_event("{not json", s3_body("posts/not-a-uuid"), s3_body(f"other/{POST_ID}"))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == failures("m0", "m1", "m2")
    assert conn.execute.call_args_list == []


def test_should_not_retry_when_post_was_hard_deleted_before_upsert(model, conn):
    # given
    conn.execute.side_effect = [conn.execute.return_value, errors.ForeignKeyViolation("post gone")]
    event = sqs_event(s3_body(POST_KEY))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == NO_FAILURES


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
    monkeypatch.setattr(app, "_get_connection", lambda: conn)

    result = app.lambda_handler({"action": "embed-interests"}, None)

    assert result == {"interests": 1, "kinds": 0}
    assert model.encode.call_args_list == [call("Jazz: koncerty", normalize_embeddings=True)]
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
    model.encode.return_value = np.array([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0]], dtype=np.float32)
    monkeypatch.setattr(app, "_get_connection", lambda: conn)

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
    monkeypatch.setattr(app, "_get_connection", lambda: conn)

    # when
    result = app.lambda_handler({"action": "derive-kinds"}, None)

    # then
    assert result == {"posts": 2}
    assert conn.cursor.return_value.__enter__.return_value.executemany.call_args_list == [
        call(ANY, [(POST_ID, ["concerts"], MODEL), (other, ["stage"], MODEL)]),
    ]
    delete = conn.execute.call_args_list[-1]
    assert "DELETE FROM rec_post_kinds" in delete.args[0]
    assert delete.args[1] == (MODEL,)


def test_should_fail_derive_kinds_when_kinds_have_no_vectors(monkeypatch):
    # given
    conn = MagicMock()
    conn.execute.return_value.fetchall.return_value = [("concerts", None)]
    monkeypatch.setattr(app, "_get_connection", lambda: conn)

    # when, then
    with pytest.raises(RuntimeError):
        app.lambda_handler({"action": "derive-kinds"}, None)


def time_left(*seconds: float) -> MagicMock:
    context = MagicMock()
    context.get_remaining_time_in_millis.side_effect = [s * 1000 for s in seconds]
    return context


def test_should_embed_posts_without_a_current_vector_and_their_kinds_until_none_are_left(model, conn):
    # given
    other = "22222222-2222-2222-2222-222222222222"
    conn.execute.return_value.fetchall.side_effect = [
        [(UUID(POST_ID), *POST_ROW), (UUID(other), "Stand-up", None, None, None)],
        KIND_ROWS,
        [],
    ]
    conn.execute.return_value.fetchone.return_value = (0,)
    model.encode.return_value = np.array([[1.0, 0.0, 0.0], [0.0, 0.0, 1.0]], dtype=np.float32)

    # when
    result = app.lambda_handler({"action": "embed-posts"}, time_left(120, 110, 100))

    # then
    assert result == {"posts": 2, "left": 0}
    assert model.encode.call_args_list == [
        call(["Jazz night Live jazz Klub Żak, Gdańsk", "Stand-up"], normalize_embeddings=True),
    ]
    assert conn.execute.call_args_list[1:3] == [
        call(ANY, (POST_ID, [1.0, 0.0, 0.0], MODEL)),
        call(ANY, (other, [0.0, 0.0, 1.0], MODEL)),
    ]
    assert conn.cursor.return_value.__enter__.return_value.executemany.call_args_list == [
        call(ANY, [(POST_ID, ["concerts"], MODEL), (other, ["stage"], MODEL)]),
    ]


def test_should_stop_before_the_timeout_and_say_how_many_posts_are_left(model, conn):
    # given
    conn.execute.return_value.fetchall.side_effect = [[(UUID(POST_ID), *POST_ROW)], KIND_ROWS]
    conn.execute.return_value.fetchone.return_value = (40,)
    model.encode.return_value = np.array([EMBEDDING], dtype=np.float32)

    # when
    result = app.lambda_handler({"action": "embed-posts"}, time_left(120, app.EMBED_POSTS_RESERVE_SECONDS))

    # then
    assert result == {"posts": 1, "left": 40}
    assert len(model.encode.call_args_list) == 1

