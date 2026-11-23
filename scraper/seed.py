"""Fills a local stack with realistic data: the snapshot's events as the bot, then reactions of the demo users.

    python scraper/seed.py events       # make seed-events
    python scraper/seed.py reactions    # make seed-reactions, after make embed-interests and make embed-posts
    python scraper/seed.py reactions --reset   # make seed-reactions RESET=1: seeds them again from current picks
    python scraper/seed.py friends      # make seed-friends, after make seed-events

Each can run again. events and reactions add only what's missing; friends puts its cases back as they were.
"""
import argparse
import logging
import os
import subprocess
import sys
import threading
import time
from collections import Counter
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

from api import TIMEOUT_S, ApiError, CooltureApi, Outcome
from images import Image, solid_png
from posts import iso
from scrape import DEFAULT_API_URL, http_session, utc_now
from snapshot import DEFAULT_DIR, Entry, is_complete, load_images, read, sample
from trojmiasto import WARSAW

log = logging.getLogger('seed')

REPO_DIR = Path(__file__).resolve().parent.parent
BOT_ID = '00000000-0000-0000-0000-00000000b07a'
# Four at a time was fastest; more gave nothing.
WORKERS = 4
# Every category and day at a fraction of the whole snapshot's load time; `--size all` loads all of it.
SEED_SIZE = 200
# An event must still be ahead when it's posted, since POST /posts refuses a past start.
START_MARGIN = timedelta(hours=2)
API_WAIT_S = 15 * 60
API_POLL_S = 3

# The demo users of db/local-dev-seed.sql.
DEMO_USERS = {
    '00000000-0000-0000-0000-000000000002': 'anna_demo',
    '00000000-0000-0000-0000-000000000003': 'jan_demo',
    '00000000-0000-0000-0000-000000000004': 'ola_demo',
}
# Places in a user's ranking of posts by closeness to their interests. Reacted posts leave their feed, so the
# closest few are left for it to show, and the likes come from just below them.
LIKE_RANKS = (3, 6, 9, 12, 15)
INTERESTED_RANKS = (4, 10)
NOT_FOR_ME_FROM_THE_END = 2
# The API stamps a reaction with the time it's made. The feed lets older stances fade, so the demo users get a past,
# some weeks back, and what someone likes in the app leads their taste, as it would for a real user.
HISTORY_DAYS = (14, 42)
# The friends line's cases are props for cards, whoever the friends are, and every friend is a persona. Fresh, a case's
# stances led its friends' own feeds; dated as far back as reactions go, they fade there like old reactions and still
# count for the friends line and PageRank.
FRIEND_DAYS = HISTORY_DAYS[1]

