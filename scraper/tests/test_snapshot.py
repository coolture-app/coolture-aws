import json
from collections import Counter
from datetime import date

from fakes import NOW, FakeGeocoder, FakeSite, listed
from images import Image
from scrape import run
from snapshot import Snapshot, allocate, is_complete, load_images, read, sample, spread_over_dates, top_category

# The listing on 2026-11-09.
LISTING = {'koncerty': 121, 'spektakle': 79, 'imprezy rozrywkowe': 53, 'wystawy': 48, 'film': 35, 'sport': 32,
           'okolicznościowe': 15, 'targi': 9, 'kulinaria': 8}


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
    items = [listed(1, start_date=date(2026, 11, 5)), listed(2, start_date=date(2026, 11, 5)), listed(3, start_date=date(2026, 11, 6)),
             listed(4, start_date=date(2026, 11, 7)), listed(5, start_date=None)]

    assert [item.url[-1] for item in spread_over_dates(items)] == ['1', '3', '4', '5', '2']


def test_category_is_the_first_part_of_the_sites_label():
    assert top_category('wystawy, spotkania') == 'wystawy'
    assert top_category(None) == '-'


def test_snapshot_keeps_the_post_and_its_photos(tmp_path):
    snapshot = Snapshot(tmp_path, clock=lambda: NOW)
    post = {'title': 'Koncert', 'eventUrl': 'https://site.test/1', 'startsAt': '2026-11-05T18:00:00Z'}

    outcome = snapshot.create_post(post, [Image(b'one', 'image/webp', 'a.webp', 4, 3), Image(b'two', 'image/jpeg', 'b.jpg')],
                                   category='koncerty')

    [entry] = read(tmp_path)
    assert outcome.photos == 2
    assert entry.post == post
    assert entry.category == 'koncerty'
    assert entry.scraped_at == NOW
    assert [(image.content, image.mime_type, image.file_name, image.width) for image in load_images(tmp_path, entry)] == [
        (b'one', 'image/webp', 'a.webp', 4), (b'two', 'image/jpeg', 'b.jpg', None)]
    assert snapshot.existing_event_urls() == {'https://site.test/1'}


def test_a_snapshot_saved_with_tags_gives_them_as_its_category(tmp_path):
    line = {'scrapedAt': NOW.isoformat(), 'images': [],
            'post': {'eventUrl': 'https://site.test/1', 'tags': ['wystawy', 'spotkania']}}
    (tmp_path / 'posts.jsonl').write_text(json.dumps(line) + '\n', encoding='utf-8')

    [entry] = read(tmp_path)

    assert entry.category == 'wystawy, spotkania'


def test_a_second_run_only_fills_what_is_missing(tmp_path):
    items = [listed(n) for n in range(3)]
    run(FakeSite(items[:2]), FakeGeocoder(), Snapshot(tmp_path), Counter(), None, clock=lambda: NOW)
    site = FakeSite(items)

    run(site, FakeGeocoder(), Snapshot(tmp_path), Counter(), None, clock=lambda: NOW)

    assert len(read(tmp_path)) == 3
    assert len(site.opened) == 1


def test_an_unfinished_run_leaves_the_snapshot_incomplete(tmp_path):
    Snapshot(tmp_path).mark_complete()

    Snapshot(tmp_path)

    assert not is_complete(tmp_path)


def saved(directory, events):
    """events: (category, start in UTC) each, saved in this order."""
    snapshot = Snapshot(directory)
    for number, (category, starts_at) in enumerate(events):
        snapshot.create_post({'eventUrl': f'https://site.test/{number}', 'startsAt': starts_at}, category=category)
    return read(directory)


def test_a_sample_shares_out_the_categories_and_spreads_each_over_the_days(tmp_path):
    concerts = [('koncerty, jazz', f'2026-11-{day:02d}T18:00:00Z') for day in (5, 5, 5, 6, 6, 7)]
    films = [('film', '2026-11-05T18:00:00Z'), ('film', '2026-11-08T22:30:00Z')]
    entries = saved(tmp_path, concerts + films)

    chosen = sample(entries, 5)

    # allocate gives 3 to 6 concerts and 2 to 2 films; 22:30 UTC on 8 November is already the 9th in Warsaw.
    assert [entry.post['eventUrl'][-1] for entry in chosen] == ['0', '3', '5', '6', '7']
    assert sample(entries, 5) == chosen
    assert sample(entries, 100) == entries
