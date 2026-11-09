"""Fills a local stack with realistic data: the snapshot's events as the bot, then reactions of the demo users.

    python scraper/seed.py events       # make seed-events
    python scraper/seed.py reactions    # make seed-reactions, after make embed-interests and make embed-posts

Both can run again and add only what's missing.
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
from scrape import DEFAULT_API_URL, http_session, utc_now
from snapshot import DEFAULT_DIR, Entry, is_complete, load_images, read
from trojmiasto import WARSAW

log = logging.getLogger('seed')

REPO_DIR = Path(__file__).resolve().parent.parent
BOT_ID = '00000000-0000-0000-0000-00000000b07a'
# A local write takes about 1.3 s. Four at a time took about 40% of the time of one at a time, and more gave nothing.
WORKERS = 4
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
DISLIKED_FROM_THE_END = 2

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


def _parse(moment: str) -> datetime:
    return datetime.fromisoformat(moment.replace('Z', '+00:00'))


def _iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')


def shifted(post: dict, days: int) -> dict:
    return {**post, **{key: _iso(moved(_parse(post[key]), days)) for key in ('startsAt', 'endsAt') if key in post}}


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
                api_factory: Callable[[], CooltureApi] | None = None) -> Counter:
    entries = read(directory)
    if not entries:
        raise SeedError(f'No snapshot in {directory}. Make one with `make scrape-snapshot`.')
    if not is_complete(directory):
        log.warning('The snapshot in %s is unfinished; run `make scrape-snapshot` to complete it.', directory)
    new_api = api_factory or (lambda: CooltureApi(http_session(), http_session(), base_url, user_id=BOT_ID))

    existing = new_api().existing_event_urls()
    todo = [entry for entry in entries if entry.post['eventUrl'] not in existing]
    days = shift_days([_parse(entry.post['startsAt']) for entry in entries], clock())
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
    tail = [post_id for post_id in ranking[-DISLIKED_FROM_THE_END:] if post_id not in taken]
    return chosen + [(post_id, 'reaction', 'DISLIKE') for post_id in tail]


def seed_reactions(base_url: str, query: Callable[[str], list[str]] = psql,
                   session_factory: Callable[[], requests.Session] = http_session) -> Counter:
    stats: Counter = Counter()
    for user_id, username in DEMO_USERS.items():
        if int(query(HAS_REACTED_SQL.format(user_id=user_id))[0]):
            # Their reactions were seeded before, or made in the app, so they're left as they are.
            stats['users_skipped'] += 1
            continue
        ranking = query(RANKING_SQL.format(user_id=user_id, bot_id=BOT_ID))
        if not ranking:
            raise SeedError(f'No ranked posts for {username}. Load events and embed them first: '
                            '`make seed-events embed-interests embed-posts`.')
        session = session_factory()
        session.headers.update({'X-User-Id': user_id, 'X-User-Name': username})
        chosen = picks(ranking)
        for post_id, path, kind in chosen:
            response = session.put(f'{base_url}/posts/{post_id}/{path}', json={'type': kind}, timeout=TIMEOUT_S)
            if not response.ok:
                raise SeedError(f'PUT /posts/{post_id}/{path} as {username} returned {response.status_code}: '
                                f'{response.text[:300]}')
            stats[kind.lower()] += 1
        stats['users'] += 1
        log.info('%s reacted to %d of %d posts', username, len(chosen), len(ranking))
    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description='Fill the local stack with realistic data')
    parser.add_argument('step', choices=['events', 'reactions'])
    parser.add_argument('--snapshot', type=Path, default=DEFAULT_DIR, metavar='DIR', help='the snapshot to load')
    parser.add_argument('--workers', type=int, default=WORKERS, help=f'posts loaded at a time (default {WORKERS})')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', stream=sys.stderr)
    logging.getLogger('urllib3').setLevel(logging.ERROR)
    base_url = os.getenv('SCRAPER_API_URL', DEFAULT_API_URL).rstrip('/')

    stats: Counter = Counter()
    try:
        wait_for_api(base_url)
        stats = seed_events(args.snapshot, base_url, args.workers) if args.step == 'events' else seed_reactions(base_url)
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
