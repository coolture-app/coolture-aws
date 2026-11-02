import json
import os

import psycopg
import pytest

from graph_gateway import scores_message
from worker import GraphScores, apply_scores, handler


@pytest.fixture
def conn():
    with psycopg.connect(os.environ['DATABASE_URL']) as connection:
        connection.execute('DELETE FROM rec_graph_scores')
        connection.commit()
        yield connection
        connection.execute('DELETE FROM rec_graph_scores')
        connection.commit()


def stored(conn) -> dict[str, float]:
    return dict(conn.execute('SELECT node_id, score FROM rec_graph_scores').fetchall())


def test_gateway_message_reaches_the_table_through_a_queue_event(conn):
    body = scores_message(1, {'a': 0.5, 'b': 1.5})

    handler({'Records': [{'messageId': '1', 'body': body}]}, None)

    assert stored(conn) == {'a': 0.5, 'b': 1.5}


def test_new_run_replaces_the_whole_snapshot(conn):
    apply_scores(conn, GraphScores(runNumber=1, scores={'a': 1.0, 'b': 2.0}))

    assert apply_scores(conn, GraphScores(runNumber=2, scores={'a': 3.0})) is True
    assert stored(conn) == {'a': 3.0}


def test_repeated_or_late_run_changes_nothing(conn):
    apply_scores(conn, GraphScores(runNumber=5, scores={'a': 1.0}))

    assert apply_scores(conn, GraphScores(runNumber=5, scores={'a': 9.0})) is False
    assert apply_scores(conn, GraphScores(runNumber=4, scores={'a': 9.0})) is False
    assert stored(conn) == {'a': 1.0}


def test_message_failing_the_schema_raises_so_it_stays_on_the_queue(conn):
    with pytest.raises(ValueError):
        handler({'Records': [{'messageId': '1', 'body': json.dumps({'scores': {}})}]}, None)
