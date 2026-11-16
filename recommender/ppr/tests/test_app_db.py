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


def test_a_run_leaves_out_what_the_feed_wont_show(conn):
    user, blocked, blocker = users(conn, 3)
    deleted, ended, own, by_blocked, by_blocker, reacted = [p for (p,) in conn.execute(
        """SELECT p.id FROM posts p JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
           WHERE p.deleted_at IS NULL ORDER BY p.id LIMIT 6""", (app.EMBEDDING_MODEL,))]
    conn.execute("UPDATE posts SET deleted_at = now() WHERE id = %s", (deleted,))
    conn.execute("UPDATE posts SET starts_at = now() - interval '2 days', ends_at = now() - interval '1 day' WHERE id = %s",
                 (ended,))
    for post, author in ((own, user), (by_blocked, blocked), (by_blocker, blocker)):
        conn.execute("UPDATE posts SET author_id = %s WHERE id = %s", (author, post))
    relate(conn, user, blocked, "BLOCK")
    relate(conn, blocker, user, "BLOCK")
    conn.execute("""INSERT INTO post_participations (post_id, user_id, type) VALUES (%s, %s, 'NOT_INTERESTED')
                    ON CONFLICT DO NOTHING""", (reacted, user))

    run, _ = app.write(conn, app.load(conn))

    scored = {p for (p,) in conn.execute("SELECT post_id FROM rec_ppr_scores WHERE run_number = %s AND user_id = %s",
                                         (run, user))}
    assert scored and not scored & {deleted, ended, own, by_blocked, by_blocker, reacted}


def test_the_rows_written_are_the_walks_scores(conn):
    inputs = app.load(conn)
    assert inputs is not None, "seed the database first"
    run, _ = app.write(conn, inputs)

    written = {(u, p): s for u, p, s in conn.execute(
        "SELECT user_id, post_id, score FROM rec_ppr_scores WHERE run_number = %s", (run,))}
    expected = {(inputs.user_ids[u], inputs.event_ids[e]): s for u, e, s in app.scores(inputs)}
    assert written and written.keys() == expected.keys()
    assert all(abs(written[k] - s) < 1e-6 for k, s in expected.items())


def test_stances_fade_and_age_out_and_nie_dla_mnie_is_no_edge(conn):
    (user,) = users(conn, 1)
    fresh, old, too_old, marked = [p for (p,) in conn.execute(
        """SELECT p.id FROM posts p JOIN post_embeddings pe ON pe.post_id = p.id AND pe.model = %s
           WHERE p.deleted_at IS NULL AND p.id NOT IN (SELECT post_id FROM post_participations WHERE user_id = %s)
           ORDER BY p.id LIMIT 4""", (app.EMBEDDING_MODEL, user))]
    for post, kind, days in ((old, "INTERESTED", 28), (too_old, "INTERESTED", 120), (marked, "NOT_INTERESTED", 0),
                             (fresh, "TAKES_PART", 0)):
        conn.execute("""INSERT INTO post_participations (post_id, user_id, type, created_at)
                        VALUES (%s, %s, %s, now() - make_interval(days => %s))""", (post, user, kind, days))

    inputs = app.load(conn)
    u = inputs.user_ids.index(user)
    weights = {inputs.event_ids[e]: w for v, e, w in inputs.stances if v == u}
    assert weights[fresh] == 2.0 and abs(weights[old] - 1.5 * 0.25) < 1e-9
    assert too_old not in weights and marked not in weights


def test_follows_count_only_between_friends_who_share_their_stances(conn):
    a, b = users(conn, 2)
    conn.execute("DELETE FROM user_relations WHERE (source_user_id, target_user_id) IN ((%s, %s), (%s, %s))",
                 (a, b, b, a))

    def edge(source, target):
        inputs = app.load(conn)
        return (inputs.user_ids.index(source), inputs.user_ids.index(target)) in inputs.follows

    relate(conn, a, b, "FOLLOW")
    assert not edge(a, b)
    relate(conn, b, a, "FOLLOW")
    conn.execute("UPDATE users SET share_stances = true WHERE id IN (%s, %s)", (a, b))
    assert edge(a, b) and edge(b, a)
    conn.execute("UPDATE users SET share_stances = false WHERE id = %s", (b,))
    assert not edge(a, b) and edge(b, a)


