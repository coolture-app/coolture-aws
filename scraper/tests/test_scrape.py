import io
import json
import logging
import sys
from collections import Counter
from datetime import date, datetime, time, timedelta, timezone

import pytest
import requests

from api import MAX_PHOTOS, ApiError, Outcome
from geocoder import Geocoder, GeocoderUnavailable, Place
from images import Image
from scrape import DryRun, main, run
from trojmiasto import EventPage, ListingItem, merge

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
        self.downloaded = []

    def listing_items(self):
        return iter(self.items)

    def event(self, listed):
        self.opened.append(listed.url)
        return merge(listed, self.pages.get(listed.url, page()))

    def image(self, url):
        self.downloaded.append(url)
        return None if 'broken' in url else Image(url.encode(), 'image/webp', url.rsplit('/', 1)[-1], 4, 3)


class FakeGeocoder:
    def __init__(self, place=PLACE, postcode='80-001'):
        self.place = place
        self.postcode = postcode
        self.calls = 0
        self.venues = []
        self.reverse_calls = 0

    def locate(self, event):
        self.calls += 1
        self.venues.append(event.venue)
        return self.place

    def postcode_at(self, place):
        self.reverse_calls += 1
        return self.postcode


class FakeApi:
    def __init__(self, existing=(), failure=None, refuse_images=0):
        self.existing = set(existing)
        self.failure = failure
        self.refuse_images = refuse_images
        self.created = []

    def existing_event_urls(self):
        return self.existing

    def create_post(self, post, images=()):
        names = [image.file_name for image in images][self.refuse_images:]
        self.created.append({**post, 'mediaIds': names} if names else post)
        return Outcome(self.failure) if self.failure else Outcome(photos=len(names))


def run_import(site, geocoder, api, limit=None, clock=lambda: NOW):
    stats = Counter()
    run(site, geocoder, api, stats, limit, clock)
    return stats


def test_new_event_is_created_with_coordinates():
    api = FakeApi()

    stats = run_import(FakeSite([item(1)]), FakeGeocoder(), api)

    assert stats['created'] == 1
    assert stats['exact_location'] == 1
    assert api.created[0]['location']['coordinates'] == {'latitude': 54.35, 'longitude': 18.65}


def test_imported_event_is_not_opened_or_sent_again():
    site = FakeSite([item(1), item(2)])
    api = FakeApi(existing={item(1).url})

    stats = run_import(site, FakeGeocoder(), api)

    assert stats['already_imported'] == 1
    assert site.opened == [item(2).url]
    assert len(api.created) == 1


def test_started_event_is_skipped_without_a_geocoder_request():
    geocoder = FakeGeocoder()
    site = FakeSite([item(1)], {item(1).url: page(start_date=date(2026, 11, 1))})

    stats = run_import(site, geocoder, FakeApi())

    assert stats['already_started'] == 1
    assert geocoder.calls == 0


def test_event_with_no_location_is_left_out():
    api = FakeApi()

    stats = run_import(FakeSite([item(1)]), FakeGeocoder(place=None), api)

    assert stats['no_location'] == 1
    assert api.created == []


def test_city_level_location_is_counted_apart():
    stats = run_import(FakeSite([item(1)]), FakeGeocoder(Place(54.3, 18.6, None, exact=False)), FakeApi())

    assert stats['city_location'] == 1


def test_online_event_needs_no_geocoder():
    geocoder = FakeGeocoder()
    site = FakeSite([item(1)], {item(1).url: page(online=True)})

    stats = run_import(site, geocoder, FakeApi())

    assert stats['online'] == 1
    assert geocoder.calls == 0


def test_event_with_no_date_is_left_out():
    site = FakeSite([item(1)], {item(1).url: page(start_date=None)})

    assert run_import(site, FakeGeocoder(), FakeApi())['unreadable'] == 1


def test_listing_venue_reaches_the_geocoder():
    geocoder = FakeGeocoder()
    site = FakeSite([item(1)], {item(1).url: page(venue=None)})

    run_import(site, geocoder, FakeApi())

    assert geocoder.venues == ['Klub']


