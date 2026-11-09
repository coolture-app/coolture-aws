from collections import Counter
from datetime import date, datetime, time, timezone

from images import Image
from scrape import run_snapshot
from snapshot import Snapshot, allocate, category_of, is_complete, load_images, read, spread_over_dates
from trojmiasto import ListingItem

from test_scrape import NOW, FakeGeocoder, FakeSite

# The listing on 2026-11-09.
LISTING = {'koncerty': 121, 'spektakle': 79, 'imprezy rozrywkowe': 53, 'wystawy': 48, 'film': 35, 'sport': 32,
           'okolicznościowe': 15, 'targi': 9, 'kulinaria': 8}


def listed(number, category='koncerty', day=date(2026, 11, 5)):
    return ListingItem(f'https://site.test/{number}', f'Wydarzenie {number}', category, 'Gdańsk', 'Klub', day,
                       time(19, 0))


def test_allocation_follows_the_square_root_of_supply_and_adds_up():
    quotas = allocate(LISTING, 120)

    assert quotas == {'koncerty': 24, 'spektakle': 19, 'imprezy rozrywkowe': 16, 'wystawy': 15, 'film': 13, 'sport': 12,
                      'okolicznościowe': 8, 'targi': 7, 'kulinaria': 6}
    assert sum(quotas.values()) == 120


def test_a_category_never_gets_more_than_it_has_and_the_rest_goes_to_the_others():
    quotas = allocate({'big': 100, 'tiny': 1}, 20)

    assert quotas == {'big': 19, 'tiny': 1}


def test_asking_for_more_than_the_listing_gives_everything():
    assert allocate({'a': 3, 'b': 2}, 50) == {'a': 3, 'b': 2}


def test_spread_takes_one_event_of_each_day_before_a_second_of_any():
    items = [listed(1, day=date(2026, 11, 5)), listed(2, day=date(2026, 11, 5)), listed(3, day=date(2026, 11, 6)),
             listed(4, day=date(2026, 11, 7)), listed(5, day=None)]

    assert [item.url[-1] for item in spread_over_dates(items)] == ['1', '3', '4', '5', '2']


def test_category_is_the_first_part_of_the_sites_label():
    assert category_of(listed(1, 'wystawy, spotkania')) == 'wystawy'
    assert category_of(listed(1, None)) == '-'


def test_snapshot_keeps_the_post_and_its_photos(tmp_path):
    snapshot = Snapshot(tmp_path, clock=lambda: NOW)
    post = {'title': 'Koncert', 'eventUrl': 'https://site.test/1', 'startsAt': '2026-11-05T18:00:00Z', 'tags': ['koncerty']}

    outcome = snapshot.create_post(post, [Image(b'one', 'image/webp', 'a.webp', 4, 3), Image(b'two', 'image/jpeg', 'b.jpg')])

    [entry] = read(tmp_path)
    assert outcome.photos == 2
    assert entry.post == post
    assert entry.scraped_at == NOW
    assert entry.category == 'koncerty'
    assert [(image.content, image.mime_type, image.file_name, image.width) for image in load_images(tmp_path, entry)] == [
        (b'one', 'image/webp', 'a.webp', 4), (b'two', 'image/jpeg', 'b.jpg', None)]
    assert snapshot.existing_event_urls() == {'https://site.test/1'}


def test_snapshot_run_samples_every_category_and_marks_it_complete(tmp_path):
    items = [listed(n, 'koncerty') for n in range(10)] + [listed(n, 'kulinaria') for n in range(10, 12)]
    stats = Counter()

    run_snapshot(FakeSite(items), FakeGeocoder(), Snapshot(tmp_path), stats, size=6, clock=lambda: NOW)

    # The square roots of 10 and 2 split six as 4 and 2.
    assert Counter(entry.category for entry in read(tmp_path)) == {'koncerty': 4, 'kulinaria': 2}
    assert is_complete(tmp_path)
    assert stats['listed'] == 12


def test_a_second_run_only_fills_what_is_missing(tmp_path):
    items = [listed(n, 'koncerty') for n in range(10)]
    run_snapshot(FakeSite(items), FakeGeocoder(), Snapshot(tmp_path), Counter(), size=3, clock=lambda: NOW)
    site = FakeSite(items)

    run_snapshot(site, FakeGeocoder(), Snapshot(tmp_path), Counter(), size=5, clock=lambda: NOW)

    assert len(read(tmp_path)) == 5
    assert len(site.opened) == 2


def test_events_listed_before_today_take_no_share(tmp_path):
    items = [listed(1, 'wystawy', day=date(2026, 10, 1)), listed(2, 'koncerty')]

    run_snapshot(FakeSite(items), FakeGeocoder(), Snapshot(tmp_path), Counter(), size=2, clock=lambda: NOW)

    assert [entry.post['eventUrl'] for entry in read(tmp_path)] == ['https://site.test/2']


def test_an_unfinished_run_leaves_the_snapshot_incomplete(tmp_path):
    Snapshot(tmp_path).mark_complete()

    Snapshot(tmp_path)

    assert not is_complete(tmp_path)


def test_whole_listing_when_size_is_none(tmp_path):
    items = [listed(n, 'koncerty') for n in range(4)] + [listed(9, 'sport')]

    run_snapshot(FakeSite(items), FakeGeocoder(), Snapshot(tmp_path), Counter(), size=None,
                 clock=lambda: datetime(2026, 11, 2, tzinfo=timezone.utc))

    assert len(read(tmp_path)) == 5
