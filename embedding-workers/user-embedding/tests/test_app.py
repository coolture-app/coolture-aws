from unittest.mock import ANY, MagicMock, call

import pytest

import app

MODEL = "all-mpnet-base-v2"


def as_lists(vectors: dict) -> dict:
    return {user_id: vector.tolist() for user_id, vector in vectors.items()}


def connection_returning(rows):
    conn = MagicMock()
    cursor = conn.cursor.return_value.__enter__.return_value
    cursor.fetchall.return_value = rows
    return conn, cursor


def test_should_return_normalized_weighted_average_when_user_has_interactions():
    # given
    interactions = [
        ("u1", [1.0, 0.0], "LIKE"),
        ("u1", [0.0, 1.0], "TAKES_PART"),
    ]

    # when
    vectors = app.compute_user_vectors(interactions)

    # then
    assert as_lists(vectors) == {"u1": pytest.approx([1 / 5 ** 0.5, 2 / 5 ** 0.5], rel=1e-6)}


def test_should_skip_user_when_weights_cancel_out_or_interaction_types_are_unknown():
    # given
    interactions = [
        ("u1", [1.0, 0.0], "LIKE"),
        ("u1", [0.0, 1.0], "DISLIKE"),
        ("u1", [0.0, 1.0], "DISLIKE"),
        ("u2", [1.0, 1.0], "UNKNOWN"),
    ]

    # when
    vectors = app.compute_user_vectors(interactions)

    # then
    assert vectors == {}


def test_should_upsert_one_row_per_user_when_interactions_exist():
    # given
    conn, cursor = connection_returning([
        ("u1", [1.0, 0.0], "LIKE"),
        ("u2", [0.0, 1.0], "INTERESTED"),
    ])

    # when
    upserted = app.run_once(conn)

    # then
    assert upserted == 2
    assert cursor.executemany.call_args_list == [
        call(ANY, [("u1", "[1.0,0.0]", MODEL), ("u2", "[0.0,1.0]", MODEL)]),
    ]
    assert conn.transaction.call_count == 1


def test_should_write_nothing_when_there_are_no_interactions():
    # given
    conn, cursor = connection_returning([])

    # when
    upserted = app.run_once(conn)

    # then
    assert upserted == 0
    assert cursor.executemany.call_args_list == []


def test_should_return_upserted_count_when_invoked_by_schedule(monkeypatch):
    # given
    conn, _ = connection_returning([("u1", [1.0, 0.0], "LIKE")])
    monkeypatch.setattr(app, "_get_connection", lambda: conn)

    # when
    result = app.lambda_handler({}, None)

    # then
    assert result == {"upserted": 1}