def test_unreadable_page_is_skipped():
    class Unreadable(FakeSite):
        def event(self, listed):
            return None

    stats = run_import(Unreadable([item(1)]), FakeGeocoder(), FakeApi())

    assert stats['unreadable'] == 1


def test_no_postal_code_anywhere_is_left_out():
    site = FakeSite([item(1)], {item(1).url: page(postal_code=None)})
    api = FakeApi()

    stats = run_import(site, FakeGeocoder(postcode=None), api)

    assert stats['no_location'] == 1
    assert api.created == []


def test_postal_code_of_the_page_needs_no_reverse_lookup():
    geocoder = FakeGeocoder(Place(54.3, 18.6, None, exact=True))
    api = FakeApi()

    run_import(FakeSite([item(1)]), geocoder, api)

    assert geocoder.reverse_calls == 0
    assert api.created[0]['location']['postalCode'] == '81-350'


def test_postal_code_of_the_hit_needs_no_reverse_lookup():
    geocoder = FakeGeocoder(Place(54.3, 18.6, '80-002', exact=True))
    api = FakeApi()

    run_import(FakeSite([item(1)], {item(1).url: page(postal_code=None)}), geocoder, api)

    assert geocoder.reverse_calls == 0
    assert api.created[0]['location']['postalCode'] == '80-002'


def test_reverse_lookup_runs_when_neither_has_a_postal_code():
    geocoder = FakeGeocoder(Place(54.3, 18.6, None, exact=False))
    api = FakeApi()

    run_import(FakeSite([item(1)], {item(1).url: page(postal_code=None)}), geocoder, api)

    assert geocoder.reverse_calls == 1
    assert api.created[0]['location']['postalCode'] == '80-001'


def test_an_event_that_starts_during_the_run_is_not_sent():
    start = datetime(2026, 11, 5, 18, 0, tzinfo=timezone.utc)
    moments = iter([start - timedelta(minutes=5), start + timedelta(minutes=1)])
    api = FakeApi()

    stats = run_import(FakeSite([item(1), item(2)]), FakeGeocoder(), api, clock=lambda: next(moments))

    assert stats['created'] == 1
    assert stats['already_started'] == 1


def test_rejected_post_is_counted_and_the_run_goes_on():
    api = FakeApi(failure='400: nope')

    stats = run_import(FakeSite([item(1), item(2)]), FakeGeocoder(), api)

    assert stats['rejected'] == 2
    assert stats['created'] == 0


def test_limit_stops_after_that_many_new_posts():
    api = FakeApi()

    run_import(FakeSite([item(1), item(2), item(3)]), FakeGeocoder(), api, limit=2)

    assert len(api.created) == 2


def test_dry_run_prints_posts_and_needs_no_api(capsys):
    stats = run_import(FakeSite([item(1)]), FakeGeocoder(), DryRun())

    assert stats['created'] == 1
    assert json.loads(capsys.readouterr().out)['title'] == 'Wydarzenie 1'


def test_dry_run_prints_an_emoji_through_a_code_page_pipe(monkeypatch):
    pipe = io.TextIOWrapper(io.BytesIO(), encoding='cp1250')
    monkeypatch.setattr(sys, 'stdout', pipe)

    DryRun().create_post({'title': 'Koncert 😀'}, [])

    pipe.flush()
    assert json.loads(pipe.buffer.getvalue().decode('utf-8'))['title'] == 'Koncert 😀'


def test_a_geocoder_failure_keeps_the_totals_and_creates_nothing_more():
    class Throttled:
        def get(self, url, params, timeout):
            response = requests.Response()
            response.status_code = 429
            return response

    stats, api = Counter(), FakeApi()
    geocoder = Geocoder(Throttled(), 'http://nominatim.test', sleep=lambda _: None)

    with pytest.raises(GeocoderUnavailable):
        run(FakeSite([item(1), item(2)]), geocoder, api, stats, None, lambda: NOW)

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
    def fail(site, geocoder, api, stats, limit):
        stats['listed'] = 3
        raise error

    monkeypatch.setattr('scrape.run', fail)

    assert main() == 1
    assert "Done: {'listed': 3}" in caplog.text
    assert [record.levelname for record in caplog.records].count('ERROR') == 1