RANKING_SQL = """
WITH taste AS (
    SELECT avg(ie.embedding) AS vector, min(ie.model) AS model
    FROM user_interests ui
    JOIN interest_embeddings ie ON ie.interest_key = ui.interest_key
    WHERE ui.user_id = '{user_id}'
)
SELECT p.id
FROM posts p
JOIN post_embeddings pe ON pe.post_id = p.id
CROSS JOIN taste
WHERE p.author_id = '{bot_id}' AND p.status <> 'DELETED' AND p.deleted_at IS NULL
  AND p.starts_at > now() AND pe.model = taste.model
ORDER BY pe.embedding <=> taste.vector, p.id
"""
DATED_SQL = """
UPDATE post_{table}s SET created_at = now() - make_interval(days => {days})
WHERE user_id = '{user_id}' AND post_id = '{post_id}';
"""
# The demo users' friends in db/local-dev-seed.sql. Bartek doesn't share his stances and Iza isn't followed back.
FRIENDS = {
    'kasia_demo': '00000000-0000-0000-0000-000000000005',
    'piotr_demo': '00000000-0000-0000-0000-000000000006',
    'ewa_demo': '00000000-0000-0000-0000-000000000007',
    'tomek_demo': '00000000-0000-0000-0000-000000000008',
    'zofia_demo': '00000000-0000-0000-0000-000000000009',
    'marek_demo': '00000000-0000-0000-0000-000000000010',
    'magda_demo': '00000000-0000-0000-0000-000000000011',
    'bartek_demo': '00000000-0000-0000-0000-000000000012',
    'iza_demo': '00000000-0000-0000-0000-000000000013',
}
# One shape of a card's friends line per post: (what it shows, going, interested). A card names the newest two of each.
FRIEND_CASES = (
    ('going 1', ('kasia_demo',), ()),
    ('interested 1', (), ('piotr_demo',)),
    ('going 1, interested 1', ('ewa_demo',), ('tomek_demo',)),
    ('going 2, a photo and initials', ('kasia_demo', 'zofia_demo'), ()),
    ('going 5, +3', ('kasia_demo', 'piotr_demo', 'ewa_demo', 'tomek_demo', 'zofia_demo'), ()),
    ('going 3 and interested 4, both counted', ('zofia_demo', 'marek_demo', 'magda_demo'),
     ('kasia_demo', 'piotr_demo', 'ewa_demo', 'tomek_demo')),
    ('no line: a friend who keeps stances to himself, and a one-way follower', ('bartek_demo',), ('iza_demo',)),
)
# Some faces are photos and some initials. Plain colours, so nobody's picture is needed.
AVATARS = {'kasia_demo': (214, 96, 77), 'tomek_demo': (61, 120, 170), 'magda_demo': (88, 150, 92)}
AVATAR_SIZES = (512, 128)
# The soonest posts the Upcoming sort shows that nobody outside FRIENDS took a stance on, so a viewer's own stance, or
# a stance of Anna or Jan, who are friends too, can't change a case.
FRIEND_POSTS_SQL = """
SELECT p.id
FROM posts p
WHERE p.author_id = '{bot_id}' AND p.deleted_at IS NULL
  AND event_end(p.starts_at, p.ends_at, p.open_ended) >= now()
  AND NOT EXISTS (SELECT 1 FROM post_participations pp
                  WHERE pp.post_id = p.id AND pp.user_id <> ALL('{{{friends}}}'::uuid[]))
ORDER BY p.starts_at, p.id
LIMIT {count}
"""
FRIEND_STANCES_SQL = """
SELECT pp.post_id || ',' || pp.user_id FROM post_participations pp WHERE pp.user_id = ANY('{{{friends}}}'::uuid[])
"""
STANCES_SQL = """
SELECT 'reaction,' || r.post_id FROM post_reactions r JOIN posts p ON p.id = r.post_id
WHERE r.user_id = '{user_id}' AND p.deleted_at IS NULL
UNION ALL
SELECT 'participation,' || r.post_id FROM post_participations r JOIN posts p ON p.id = r.post_id
WHERE r.user_id = '{user_id}' AND p.deleted_at IS NULL
"""
# The API refuses a deleted post, and a deleted post has no counts left to keep.
ON_DELETED_SQL = """
DELETE FROM post_reactions WHERE user_id = '{user_id}' AND post_id IN (SELECT id FROM posts WHERE deleted_at IS NOT NULL);
DELETE FROM post_participations WHERE user_id = '{user_id}'
  AND post_id IN (SELECT id FROM posts WHERE deleted_at IS NOT NULL);
"""
HAS_REACTED_SQL = """
SELECT (SELECT count(*) FROM post_reactions WHERE user_id = '{user_id}')
     + (SELECT count(*) FROM post_participations WHERE user_id = '{user_id}')
"""


class SeedError(RuntimeError):
    pass


def moved(moment: datetime, days: int) -> datetime:
    """Whole days later on the Warsaw clock, so an event keeps its hour across a change to or from summer time."""
    return (moment.astimezone(WARSAW) + timedelta(days=days)).astimezone(timezone.utc)


def shift_days(starts: list[datetime], now: datetime, margin: timedelta = START_MARGIN) -> int:
    """The fewest whole days that put every start after now plus the margin."""
    earliest = min(starts)
    days = max(0, (now.astimezone(WARSAW).date() - earliest.astimezone(WARSAW).date()).days)
    while moved(earliest, days) <= now + margin:
        days += 1
    return days


