import argparse
import json
import logging
import os
import sys
from collections import Counter, defaultdict
from collections.abc import Callable, Iterable
from datetime import datetime, timezone
from itertools import islice
from pathlib import Path

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from api import MAX_PHOTOS, ApiError, CooltureApi, Outcome
from geocoder import DEFAULT_URL, Geocoder, GeocoderUnavailable
from images import Image
from posts import to_post
from snapshot import DEFAULT_SIZE, Snapshot, allocate, category_of, spread_over_dates
from trojmiasto import WARSAW, ListingItem, Site

log = logging.getLogger('scraper')

USER_AGENT = 'coolture-scraper/1.0 (+https://github.com/coolture-app/coolture-aws)'
DEFAULT_API_URL = 'http://localhost:3000'
# A longer wait would stall the whole run, and urllib3's own limit is 6 hours.
RETRY_AFTER_MAX_S = 120


class PacedRetry(Retry):
    # Only a throttle is retried, because a 5xx may come after a post was saved, whatever Retry-After says.
    RETRY_AFTER_STATUS_CODES = frozenset({429})

    def get_backoff_time(self) -> float:
        # urllib3 sends the first retry at once, which would break the site's limit of one request a second.
        return max(super().get_backoff_time(), self.backoff_factor)


def http_session(retry_timeouts: bool = False) -> requests.Session:
    # read=False keeps a POST from being sent again after a timeout, and lets the timeout arrive as requests.Timeout.
    # The site and the geocoder only get GETs, which are safe to send again, and one slow answer shouldn't end a run.
    retry = PacedRetry(total=3, connect=2, read=2 if retry_timeouts else False, status_forcelist={429}, backoff_factor=2,
                       allowed_methods=None, respect_retry_after_header=True, retry_after_max=RETRY_AFTER_MAX_S,
                       raise_on_status=False)
    session = requests.Session()
    session.headers['User-Agent'] = USER_AGENT
    session.mount('https://', HTTPAdapter(max_retries=retry))
    session.mount('http://', HTTPAdapter(max_retries=retry))
    return session


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class DryRun:
    """Takes the place of the API, and prints the posts instead of sending them."""

    def __init__(self) -> None:
        # A Windows pipe defaults to a code page, which can't hold an emoji in a title.
        sys.stdout.reconfigure(encoding='utf-8')

    def existing_event_urls(self) -> set[str]:
        return set()

    def create_post(self, post: dict, images: Iterable[Image] = ()) -> Outcome:
        uploads = [{'fileName': image.file_name, 'mimeType': image.mime_type, 'sizeBytes': len(image.content),
                    'width': image.width, 'height': image.height} for image in islice(images, MAX_PHOTOS)]
        print(json.dumps({**post, 'images': uploads} if uploads else post, ensure_ascii=False))
        return Outcome(photos=len(uploads))


def run(site: Site, geocoder: Geocoder, sink: CooltureApi | DryRun, stats: Counter, limit: int | None,
        clock: Callable[[], datetime] = utc_now, stop_at: datetime | None = None) -> None:
    """Imports what the API doesn't have yet. `stop_at` ends the run between two events, as a Lambda's time runs out;
    the next run goes on, because it skips what this one created."""
    existing = sink.existing_event_urls()

    for item in site.listing_items():
        stats['listed'] += 1
        if item.url in existing:
            stats['already_imported'] += 1
            continue
        if limit is not None and stats['created'] >= limit:
            break
        if stop_at is not None and clock() >= stop_at:
            stats['stopped_for_time'] += 1
            break
        import_item(item, site, geocoder, sink, stats, clock)


def run_snapshot(site: Site, geocoder: Geocoder, snapshot: Snapshot, stats: Counter, size: int | None,
                 clock: Callable[[], datetime] = utc_now) -> None:
    """Saves a sample of the listing that covers every category and spreads over its dates, or all of it when size is None.

    The listing comes sorted by category, so its first events would all be concerts. A run adds to what the snapshot
    already holds, so one that stopped goes on where it was.
    """
    today = clock().astimezone(WARSAW).date()
    groups: dict[str, list[ListingItem]] = defaultdict(list)
    for item in site.listing_items():
        stats['listed'] += 1
        # One that started before today would be skipped anyway, and shouldn't take a share of the quota.
        if item.start_date is None or item.start_date >= today:
            groups[category_of(item)].append(item)
    supply = {category: len(items) for category, items in groups.items()}
    quotas = supply if size is None else allocate(supply, size)
    existing = snapshot.existing_event_urls()

    for category in sorted(groups, key=lambda name: (-quotas[name], name)):
        items = spread_over_dates(groups[category])
        count = sum(item.url in existing for item in items)
        for item in items:
            if count >= quotas[category]:
                break
            if item.url not in existing and import_item(item, site, geocoder, snapshot, stats, clock):
                count += 1
        log.info('Category %s: %d of %d (listed %d)', category, count, quotas[category], supply[category])
    snapshot.mark_complete()