def test_a_network_error_names_the_request_in_one_line(monkeypatch, caplog, scraper_args):
    request = requests.Request('GET', 'http://localhost:3000/auth/me').prepare()

    def fail(*_):
        raise requests.ConnectionError('HTTPConnectionPool(...): Max retries exceeded', request=request)

    monkeypatch.setattr('scrape.run', fail)

    assert main() == 1
    assert 'Run stopped: GET http://localhost:3000/auth/me failed: ConnectionError' in caplog.text
    assert 'HTTPConnectionPool' not in caplog.text


def test_a_failed_status_is_named_too(monkeypatch, caplog, scraper_args):
    request = requests.Request('GET', 'https://site.test/imprezy').prepare()
    response = requests.Response()
    response.status_code = 503

    def fail(*_):
        raise requests.HTTPError('503 Server Error', request=request, response=response)

    monkeypatch.setattr('scrape.run', fail)

    assert main() == 1
    assert 'Run stopped: GET https://site.test/imprezy failed: 503' in caplog.text


def test_a_missing_identity_is_one_clear_error(monkeypatch, caplog, scraper_args):
    monkeypatch.delenv('SCRAPER_USER_ID')

    assert main() == 1
    assert 'SCRAPER_USER_ID' in caplog.text


def test_every_photo_of_the_event_is_sent_in_order():
    api = FakeApi()
    urls = ('https://img.test/cover.webp', 'https://img.test/1.webp', 'https://img.test/2.webp')

    stats = run_import(FakeSite([item(1)], {item(1).url: page(image_urls=urls)}), FakeGeocoder(), api)

    assert api.created[0]['mediaIds'] == ['cover.webp', '1.webp', '2.webp']
    assert (stats['with_image'], stats['photos']) == (1, 3)


def test_a_photo_that_cannot_be_downloaded_is_left_out():
    api = FakeApi()
    urls = ('https://img.test/broken.webp', 'https://img.test/1.webp')

    run_import(FakeSite([item(1)], {item(1).url: page(image_urls=urls)}), FakeGeocoder(), api)

    assert api.created[0]['mediaIds'] == ['1.webp']


def test_event_with_no_image_is_posted_without_one():
    api = FakeApi()

    stats = run_import(FakeSite([item(1)]), FakeGeocoder(), api)

    assert 'mediaIds' not in api.created[0]
    assert stats['with_image'] == 0


def test_a_post_whose_images_were_all_refused_does_not_count_as_with_image():
    api = FakeApi(refuse_images=1)

    stats = run_import(FakeSite([item(1)], {item(1).url: page(image_urls=('https://img.test/1.webp',))}),
                       FakeGeocoder(), api)

    assert (stats['created'], stats['with_image']) == (1, 0)


def test_a_rejected_post_does_not_count_as_with_image():
    api = FakeApi(failure='400: nope')

    stats = run_import(FakeSite([item(1)], {item(1).url: page(image_urls=('https://img.test/1.webp',))}),
                       FakeGeocoder(), api)

    assert (stats['rejected'], stats['with_image'], stats['photos']) == (1, 0, 0)


def test_dry_run_prints_what_it_would_upload(capsys):
    site = FakeSite([item(1)], {item(1).url: page(image_urls=('https://img.test/1.webp',))})

    stats = run_import(site, FakeGeocoder(), DryRun())

    assert json.loads(capsys.readouterr().out)['images'] == [
        {'fileName': '1.webp', 'mimeType': 'image/webp', 'sizeBytes': len(b'https://img.test/1.webp'),
         'width': 4, 'height': 3}]
    assert stats['with_image'] == 1


def test_dry_run_stops_downloading_at_the_api_limit(capsys):
    urls = tuple(f'https://img.test/{number}.webp' for number in range(MAX_PHOTOS + 2))
    site = FakeSite([item(1)], {item(1).url: page(image_urls=urls)})

    run_import(site, FakeGeocoder(), DryRun())

    assert len(json.loads(capsys.readouterr().out)['images']) == MAX_PHOTOS
    assert len(site.downloaded) == MAX_PHOTOS
