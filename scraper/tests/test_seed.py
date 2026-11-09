from datetime import datetime, timedelta, timezone

import pytest
import requests

import seed
from api import Outcome
from images import Image
from seed import (BOT_ID, SeedError, moved, picks, seed_events, seed_reactions, shift_days, shifted, wait_for_api)
from snapshot import Snapshot

UTC = timezone.utc


def test_dates_move_by_whole_days_on_the_warsaw_clock_across_summer_time():
    # 19:00 in Warsaw in summer (UTC+2) is still 19:00 in winter (UTC+1).
    summer = datetime(2026, 10, 20, 17, 0, tzinfo=UTC)

    assert moved(summer, 10) == datetime(2026, 10, 30, 18, 0, tzinfo=UTC)


def test_shift_is_the_fewest_days_that_put_every_start_ahead():
    starts = [datetime(2026, 11, 5, 18, 0, tzinfo=UTC), datetime(2026, 11, 20, 18, 0, tzinfo=UTC)]

    assert shift_days(starts, now=datetime(2026, 11, 9, 12, 0, tzinfo=UTC)) == 4
    # Later the same day the earliest would start too soon, so it goes one day further.
    assert shift_days(starts, now=datetime(2026, 11, 9, 17, 0, tzinfo=UTC)) == 5


def test_a_snapshot_still_ahead_is_not_moved():
    assert shift_days([datetime(2026, 12, 1, 18, 0, tzinfo=UTC)], now=datetime(2026, 11, 9, tzinfo=UTC)) == 0


def test_shifted_moves_start_and_end_and_keeps_the_rest():
    post = {'title': 'T', 'startsAt': '2026-11-05T18:00:00Z', 'endsAt': '2026-11-05T20:00:00Z'}

    assert shifted(post, 3) == {'title': 'T', 'startsAt': '2026-11-08T18:00:00Z', 'endsAt': '2026-11-08T20:00:00Z'}
    assert shifted({'startsAt': '2026-11-05T18:00:00Z'}, 1) == {'startsAt': '2026-11-06T18:00:00Z'}


class FakeApi:
    def __init__(self, existing=(), failure=None):
        self.existing = set(existing)
        self.failure = failure
        self.created = []

    def existing_event_urls(self):
        return self.existing

    def create_post(self, post, images=()):
        self.created.append((post, [image.content for image in images]))
        return Outcome(self.failure) if self.failure else Outcome(photos=len(self.created[-1][1]))


def saved_snapshot(directory, count=3):
    snapshot = Snapshot(directory)
    for number in range(count):
        snapshot.create_post({'eventUrl': f'https://site.test/{number}', 'startsAt': '2026-11-05T18:00:00Z',
                              'tags': ['koncerty']}, [Image(b'photo%d' % number, 'image/webp', f'{number}.webp', 4, 3)])
    snapshot.mark_complete()


def test_events_are_loaded_once_with_photos_and_moved_dates(tmp_path):
    saved_snapshot(tmp_path)
    api = FakeApi(existing={'https://site.test/0'})

    stats = seed_events(tmp_path, 'http://api.test', workers=2, clock=lambda: datetime(2026, 11, 9, 12, tzinfo=UTC),
                        api_factory=lambda: api)

    assert stats['created'] == 2 and stats['already_loaded'] == 1 and stats['photos'] == 2
    assert sorted(post['eventUrl'] for post, _ in api.created) == ['https://site.test/1', 'https://site.test/2']
    assert {post['startsAt'] for post, _ in api.created} == {'2026-11-09T18:00:00Z'}
    assert sorted(photos[0] for _, photos in api.created) == [b'photo1', b'photo2']


def test_a_refused_event_is_counted(tmp_path):
    saved_snapshot(tmp_path, count=1)

    stats = seed_events(tmp_path, 'http://api.test', clock=lambda: datetime(2026, 11, 9, tzinfo=UTC),
                        api_factory=lambda: FakeApi(failure='400: bad'))

    assert stats['rejected'] == 1


