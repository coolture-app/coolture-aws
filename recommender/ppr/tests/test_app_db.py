import os
from datetime import timedelta

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


def users(conn, count):
    return [u for (u,) in conn.execute("SELECT user_id FROM user_interests GROUP BY user_id ORDER BY user_id LIMIT %s",
                                       (count,))]


def relate(conn, source, target, kind):
    conn.execute("""INSERT INTO user_relations (source_user_id, target_user_id, type) VALUES (%s, %s, %s)
                    ON CONFLICT (source_user_id, target_user_id) DO UPDATE SET type = EXCLUDED.type""",
                 (source, target, kind))


def ranked(inputs):
    return {p for p, not_ended in zip(inputs.event_ids, inputs.not_ended) if not_ended}


def test_a_post_with_no_end_date_that_started_long_ago_is_ranked(conn):
    (standing,) = conn.execute(
        """SELECT p.id FROM posts p JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
           WHERE p.deleted_at IS NULL ORDER BY p.id LIMIT 1""", (app.EMBEDDING_MODEL,)).fetchone()
    conn.execute("UPDATE posts SET starts_at = now() - interval '200 days', ends_at = NULL, open_ended = true "
                 "WHERE id = %s", (standing,))

    assert standing in ranked(app.load(conn))


def test_a_run_ranks_every_event_that_hasnt_ended_and_nothing_else(conn):
    user, blocked = users(conn, 2)
    upcoming, deleted, ended, own, by_blocked, reacted = [p for (p,) in conn.execute(
        """SELECT p.id FROM posts p JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
           WHERE p.deleted_at IS NULL ORDER BY p.id LIMIT 6""", (app.EMBEDDING_MODEL,))]
    conn.execute("UPDATE posts SET starts_at = now() + interval '1 day', ends_at = NULL, open_ended = false "
                 "WHERE id IN (%s, %s, %s, %s, %s)", (upcoming, deleted, own, by_blocked, reacted))
    conn.execute("UPDATE posts SET deleted_at = now() WHERE id = %s", (deleted,))
    conn.execute("UPDATE posts SET starts_at = now() - interval '2 days', ends_at = now() - interval '1 day', "
                 "open_ended = false WHERE id = %s", (ended,))
    conn.execute("UPDATE posts SET author_id = %s WHERE id = %s", (user, own))
    conn.execute("UPDATE posts SET author_id = %s WHERE id = %s", (blocked, by_blocked))
    relate(conn, user, blocked, "BLOCK")
    conn.execute("""INSERT INTO post_participations (post_id, user_id, type) VALUES (%s, %s, 'NOT_INTERESTED')
                    ON CONFLICT DO NOTHING""", (reacted, user))

    events = ranked(app.load(conn))

    assert {upcoming, own, by_blocked, reacted} <= events and not events & {deleted, ended}


def test_the_rows_written_are_the_walks_scores(conn):
    inputs = app.load(conn)
    assert inputs is not None, "seed the database first"
    run, _ = app.write(conn, inputs)

    written = {(u, p): s for u, p, s in conn.execute(
        "SELECT user_id, post_id, score FROM rec_ppr_scores WHERE run_number = %s", (run,))}
    expected = {(inputs.user_ids[u], inputs.event_ids[e]): s for u, e, s in app.written(app.scores(inputs))}
    assert written and written.keys() == expected.keys()
    assert all(abs(written[k] - s) < 1e-6 for k, s in expected.items())


def test_stances_fade_and_age_out_and_neither_a_like_nor_nie_dla_mnie_is_an_edge(conn):
    (user,) = users(conn, 1)
    fresh, old, too_old, marked = [p for (p,) in conn.execute(
        """SELECT p.id FROM posts p JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
           WHERE p.deleted_at IS NULL AND p.id NOT IN (SELECT post_id FROM post_participations WHERE user_id = %s)
           ORDER BY p.id LIMIT 4""", (app.EMBEDDING_MODEL, user))]
    for post, kind, days in ((old, "INTERESTED", 28), (too_old, "INTERESTED", 120), (marked, "NOT_INTERESTED", 0),
                             (fresh, "TAKES_PART", 0)):
        conn.execute("""INSERT INTO post_participations (post_id, user_id, type, created_at)
                        VALUES (%s, %s, %s, now() - make_interval(days => %s))""", (post, user, kind, days))
    (liked,) = conn.execute("""SELECT p.id FROM posts p JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
                               WHERE p.deleted_at IS NULL AND p.id NOT IN (%s, %s, %s, %s) ORDER BY p.id LIMIT 1""",
                            (app.EMBEDDING_MODEL, fresh, old, too_old, marked)).fetchone()
    conn.execute("""INSERT INTO post_reactions (post_id, user_id, type, created_at) VALUES (%s, %s, 'LIKE', now())
                    ON CONFLICT DO NOTHING""", (liked, user))

    inputs = app.load(conn)
    u = inputs.user_ids.index(user)
    weights = {inputs.event_ids[e]: w for v, e, w in inputs.stances if v == u}
    assert weights[fresh] == 2.0 and abs(weights[old] - 1.5 * 0.25) < 1e-9
    assert too_old not in weights and marked not in weights and liked not in weights