def import_item(item: ListingItem, site: Site, geocoder: Geocoder, sink: CooltureApi | DryRun | Snapshot,
                stats: Counter, clock: Callable[[], datetime]) -> bool:
    """Reads one listed event, locates it and sends it with its photos. True when it was created."""
    event = site.event(item)
    if event is None:
        stats['unreadable'] += 1
        log.warning('Left out, no details or date url=%s', item.url)
        return False
    if event.start <= clock():
        stats['already_started'] += 1
        return False

    place, postal_code = None, None
    if not event.online:
        place = geocoder.locate(event)
        postal_code = place and (event.postal_code or place.postal_code or geocoder.postcode_at(place))
        if not postal_code:
            # Left out rather than sent as ONLINE, which would hide it from the proximity signal.
            stats['no_location'] += 1
            log.warning('Left out, no location url=%s address=%s', item.url, (event.venue, event.street, event.city))
            return False

    post = to_post(event, place, postal_code)
    stats['online' if event.online else 'exact_location' if place.exact else 'city_location'] += 1
    # Downloaded one at a time as the sink asks, so it stops at the API's limit without fetching the rest.
    images = (image for image in map(site.image, event.image_urls) if image is not None)
    outcome = sink.create_post(post, images)
    if outcome.failure:
        stats['rejected'] += 1
        log.warning('Rejected url=%s reason=%s', item.url, outcome.failure)
        return False
    stats['created'] += 1
    if outcome.photos:
        stats['with_image'] += 1
        stats['photos'] += outcome.photos
    return True


def _failed_request(error: requests.RequestException) -> str:
    where = f'{error.request.method} {error.request.url}' if error.request else 'a request'
    outcome = error.response.status_code if error.response is not None else type(error).__name__
    return f'{where} failed: {outcome}'


def main() -> int:
    parser = argparse.ArgumentParser(description='Import trojmiasto.pl events into Coolture')
    parser.add_argument('--limit', type=int, help='stop after this many new posts')
    parser.add_argument('--dry-run', action='store_true', help='print the posts as JSON lines instead of sending them')
    parser.add_argument('--snapshot', type=Path, metavar='DIR',
                        help='save a sample of every category, with photos, into DIR instead of sending it; adds to what DIR holds')
    parser.add_argument('--size', type=int, default=DEFAULT_SIZE, help=f'events in a snapshot (default {DEFAULT_SIZE})')
    parser.add_argument('--all', action='store_true', help='a snapshot of the whole listing')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', stream=sys.stderr)
    # A retry that fails ends in the one error below, so its warnings would only repeat it.
    logging.getLogger('urllib3').setLevel(logging.ERROR)

    stats: Counter = Counter()
    try:
        geocoder = Geocoder(http_session(retry_timeouts=True), os.getenv('SCRAPER_GEOCODER_URL', DEFAULT_URL))
        if args.snapshot:
            run_snapshot(Site(http_session(retry_timeouts=True)), geocoder, Snapshot(args.snapshot), stats, None if args.all else args.size)
            return _exit_code(stats)
        sink = DryRun() if args.dry_run else CooltureApi(
            http_session(), http_session(), os.getenv('SCRAPER_API_URL', DEFAULT_API_URL),
            token=os.getenv('SCRAPER_API_TOKEN'), user_id=os.getenv('SCRAPER_USER_ID'))
        run(Site(http_session(retry_timeouts=True)), geocoder, sink, stats, args.limit)
    # Everything imported so far stays after an error, and the next run goes on from there.
    except (ApiError, GeocoderUnavailable) as error:
        log.error('Run stopped: %s', error)
        return 1
    except requests.RequestException as error:
        log.error('Run stopped: %s', _failed_request(error))
        return 1
    finally:
        log.info('Done: %s', dict(stats))

    return _exit_code(stats)


def _exit_code(stats: Counter) -> int:
    if not stats['listed']:
        log.error('The listing gave no events, so its markup has probably changed')
        return 1
    return 1 if stats['rejected'] else 0


if __name__ == '__main__':
    sys.exit(main())
