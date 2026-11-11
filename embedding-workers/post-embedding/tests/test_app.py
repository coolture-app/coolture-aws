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
POST_ROW = ("Jazz night", "Live jazz", ["music", "jazz"])
EMBEDDING = [0.5, 0.25, 1.0]
NO_FAILURES = {"batchItemFailures": []}


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
    monkeypatch.setattr(app, "_get_connection", lambda: c)
    return c


def test_should_store_embedding_of_current_post_content_when_post_exists(model, conn):
    # given
    event = sqs_event(s3_body(POST_KEY))

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == NO_FAILURES
    assert model.encode.call_args_list == [call("Jazz night Live jazz music jazz")]
    assert conn.execute.call_args_list == [
        call(ANY, (POST_ID,)),
        call(ANY, (POST_ID, EMBEDDING, "all-mpnet-base-v2")),
    ]


def test_should_embed_title_only_when_description_and_tags_are_null(model, conn):
    # given
    conn.execute.return_value.fetchone.return_value = ("Jazz night", None, None)
    event = sqs_event(s3_body(POST_KEY))

    # when
    app.lambda_handler(event, None)

    # then
    assert model.encode.call_args_list == [call("Jazz night")]


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
    conn.execute.side_effect = [conn.execute.return_value, None, conn.execute.return_value, RuntimeError("db down")]
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