def shifted(post: dict, days: int) -> dict:
    return {**post, **{key: iso(moved(datetime.fromisoformat(post[key]), days))
                       for key in ('startsAt', 'endsAt') if key in post}}


def wait_for_api(base_url: str, timeout_s: float = API_WAIT_S, sleep: Callable[[float], None] = time.sleep,
                 monotonic: Callable[[], float] = time.monotonic) -> None:
    """Returns once the API answers, so seeding can start while `make dev` is still building."""
    deadline = monotonic() + timeout_s
    told = False
    while True:
        try:
            if requests.get(f'{base_url}/posts', params={'limit': 1}, timeout=TIMEOUT_S).status_code < 500:
                return
        except requests.RequestException:
            pass
        if monotonic() >= deadline:
            raise SeedError(f'The API at {base_url} did not answer in {timeout_s / 60:.0f} minutes. '
                            'Is `make local` (or `make dev`) running?')
        if not told:
            log.info('Waiting for the API at %s. Start it with `make local` or `make dev` in another terminal.', base_url)
            told = True
        sleep(API_POLL_S)


def seed_events(directory: Path, base_url: str, workers: int = WORKERS, clock: Callable[[], datetime] = utc_now,
                api_factory: Callable[[], CooltureApi] | None = None, size: int | None = SEED_SIZE) -> Counter:
    """Loads `size` of the snapshot's events (snapshot.sample), or all of them when size is None."""
    snapshot = read(directory)
    if not snapshot:
        raise SeedError(f'No snapshot in {directory}. Make one with `make scrape-snapshot`.')
    if not is_complete(directory):
        log.warning('The snapshot in %s is unfinished; run `make scrape-snapshot` to complete it.', directory)
    new_api = api_factory or (lambda: CooltureApi(http_session(), http_session(), base_url, user_id=BOT_ID))

    entries = snapshot if size is None else sample(snapshot, size)
    existing = new_api().existing_event_urls()
    todo = [entry for entry in entries if entry.post['eventUrl'] not in existing]
    # From the whole snapshot, so every size moves the dates alike and a later, bigger load fits the first.
    days = shift_days([datetime.fromisoformat(entry.post['startsAt']) for entry in snapshot], clock())
    log.info('Snapshot: %d events, %d already loaded, %d to load, dates moved %d days forward',
             len(entries), len(entries) - len(todo), len(todo), days)

    # requests' sessions aren't meant to be shared between threads, so each thread has its own.
    local = threading.local()

    def load(entry: Entry) -> Outcome:
        if not hasattr(local, 'api'):
            local.api = new_api()
        return local.api.create_post(shifted(entry.post, days), load_images(directory, entry))

    stats: Counter = Counter(snapshot=len(entries), already_loaded=len(entries) - len(todo))
    with ThreadPoolExecutor(workers) as pool:
        for entry, outcome in zip(todo, pool.map(load, todo)):
            if outcome.failure:
                stats['rejected'] += 1
                log.warning('Rejected url=%s reason=%s', entry.post['eventUrl'], outcome.failure)
            else:
                stats['created'] += 1
                stats['photos'] += outcome.photos
    return stats


