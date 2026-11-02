import json
import sys
from unittest.mock import ANY, MagicMock, call
from uuid import UUID

import numpy as np
import pytest

import app

MODEL = "sdadas/mmlw-roberta-base"
STARTED_AT = "2026-11-01T10:00:00+00:00"
A = [1.0, 0.0]
B = [0.0, 1.0]
USER_ID = UUID("11111111-1111-1111-1111-111111111111")
INTERESTS = [("jazz", "music", A), ("rock", "music", B), ("running", "sports", B)]


def as_lists(vectors: dict) -> dict:
    return {user_id: vector.tolist() for user_id, vector in vectors.items()}


def weighted(*rows) -> dict:
    return as_lists(app.weighted_vectors(
        (user_id, np.array(vector), weight) for user_id, vector, weight in rows))


def connection_returning(interactions=(), selections=()):
    conn = MagicMock()
    cursor = conn.cursor.return_value.__enter__.return_value
    cursor.execute.return_value.fetchone.return_value = (STARTED_AT,)
    cursor.__iter__.side_effect = [iter(interactions), iter(INTERESTS), iter(selections)]
    return conn, cursor


def sqs_event(*keys: str) -> dict:
    body = {"Records": [{"s3": {"object": {"key": key}}} for key in keys]}
    return {"Records": [{"messageId": "m0", "body": json.dumps(body)}]}


def run_once_recorder(monkeypatch) -> list:
    calls = []
    monkeypatch.setattr(app, "_get_connection", lambda: "conn")
    monkeypatch.setattr(app, "run_once", lambda conn, user_ids=None: calls.append(user_ids) or 1)
    return calls


def stored_rows(cursor) -> list:
    return cursor.executemany.call_args.args[1]


def test_should_return_normalized_weighted_average_when_user_has_interactions():
    vectors = weighted(("u1", A, 1.0), ("u1", B, 2.0))

    assert vectors == {"u1": pytest.approx([1 / 5 ** 0.5, 2 / 5 ** 0.5], rel=1e-6)}


def test_should_point_away_from_post_when_user_only_disliked_it():
    # when
    vectors = weighted(("u1", A, -0.5))

    # then
    assert vectors == {"u1": pytest.approx([-1.0, 0.0])}


def test_should_stay_closer_to_liked_post_when_dislikes_outweigh_it():
    vectors = weighted(("u1", A, 1.0), ("u1", B, -0.5), ("u1", B, -0.5), ("u1", B, -0.5))

    assert vectors["u1"][0] > 0 > vectors["u1"][1]


def test_should_skip_user_when_vectors_cancel_out_or_weight_is_zero():
    # when
    vectors = weighted(("u1", A, 1.0), ("u1", A, -1.0), ("u2", B, 0.0))

    # then
    assert vectors == {}


def test_should_upsert_one_row_per_user_when_interactions_exist():
    # given
    conn, cursor = connection_returning(interactions=[
        ("u1", A, "LIKE", True),
        ("u2", B, "INTERESTED", True),
    ])

    # when
    upserted = app.run_once(conn)

    # then
    assert upserted == 2
    assert cursor.executemany.call_args_list == [
        call(ANY, [("u1", "[1.0,0.0]", MODEL, STARTED_AT), ("u2", "[0.0,1.0]", MODEL, STARTED_AT)]),
    ]
    assert conn.transaction.call_count == 1


def test_should_write_nothing_when_there_are_no_interactions():
    # given
    conn, cursor = connection_returning()

    # when
    upserted = app.run_once(conn)

    # then
    assert upserted == 0
    assert cursor.executemany.call_args_list == []


def test_should_return_upserted_count_when_invoked_by_schedule(monkeypatch):
    # given
    conn, _ = connection_returning(interactions=[("u1", A, "LIKE", True)])
    monkeypatch.setattr(app, "_get_connection", lambda: conn)

    # when
    result = app.lambda_handler({}, None)

    # then
    assert result == {"upserted": 1}