def test_friends_are_mutual_follows_who_share_their_stances(conn):
    a, b = users(conn, 2)
    conn.execute("DELETE FROM user_relations WHERE (source_user_id, target_user_id) IN ((%s, %s), (%s, %s))",
                 (a, b, b, a))

    def edge(source, target):
        inputs = app.load(conn)
        return (inputs.user_ids.index(source), inputs.user_ids.index(target)) in inputs.friends

    relate(conn, a, b, "FOLLOW")
    assert not edge(a, b)
    relate(conn, b, a, "FOLLOW")
    conn.execute("UPDATE users SET share_stances = true WHERE id IN (%s, %s)", (a, b))
    assert edge(a, b) and edge(b, a)
    conn.execute("UPDATE users SET share_stances = false WHERE id = %s", (b,))
    assert not edge(a, b) and edge(b, a)


def run_finished(conn, hours_ago):
    return conn.execute("INSERT INTO rec_ppr_runs (finished_at) VALUES (now() - make_interval(hours => %s)) "
                        "RETURNING run_number", (hours_ago,)).fetchone()[0]


def test_a_run_deletes_the_runs_the_feed_no_longer_reads(conn):
    conn.execute("DELETE FROM rec_ppr_scores")
    conn.execute("DELETE FROM rec_ppr_runs")
    too_old, recent = run_finished(conn, app.RUN_MAX_AGE_HOURS + 1), run_finished(conn, app.RUN_MAX_AGE_HOURS - 1)
    conn.execute("INSERT INTO rec_ppr_scores VALUES (%s, gen_random_uuid(), gen_random_uuid(), 0.5)", (too_old,))
    new, _ = app.write(conn, app.load(conn))

    assert {r for (r,) in conn.execute("SELECT run_number FROM rec_ppr_runs")} == {recent, new}
    assert not conn.execute("SELECT 1 FROM rec_ppr_scores WHERE run_number = %s", (too_old,)).fetchone()


def test_the_job_and_the_feed_have_their_grants(conn):
    granted = conn.execute(
        """SELECT has_table_privilege('coolture_app', 'rec_ppr_scores', 'INSERT'),
                  has_table_privilege('coolture_app', 'rec_ppr_runs', 'INSERT, UPDATE, DELETE'),
                  has_table_privilege('coolture_reader', 'rec_ppr_scores', 'SELECT'),
                  has_table_privilege('coolture_reader', 'rec_ppr_runs', 'SELECT')""").fetchone()
    assert all(granted)


def test_a_run_as_of_an_earlier_time_leaves_out_what_came_after(conn):
    user, friend, old_friend = users(conn, 3)
    going, fresh, deleted = [p for (p,) in conn.execute(
        "SELECT post_id FROM post_embeddings WHERE model = %s ORDER BY post_id LIMIT 3", (app.EMBEDDING_MODEL,))]
    conn.execute("UPDATE posts SET created_at = now() - interval '1 day', deleted_at = NULL")
    conn.execute("UPDATE posts SET created_at = now() WHERE id = %s", (fresh,))
    conn.execute("UPDATE posts SET deleted_at = now() WHERE id = %s", (deleted,))
    conn.execute("DELETE FROM post_participations WHERE user_id = %s", (user,))
    conn.execute("INSERT INTO post_participations (post_id, user_id, type, created_at) "
                 "VALUES (%s, %s, 'TAKES_PART', now())", (going, user))
    for other in (friend, old_friend):
        for a, b in ((user, other), (other, user)):
            relate(conn, a, b, "FOLLOW")
    conn.execute("UPDATE user_relations SET created_at = now() WHERE source_user_id = %s AND target_user_id = %s",
                 (user, friend))
    conn.execute("UPDATE user_relations SET created_at = NULL WHERE %s IN (source_user_id, target_user_id) "
                 "AND %s IN (source_user_id, target_user_id)", (user, old_friend))
    conn.execute("UPDATE users SET share_stances = true WHERE id IN (%s, %s, %s)", (user, friend, old_friend))
    (now,) = conn.execute("SELECT now()").fetchone()

    earlier = app.load(conn, now - timedelta(minutes=30))
    u, f, o = (earlier.user_ids.index(x) for x in (user, friend, old_friend))
    assert not any(s == u for s, _, _ in earlier.stances)
    assert (u, f) not in earlier.friends, "friends only since now"
    assert (u, o) in earlier.friends, "rows with no time count as always there"
    assert fresh not in earlier.event_ids and deleted in earlier.event_ids


def test_a_run_that_fails_while_writing_leaves_no_trace_but_still_deletes_old_runs(conn, monkeypatch):
    inputs = app.load(conn)
    conn.execute("DELETE FROM rec_ppr_scores")
    conn.execute("DELETE FROM rec_ppr_runs")
    too_old, recent = run_finished(conn, app.RUN_MAX_AGE_HOURS + 1), run_finished(conn, 1)
    conn.execute("INSERT INTO rec_ppr_scores VALUES (%s, gen_random_uuid(), gen_random_uuid(), 0.5)", (too_old,))

    real_scores = app.scores

    def fails_halfway(given):
        rows = real_scores(given)
        yield next(rows)
        raise MemoryError

    monkeypatch.setattr(app, "scores", fails_halfway)
    with pytest.raises(MemoryError):
        app.write(conn, inputs)

    assert [r for (r,) in conn.execute("SELECT run_number FROM rec_ppr_runs")] == [recent]
    assert not conn.execute("SELECT 1 FROM rec_ppr_scores").fetchone()
