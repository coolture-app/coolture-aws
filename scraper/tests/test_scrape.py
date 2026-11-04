import json
import logging
import sys
from collections import Counter
from datetime import date, datetime, time, timezone

import pytest
import requests

from api import ApiError
from geocoder import Geocoder, GeocoderUnavailable, Place
from scrape import main, run
from trojmiasto import EventPage, ListingItem

NOW = datetime(2026, 11, 2, 12, 0, tzinfo=timezone.utc)
PLACE = Place(54.35, 18.65, None, exact=True)


def item(number, city='Gdańsk'):
    return ListingItem(f'https://site.test/{number}', f'Wydarzenie {number}', 'koncerty', city, 'Klub', None, time(19, 0))


def page(**overrides):
    fields = dict(start_date=date(2026, 11, 5), end=None, online=False, description='Opis.', venue='Klub',
                  street='Portowa', building_number='9', postal_code='81-350', hour=None)
    return EventPage(**{**fields, **overrides})


class FakeSite:
    def __init__(self, items, pages=None):
        self.items = items
        self.pages = pages or {}
        self.opened = []

    def listing_items(self):
        return iter(self.items)

    def event_page(self, url):
        self.opened.append(url)
        return self.pages.get(url, page())


class FakeGeocoder:
    def __init__(self, place=PLACE):
        self.place = place
        self.calls = 0
        self.venues = []

    def locate(self, venue, *_):
        self.calls += 1
        self.venues.append(venue)
        return self.place


class FakeApi:
    def __init__(self, existing=(), failure=None):
        self.existing = set(existing)
        self.failure = failure
        self.created = []
        self.asked_for = None

    def me(self):
        return 'bot-id'

    def existing_event_urls(self, author_id):
        self.asked_for = author_id
        return self.existing

    def create_post(self, post):
        self.created.append(post)
        return self.failure


def run_import(site, geocoder, api, now, limit):
    stats = Counter()
    run(site, geocoder, api, stats, now, limit)
    return stats


def test_new_event_is_created_with_coordinates():
    api = FakeApi()

    stats = run_import(FakeSite([item(1)]), FakeGeocoder(), api, NOW, None)

    assert stats['created'] == 1
    assert stats['exact_location'] == 1
    assert api.created[0]['location']['coordinates'] == {'latitude': 54.35, 'longitude': 18.65}
    assert api.asked_for == 'bot-id'


def test_imported_event_is_not_opened_or_sent_again():
    site = FakeSite([item(1), item(2)])
    api = FakeApi(existing={item(1).url})

    stats = run_import(site, FakeGeocoder(), api, NOW, None)

    assert stats['already_imported'] == 1
    assert site.opened == [item(2).url]
    assert len(api.created) == 1


def test_started_event_is_skipped_without_a_geocoder_request():
    geocoder = FakeGeocoder()
    site = FakeSite([item(1)], {item(1).url: page(start_date=date(2026, 11, 1))})

    stats = run_import(site, geocoder, FakeApi(), NOW, None)

    assert stats['already_started'] == 1
    assert geocoder.calls == 0


def test_event_with_no_location_is_left_out():
    api = FakeApi()

    stats = run_import(FakeSite([item(1)]), FakeGeocoder(place=None), api, NOW, None)

    assert stats['no_location'] == 1
    assert api.created == []


def test_city_level_location_is_counted_apart():
    stats = run_import(FakeSite([item(1)]), FakeGeocoder(Place(54.3, 18.6, None, exact=False)), FakeApi(), NOW, None)

    assert stats['city_location'] == 1


def test_online_event_needs_no_geocoder():
    geocoder = FakeGeocoder()
    site = FakeSite([item(1)], {item(1).url: page(online=True)})

    stats = run_import(site, geocoder, FakeApi(), NOW, None)

    assert stats['online'] == 1
    assert geocoder.calls == 0


def test_event_with_no_date_is_left_out():
    site = FakeSite([item(1)], {item(1).url: page(start_date=None)})

    assert run_import(site, FakeGeocoder(), FakeApi(), NOW, None)['unreadable'] == 1


def test_listing_venue_reaches_the_geocoder():
    geocoder = FakeGeocoder()
    site = FakeSite([item(1)], {item(1).url: page(venue=None)})

    run_import(site, geocoder, FakeApi(), NOW, None)

    assert geocoder.venues == ['Klub']


def test_unreadable_page_is_skipped():
    class Unreadable(FakeSite):
        def event_page(self, url):
            return None

    stats = run_import(Unreadable([item(1)]), FakeGeocoder(), FakeApi(), NOW, None)

    assert stats['unreadable'] == 1


def test_rejected_post_is_counted_and_the_run_goes_on():
    api = FakeApi(failure='400: nope')

    stats = run_import(FakeSite([item(1), item(2)]), FakeGeocoder(), api, NOW, None)

    assert stats['rejected'] == 2
    assert stats['created'] == 0


def test_limit_stops_after_that_many_new_posts():
    api = FakeApi()

    run_import(FakeSite([item(1), item(2), item(3)]), FakeGeocoder(), api, NOW, 2)

    assert len(api.created) == 2


def test_dry_run_prints_posts_and_needs_no_api(capsys):
    stats = run_import(FakeSite([item(1)]), FakeGeocoder(), None, NOW, None)

    assert stats['created'] == 1
    assert json.loads(capsys.readouterr().out)['title'] == 'Wydarzenie 1'


def test_a_geocoder_failure_keeps_the_totals_and_creates_nothing_more():
    class Throttled:
        def get(self, url, params, timeout):
            response = requests.Response()
            response.status_code = 429
            return response

    stats, api = Counter(), FakeApi()
    geocoder = Geocoder(Throttled(), 'http://nominatim.test', sleep=lambda _: None)

    with pytest.raises(GeocoderUnavailable):
        run(FakeSite([item(1), item(2)]), geocoder, api, stats, NOW, None)

    assert stats['listed'] == 1
    assert stats['no_location'] == 0
    assert api.created == []


@pytest.fixture
def scraper_args(monkeypatch, caplog):
    monkeypatch.setattr(sys, 'argv', ['scrape.py'])
    monkeypatch.setenv('SCRAPER_USER_ID', 'bot')
    monkeypatch.delenv('SCRAPER_API_TOKEN', raising=False)
    caplog.set_level(logging.INFO)


@pytest.mark.parametrize('error', [
    ApiError('GET /posts returned 500'),
    GeocoderUnavailable('Nominatim request failed'),
    requests.ConnectionError('refused'),
    requests.Timeout('slow'),
    requests.exceptions.JSONDecodeError('Expecting value', '<html>', 0),
])
def test_a_failed_run_logs_one_error_and_the_totals_and_exits_1(monkeypatch, caplog, scraper_args, error):
    def fail(site, geocoder, api, stats, now, limit):
        stats['listed'] = 3
        raise error

    monkeypatch.setattr('scrape.run', fail)

    assert main() == 1
    assert "Done: {'listed': 3}" in caplog.text
    assert [record.levelname for record in caplog.records].count('ERROR') == 1


def test_a_missing_identity_is_one_clear_error(monkeypatch, caplog, scraper_args):
    monkeypatch.delenv('SCRAPER_USER_ID')

    assert main() == 1
    assert 'SCRAPER_USER_ID' in caplog.text
