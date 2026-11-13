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


def test_a_run_scores_only_what_the_feed_may_show(conn):
    inputs = app.load(conn)
    assert inputs is not None, "seed the database first"
    run, rows = app.write(conn, inputs)

    assert rows > 0
    assert conn.execute("SELECT finished_at IS NOT NULL FROM rec_graph_runs WHERE run_number = %s", (run,)).fetchone()[0]
    shown_wrongly = conn.execute(
        f"""SELECT count(*) FROM rec_graph_scores g JOIN posts p ON p.id = g.post_id
            WHERE g.run_number = %s AND (p.deleted_at IS NOT NULL OR {app.EVENT_END} < now() OR p.author_id = g.user_id
              OR EXISTS (SELECT 1 FROM post_reactions r WHERE r.post_id = p.id AND r.user_id = g.user_id)
              OR EXISTS (SELECT 1 FROM post_participations r WHERE r.post_id = p.id AND r.user_id = g.user_id)
              OR NOT (p.visibility = 'PUBLIC' OR (p.visibility = 'FRIENDS' AND EXISTS (SELECT 1 FROM user_relations f
                      WHERE f.source_user_id = g.user_id AND f.target_user_id = p.author_id AND f.type = 'FOLLOW')))
              OR EXISTS (SELECT 1 FROM user_relations b WHERE b.type = 'BLOCK'
                         AND ((b.source_user_id = g.user_id AND b.target_user_id = p.author_id)
                           OR (b.source_user_id = p.author_id AND b.target_user_id = g.user_id))))""",
        (run,)).fetchone()[0]
    assert shown_wrongly == 0


def test_a_run_keeps_the_newest_run_finished_over_a_day_ago_and_deletes_older_ones(conn):
    def run_finished(ago):
        return conn.execute("INSERT INTO rec_graph_runs (finished_at) VALUES (now() - %s::interval) RETURNING run_number",
                            (ago,)).fetchone()[0]

    conn.execute("DELETE FROM rec_graph_runs")
    oldest, day_old, recent = run_finished("3 days"), run_finished("2 days"), run_finished("1 hour")
    new, _ = app.write(conn, app.load(conn))

    kept = {r for (r,) in conn.execute("SELECT run_number FROM rec_graph_runs WHERE run_number >= %s", (oldest,))}
    assert kept == {day_old, recent, new}


def test_the_job_and_the_feed_have_their_grants(conn):
    granted = conn.execute(
        """SELECT has_table_privilege('coolture_app', 'rec_graph_scores', 'INSERT'),
                  has_table_privilege('coolture_app', 'rec_graph_runs', 'INSERT, UPDATE, DELETE'),
                  has_table_privilege('coolture_reader', 'rec_graph_scores', 'SELECT'),
                  has_table_privilege('coolture_reader', 'rec_graph_runs', 'SELECT')""").fetchone()
    assert all(granted)