def test_no_snapshot_is_one_clear_error(tmp_path):
    with pytest.raises(SeedError, match='make scrape-snapshot'):
        seed_events(tmp_path, 'http://api.test', api_factory=FakeApi)


def test_picks_leave_the_closest_posts_for_the_feed_and_dislike_the_farthest():
    ranking = [f'p{rank}' for rank in range(30)]

    assert picks(ranking) == [('p3', 'reaction', 'LIKE'), ('p6', 'reaction', 'LIKE'), ('p9', 'reaction', 'LIKE'),
                              ('p12', 'reaction', 'LIKE'), ('p15', 'reaction', 'LIKE'),
                              ('p4', 'participation', 'INTERESTED'), ('p10', 'participation', 'INTERESTED'),
                              ('p28', 'reaction', 'DISLIKE'), ('p29', 'reaction', 'DISLIKE')]


def test_picks_from_a_short_ranking_never_react_twice_to_one_post():
    chosen = picks(['p0', 'p1', 'p2', 'p3', 'p4'])

    assert len({post_id for post_id, _, _ in chosen}) == len(chosen)


class FakeSession:
    def __init__(self, status=200):
        self.headers = {}
        self.status = status
        self.sent = []

    def put(self, url, json, timeout):
        self.sent.append((self.headers['X-User-Name'], url, json['type']))
        response = requests.Response()
        response.status_code = self.status
        return response


def fake_query(reacted=()):
    def query(sql):
        if 'count(*)' in sql:
            return ['1' if any(user_id in sql for user_id in reacted) else '0']
        assert BOT_ID in sql
        return [f'p{rank}' for rank in range(30)]
    return query


def test_each_demo_user_reacts_as_themself():
    session = FakeSession()

    stats = seed_reactions('http://api.test', query=fake_query(), session_factory=lambda: session)

    assert stats['users'] == 3 and stats['like'] == 15 and stats['interested'] == 6 and stats['dislike'] == 6
    assert ('anna_demo', 'http://api.test/posts/p3/reaction', 'LIKE') in session.sent
    assert ('ola_demo', 'http://api.test/posts/p4/participation', 'INTERESTED') in session.sent


def test_a_user_who_already_reacted_is_left_alone():
    session = FakeSession()

    stats = seed_reactions('http://api.test', query=fake_query(reacted=['00000000-0000-0000-0000-000000000002']),
                           session_factory=lambda: session)

    assert stats['users_skipped'] == 1
    assert not [sent for sent in session.sent if sent[0] == 'anna_demo']


def test_reactions_before_embeddings_say_what_to_run():
    with pytest.raises(SeedError, match='embed-posts'):
        seed_reactions('http://api.test', query=lambda sql: ['0'] if 'count(*)' in sql else [],
                       session_factory=FakeSession)


def test_a_refused_reaction_stops_with_the_answer():
    with pytest.raises(SeedError, match='returned 403'):
        seed_reactions('http://api.test', query=fake_query(), session_factory=lambda: FakeSession(status=403))


def test_waiting_for_the_api_ends_when_it_answers(monkeypatch):
    answers = iter([requests.ConnectionError('refused'), 200])

    def get(url, params, timeout):
        answer = next(answers)
        if isinstance(answer, Exception):
            raise answer
        response = requests.Response()
        response.status_code = answer
        return response

    monkeypatch.setattr(seed.requests, 'get', get)
    slept = []

    wait_for_api('http://api.test', sleep=slept.append)

    assert slept == [seed.API_POLL_S]


def test_waiting_for_the_api_gives_up_with_a_hint(monkeypatch):
    def refuse(url, params, timeout):
        raise requests.ConnectionError('refused')

    monkeypatch.setattr(seed.requests, 'get', refuse)
    clock = iter([0, 10, 20])

    with pytest.raises(SeedError, match='make local'):
        wait_for_api('http://api.test', timeout_s=15, sleep=lambda s: None, monotonic=lambda: next(clock))