def test_a_run_keeps_the_newest_run_finished_over_a_day_ago_and_deletes_older_ones(conn):
    def run_finished(ago):
        return conn.execute("INSERT INTO rec_ppr_runs (finished_at) VALUES (now() - %s::interval) RETURNING run_number",
                            (ago,)).fetchone()[0]

    conn.execute("DELETE FROM rec_ppr_scores")
    conn.execute("DELETE FROM rec_ppr_runs")
    day_old, oldest, recent = run_finished("2 days"), run_finished("3 days"), run_finished("1 hour")
    conn.execute("INSERT INTO rec_ppr_scores VALUES (%s, gen_random_uuid(), gen_random_uuid(), 0.5)", (oldest,))
    new, _ = app.write(conn, app.load(conn))

    assert {r for (r,) in conn.execute("SELECT run_number FROM rec_ppr_runs")} == {day_old, recent, new}
    assert not conn.execute("SELECT 1 FROM rec_ppr_scores WHERE run_number = %s", (oldest,)).fetchone()


def test_the_job_and_the_feed_have_their_grants(conn):
    granted = conn.execute(
        """SELECT has_table_privilege('coolture_app', 'rec_ppr_scores', 'INSERT'),
                  has_table_privilege('coolture_app', 'rec_ppr_runs', 'INSERT, UPDATE, DELETE'),
                  has_table_privilege('coolture_reader', 'rec_ppr_scores', 'SELECT'),
                  has_table_privilege('coolture_reader', 'rec_ppr_runs', 'SELECT')""").fetchone()
    assert all(granted)


def test_a_run_as_of_an_earlier_time_leaves_out_what_came_after(conn):
    user, friend = users(conn, 2)
    post, fresh = [p for (p,) in conn.execute("SELECT post_id FROM post_embeddings WHERE model = %s ORDER BY post_id LIMIT 2",
                                              (app.EMBEDDING_MODEL,))]
    conn.execute("UPDATE posts SET created_at = now() - interval '1 day'")
    conn.execute("UPDATE posts SET created_at = now() WHERE id = %s", (fresh,))
    conn.execute("DELETE FROM post_reactions WHERE user_id = %s", (user,))
    conn.execute("DELETE FROM post_participations WHERE user_id = %s", (user,))
    conn.execute("INSERT INTO post_reactions (post_id, user_id, type, created_at) VALUES (%s, %s, 'LIKE', now())",
                 (post, user))
    for a, b in ((user, friend), (friend, user)):
        relate(conn, a, b, "FOLLOW")
    # Friends only since now; the other side's follow has no time and counts as always there.
    conn.execute("UPDATE user_relations SET created_at = now() WHERE source_user_id = %s AND target_user_id = %s",
                 (user, friend))
    conn.execute("UPDATE user_relations SET created_at = NULL WHERE source_user_id = %s AND target_user_id = %s",
                 (friend, user))
    conn.execute("UPDATE users SET share_stances = true WHERE id IN (%s, %s)", (user, friend))
    (now,) = conn.execute("SELECT now()").fetchone()

    earlier = app.load(conn, now - timedelta(minutes=30))
    u, f = earlier.user_ids.index(user), earlier.user_ids.index(friend)
    assert not any(s == u for s, _, _ in earlier.stances)
    assert earlier.event_ids.index(post) not in earlier.hidden[u]
    assert (u, f) not in earlier.follows and (f, u) not in earlier.follows
    assert fresh not in earlier.event_ids


def test_a_run_that_fails_while_writing_leaves_no_trace(conn, monkeypatch):
    inputs = app.load(conn)
    runs_before = conn.execute("SELECT count(*) FROM rec_ppr_runs").fetchone()[0]
    rows_before = conn.execute("SELECT count(*) FROM rec_ppr_scores").fetchone()[0]

    real_scores = app.scores

    def fails_halfway(given):
        rows = real_scores(given)
        yield next(rows)
        raise MemoryError

    with monkeypatch.context() as patch:
        patch.setattr(app, "scores", fails_halfway)
        with pytest.raises(MemoryError):
            app.write(conn, inputs)

    assert conn.execute("SELECT count(*) FROM rec_ppr_runs").fetchone()[0] == runs_before
    assert conn.execute("SELECT count(*) FROM rec_ppr_scores").fetchone()[0] == rows_before
