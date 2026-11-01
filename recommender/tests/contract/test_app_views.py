"""Interface tests of IF-3, the read-only views of the app's posts, users, follows and reactions."""

import os
from datetime import datetime, timezone
from uuid import UUID

import psycopg
import pytest

STARTS_AT = datetime(2030, 1, 10, 18, tzinfo=timezone.utc)
ENDS_AT = datetime(2030, 1, 10, 21, tzinfo=timezone.utc)
CREATED_AT = datetime(2029, 12, 1, 9, tzinfo=timezone.utc)

VIEW_COLUMNS = {
    "follows": ["follower_id", "followed_id", "created_at"],
    "posts": [
        "id", "author_id", "title", "description", "tags", "starts_at", "ends_at", "type",
        "venue_latitude", "venue_longitude", "positive_reaction_count",
        "negative_reaction_count", "participant_count", "created_at",
    ],
    "reactions": ["post_id", "user_id", "type", "created_at"],
    "users": ["id"],
}


@pytest.fixture
def conn():
    connection = psycopg.connect(os.environ["DATABASE_URL"])
    yield connection
    connection.rollback()
    connection.close()


def add_user(conn, number: int) -> UUID:
    user_id = UUID(int=0xA000 + number)
    conn.execute("INSERT INTO users (id, username) VALUES (%s, %s)", (user_id, f"test_user_{number}"))
    return user_id


def add_post(conn, number: int, author_id: UUID, **fields) -> UUID:
    post_id = UUID(int=0xB000 + number)
    row = {
        "id": post_id, "author_id": author_id, "title": f"Post {number}", "description": "Jazz",
        "tags": ["jazz"], "starts_at": STARTS_AT, "ends_at": ENDS_AT, "type": "ONLINE",
        "positive_reaction_count": 3, "negative_reaction_count": 1, "participant_count": 5,
        "created_at": CREATED_AT, **fields,
    }
    conn.execute(
        f"INSERT INTO posts ({', '.join(row)}) VALUES ({', '.join(['%s'] * len(row))})",
        list(row.values()),
    )
    return post_id


def add_venue(conn, latitude: float, longitude: float) -> UUID:
    venue_id = UUID(int=0xC000)
    conn.execute(
        "INSERT INTO event_locations (id, coordinates) VALUES (%s, ST_MakePoint(%s, %s)::geography)",
        (venue_id, longitude, latitude),
    )
    return venue_id


def test_if_3_schema_holds_the_four_views_with_their_columns(conn):
    rows = conn.execute(
        "SELECT table_name, column_name FROM information_schema.columns "
        "WHERE table_schema = 'app_views' ORDER BY table_name, ordinal_position"
    ).fetchall()
    columns = {}
    for view, column in rows:
        columns.setdefault(view, []).append(column)
    assert columns == VIEW_COLUMNS


def test_if_3_posts_view_returns_an_online_post(conn):
    author = add_user(conn, 1)
    post = add_post(conn, 1, author)

    row = conn.execute("SELECT * FROM app_views.posts WHERE id = %s", (post,)).fetchone()

    assert row == (
        post, author, "Post 1", "Jazz", ["jazz"], STARTS_AT, ENDS_AT, "ONLINE",
        None, None, 3, 1, 5, CREATED_AT,
    )


def test_if_3_posts_view_returns_the_venue_of_an_offline_post(conn):
    author = add_user(conn, 1)
    venue = add_venue(conn, latitude=52.25, longitude=21.0)
    post = add_post(conn, 1, author, type="OFFLINE", event_location_id=venue)

    row = conn.execute(
        "SELECT venue_latitude, venue_longitude FROM app_views.posts WHERE id = %s", (post,)
    ).fetchone()

    assert row == pytest.approx((52.25, 21.0))


@pytest.mark.parametrize(
    ("fields", "is_listed"),
    [
        ({"status": "EDITED"}, True),
        ({"status": "DELETED"}, False),
        ({"deleted_at": CREATED_AT}, False),
        ({"visibility": "FRIENDS"}, False),
        ({"visibility": "PRIVATE"}, False),
    ],
)
def test_if_3_posts_view_lists_live_public_posts_only(conn, fields, is_listed):
    post = add_post(conn, 1, add_user(conn, 1), **fields)

    rows = conn.execute("SELECT id FROM app_views.posts WHERE id = %s", (post,)).fetchall()

    assert rows == ([(post,)] if is_listed else [])


def test_if_3_follows_view_returns_follows_only(conn):
    follower, followed, blocked = (add_user(conn, n) for n in (1, 2, 3))
    conn.execute(
        "INSERT INTO user_relations (source_user_id, target_user_id, type, created_at) "
        "VALUES (%s, %s, 'FOLLOW', %s), (%s, %s, 'BLOCK', %s)",
        (follower, followed, CREATED_AT, follower, blocked, CREATED_AT),
    )

    rows = conn.execute(
        "SELECT * FROM app_views.follows WHERE follower_id IN (%s, %s, %s)",
        (follower, followed, blocked),
    ).fetchall()

    assert rows == [(follower, followed, CREATED_AT)]


def test_if_3_reactions_view_returns_likes_and_dislikes(conn):
    author, liker, disliker = (add_user(conn, n) for n in (1, 2, 3))
    post = add_post(conn, 1, author)
    conn.execute(
        "INSERT INTO post_reactions (post_id, user_id, type, created_at) "
        "VALUES (%s, %s, 'LIKE', %s), (%s, %s, 'DISLIKE', %s)",
        (post, liker, CREATED_AT, post, disliker, CREATED_AT),
    )

    rows = conn.execute(
        "SELECT * FROM app_views.reactions WHERE post_id = %s ORDER BY type", (post,)
    ).fetchall()

    assert rows == [(post, disliker, "DISLIKE", CREATED_AT), (post, liker, "LIKE", CREATED_AT)]


def test_if_3_users_view_returns_a_user(conn):
    user = add_user(conn, 1)

    rows = conn.execute("SELECT * FROM app_views.users WHERE id = %s", (user,)).fetchall()

    assert rows == [(user,)]


@pytest.mark.parametrize("view", sorted(VIEW_COLUMNS))
@pytest.mark.parametrize(
    "statement",
    [
        "INSERT INTO app_views.{view} SELECT * FROM app_views.{view}",
        "UPDATE app_views.{view} SET {column} = {column}",
        "DELETE FROM app_views.{view}",
    ],
)
def test_if_3_reader_cannot_write_through_the_views(conn, view, statement):
    conn.execute("SET LOCAL ROLE app_views_reader")

    with pytest.raises(
        (psycopg.errors.InsufficientPrivilege, psycopg.errors.ObjectNotInPrerequisiteState)
    ):
        conn.execute(statement.format(view=view, column=VIEW_COLUMNS[view][0]))


@pytest.mark.parametrize("view", sorted(VIEW_COLUMNS))
def test_if_3_reader_reads_the_views(conn, view):
    conn.execute("SET LOCAL ROLE app_views_reader")

    conn.execute(f"SELECT * FROM app_views.{view} LIMIT 1")


@pytest.mark.parametrize("table", ["posts", "users", "user_relations", "post_reactions"])
def test_if_3_reader_cannot_read_the_app_tables(conn, table):
    conn.execute("SET LOCAL ROLE app_views_reader")

    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        conn.execute(f"SELECT 1 FROM public.{table} LIMIT 1")