def test_should_read_only_the_requested_users():
    conn, cursor = connection_returning()

    app.run_once(conn, [USER_ID])

    params = [c.args[1] for c in cursor.execute.call_args_list if len(c.args) > 1]
    assert [p["users"] for p in params] == [[USER_ID], [USER_ID]]


def test_should_read_every_user_when_invoked_by_schedule(monkeypatch):
    calls = run_once_recorder(monkeypatch)

    app.lambda_handler({}, None)

    assert calls == [None]


def test_should_refresh_users_named_in_sqs_messages(monkeypatch):
    calls = run_once_recorder(monkeypatch)

    result = app.lambda_handler(sqs_event(f"users/{USER_ID}"), None)

    assert (result, calls) == ({"upserted": 1}, [[USER_ID]])


def test_should_skip_s3_test_event(monkeypatch):
    calls = run_once_recorder(monkeypatch)
    event = {"Records": [{"messageId": "m0", "body": json.dumps({"Event": "s3:TestEvent"})}]}

    result = app.lambda_handler(event, None)

    assert (result, calls) == ({"upserted": 0}, [])


def test_should_reject_key_outside_users_prefix(monkeypatch):
    run_once_recorder(monkeypatch)

    with pytest.raises(ValueError):
        app.lambda_handler(sqs_event(f"posts/{USER_ID}"), None)


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


def test_should_add_the_category_average_to_each_pick_at_a_lower_weight():
    conn, cursor = connection_returning(selections=[("u1", "jazz")])

    app.run_once(conn)

    music = np.array([1.0, 1.0]) / 2 ** 0.5
    expected = np.array(A) + 0.25 * music
    stored = np.fromstring(stored_rows(cursor)[0][1][1:-1], sep=",")
    assert stored == pytest.approx(expected / np.linalg.norm(expected), rel=1e-6)


def test_should_ignore_selection_when_interest_has_no_embedding():
    conn, cursor = connection_returning(selections=[("u1", "not-embedded-yet")])

    upserted = app.run_once(conn)

    assert upserted == 0


def test_should_use_interests_alone_when_user_has_no_interactions():
    conn, cursor = connection_returning(selections=[("u1", "running")])

    upserted = app.run_once(conn)

    assert upserted == 1
    assert stored_rows(cursor) == [("u1", "[0.0,1.0]", MODEL, STARTED_AT)]


def test_should_keep_interactions_alone_when_user_has_no_interests():
    vectors = app.blend_user_vectors({"u1": np.array(A)}, {}, {"u1": 50.0})

    assert as_lists(vectors) == {"u1": A}


def test_should_move_towards_interactions_as_engagement_grows():
    behaviour = {"u1": np.array(A)}
    interests = {"u1": np.array(B)}

    new_user = app.blend_user_vectors(behaviour, interests, {})["u1"]
    active_user = app.blend_user_vectors(behaviour, interests, {"u1": 100.0})["u1"]

    assert new_user.tolist() == pytest.approx(B)
    assert active_user[0] > active_user[1] > 0


def test_should_count_only_recent_interactions_as_engagement():
    conn, cursor = connection_returning(
        interactions=[
            ("recent", A, "DISLIKE", True),
            ("recent", A, "LIKE", True),
            ("old", A, "LIKE", False),
        ],
        selections=[("recent", "running"), ("old", "running")],
    )

    app.run_once(conn)

    vectors = {row[0]: np.fromstring(row[1][1:-1], sep=",") for row in stored_rows(cursor)}
    assert vectors["old"][1] == pytest.approx(1.0)
    assert vectors["recent"][1] < 1.0


def test_should_not_fall_below_minimum_interest_share():
    assert app.interest_share(0.0) == 1.0
    assert app.interest_share(10.0) == pytest.approx(0.3679, rel=1e-3)
    assert app.interest_share(1000.0) == 0.2


def test_should_use_default_database_address_when_it_is_empty(monkeypatch):
    monkeypatch.setenv("DB_HOST", "")
    monkeypatch.setenv("DB_PORT", "")

    settings = type(app.settings)()

    assert (settings.db_host, settings.db_port) == ("host.docker.internal", 5430)