def psql(sql: str) -> list[str]:
    command = ['docker', 'compose', 'exec', '-T', 'postgres',
               'psql', '-U', 'coolture_admin', '-d', 'coolture', '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql]
    result = subprocess.run(command, cwd=REPO_DIR, capture_output=True, text=True, encoding='utf-8')
    if result.returncode:
        raise SeedError(f'Database query failed: {result.stderr.strip() or result.stdout.strip()}')
    return result.stdout.split()


def picks(ranking: list[str]) -> list[tuple[str, str, str]]:
    """(post id, path, type) for each reaction of one user, from posts ranked closest to their interests first."""
    chosen = [(ranking[rank], 'reaction', 'LIKE') for rank in LIKE_RANKS if rank < len(ranking)]
    chosen += [(ranking[rank], 'participation', 'INTERESTED') for rank in INTERESTED_RANKS if rank < len(ranking)]
    taken = {post_id for post_id, _, _ in chosen}
    tail = [post_id for post_id in ranking[-NOT_FOR_ME_FROM_THE_END:] if post_id not in taken]
    return chosen + [(post_id, 'participation', 'NOT_INTERESTED') for post_id in tail]


def history(count: int) -> list[int]:
    """How many days ago each of a user's reactions was made, spread evenly over HISTORY_DAYS."""
    first, last = HISTORY_DAYS
    return [first + round((last - first) * i / max(count - 1, 1)) for i in range(count)]


def _sent(response: requests.Response, request: str, username: str) -> requests.Response:
    if not response.ok:
        raise SeedError(f'{request} as {username} returned {response.status_code}: {response.text[:300]}')
    return response


def _as_user(session_factory: Callable[[], requests.Session], user_id: str, username: str) -> requests.Session:
    session = session_factory()
    session.headers.update({'X-User-Id': user_id, 'X-User-Name': username})
    return session


def seed_reactions(base_url: str, query: Callable[[str], list[str]] = psql,
                   session_factory: Callable[[], requests.Session] = http_session, reset: bool = False) -> Counter:
    """With reset, first takes back every reaction and stance of the demo users, so their picks lead again after they
    change, at the cost of what was done as them in the app."""
    stats: Counter = Counter()
    for user_id, username in DEMO_USERS.items():
        session = _as_user(session_factory, user_id, username)
        if reset:
            for row in query(STANCES_SQL.format(user_id=user_id)):
                path, post_id = row.split(',')
                _sent(session.delete(f'{base_url}/posts/{post_id}/{path}', timeout=TIMEOUT_S),
                      f'DELETE /posts/{post_id}/{path}', username)
                stats['taken_back'] += 1
            query(ON_DELETED_SQL.format(user_id=user_id))
        elif int(query(HAS_REACTED_SQL.format(user_id=user_id))[0]):
            # Their reactions were seeded before, or made in the app, so they're left as they are.
            stats['users_skipped'] += 1
            continue
        ranking = query(RANKING_SQL.format(user_id=user_id, bot_id=BOT_ID))
        if not ranking:
            raise SeedError(f'No ranked posts for {username}. Load events and embed them first: '
                            '`make seed-events embed-interests embed-posts`.')
        chosen = picks(ranking)
        for post_id, path, kind in chosen:
            _sent(session.put(f'{base_url}/posts/{post_id}/{path}', json={'type': kind}, timeout=TIMEOUT_S),
                  f'PUT /posts/{post_id}/{path}', username)
            stats[kind.lower()] += 1
        query(''.join(DATED_SQL.format(table=path, days=days, user_id=user_id, post_id=post_id)
                      for (post_id, path, _), days in zip(chosen, history(len(chosen)))))
        stats['users'] += 1
        log.info('%s reacted to %d of %d posts', username, len(chosen), len(ranking))
    return stats


def friend_stances(post_ids: list[str]) -> list[tuple[str, str, str]]:
    """(post id, username, type) for each stance of FRIEND_CASES, a post per case, in the order they're taken."""
    return [(post_id, name, kind)
            for post_id, (_, going, interested) in zip(post_ids, FRIEND_CASES)
            for kind, names in (('TAKES_PART', going), ('INTERESTED', interested))
            for name in names]




def seed_friends(base_url: str, query: Callable[[str], list[str]] = psql,
                 session_factory: Callable[[], requests.Session] = http_session,
                 api_factory: Callable[[str], CooltureApi] | None = None) -> Counter:
    """Gives the friends their stances on the soonest posts, a case of the friends line per post, and takes back any
    other stance of theirs, so a later run puts each case back as it was."""
    friends = ','.join(FRIENDS.values())
    post_ids = query(FRIEND_POSTS_SQL.format(bot_id=BOT_ID, friends=friends, count=len(FRIEND_CASES)))
    if len(post_ids) < len(FRIEND_CASES):
        raise SeedError(f'Only {len(post_ids)} upcoming posts without stances. Load events first: `make seed-events`.')
    planned = friend_stances(post_ids)

    sessions: dict[str, requests.Session] = {}

    def session(username: str) -> requests.Session:
        if username not in sessions:
            sessions[username] = _as_user(session_factory, FRIENDS[username], username)
        return sessions[username]

    stats: Counter = Counter()
    keep = {(post_id, FRIENDS[name]) for post_id, name, _ in planned}
    names = {user_id: name for name, user_id in FRIENDS.items()}
    for row in query(FRIEND_STANCES_SQL.format(friends=friends)):
        post_id, user_id = row.split(',')
        if (post_id, user_id) not in keep:
            _sent(session(names[user_id]).delete(f'{base_url}/posts/{post_id}/participation', timeout=TIMEOUT_S),
                  f'DELETE /posts/{post_id}/participation', names[user_id])
            stats['taken_back'] += 1
    for post_id, name, kind in planned:
        _sent(session(name).put(f'{base_url}/posts/{post_id}/participation', json={'type': kind}, timeout=TIMEOUT_S),
              f'PUT /posts/{post_id}/participation', name)
        stats[kind.lower()] += 1
    query(''.join(DATED_SQL.format(table='participation', days=FRIEND_DAYS, user_id=FRIENDS[name], post_id=post_id)
                  for post_id, name, _ in planned))

    new_api = api_factory or (lambda user_id: CooltureApi(http_session(), http_session(), base_url, user_id=user_id))
    for name, colour in AVATARS.items():
        profile = _sent(session(name).get(f'{base_url}/users/{FRIENDS[name]}', timeout=TIMEOUT_S),
                        f'GET /users/{FRIENDS[name]}', name).json()
        if profile.get('avatar'):
            continue
        full, thumbnail = (Image.read(solid_png(size, colour), 'image/png', f'{name}-{size}.png') for size in AVATAR_SIZES)
        new_api(FRIENDS[name]).set_profile_image(FRIENDS[name], full, thumbnail)
        stats['photos'] += 1

    for post_id, (shows, _, _) in zip(post_ids, FRIEND_CASES):
        post = _sent(session('kasia_demo').get(f'{base_url}/posts/{post_id}', timeout=TIMEOUT_S),
                     f'GET /posts/{post_id}', 'kasia_demo').json()
        log.info('%s: %s (%s)', shows, post.get('title'), post_id)
    return stats


def _size(value: str) -> int | None:
    return None if value == 'all' else int(value)


def main() -> int:
    parser = argparse.ArgumentParser(description='Fill the local stack with realistic data')
    parser.add_argument('step', choices=['events', 'reactions', 'friends'])
    parser.add_argument('--snapshot', type=Path, default=DEFAULT_DIR, metavar='DIR', help='the snapshot to load')
    parser.add_argument('--workers', type=int, default=WORKERS, help=f'posts loaded at a time (default {WORKERS})')
    parser.add_argument('--size', type=_size, default=SEED_SIZE, metavar='N|all',
                        help=f'events: how many of the snapshot to load (default {SEED_SIZE})')
    parser.add_argument('--reset', action='store_true',
                        help="reactions: take back the demo users' reactions and stances first, then seed them again")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', stream=sys.stderr)
    logging.getLogger('urllib3').setLevel(logging.ERROR)
    base_url = os.getenv('SCRAPER_API_URL', DEFAULT_API_URL).rstrip('/')

    stats: Counter = Counter()
    try:
        wait_for_api(base_url)
        if args.step == 'events':
            stats = seed_events(args.snapshot, base_url, args.workers, size=args.size)
        elif args.step == 'reactions':
            stats = seed_reactions(base_url, reset=args.reset)
        else:
            stats = seed_friends(base_url)
    except (SeedError, ApiError) as error:
        log.error('%s', error)
        return 1
    except requests.RequestException as error:
        log.error('Request failed: %s', error)
        return 1
    log.info('Done: %s', dict(stats))
    return 1 if stats['rejected'] else 0


if __name__ == '__main__':
    sys.exit(main())
