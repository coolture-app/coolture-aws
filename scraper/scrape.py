import argparse
import json
import logging
import os
import sys
from collections import Counter
from collections.abc import Callable
from datetime import datetime, timezone

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from api import ApiError, CooltureApi
from geocoder import DEFAULT_URL, Geocoder, GeocoderUnavailable
from posts import to_post
from trojmiasto import Site

log = logging.getLogger('scraper')

USER_AGENT = 'coolture-scraper/1.0 (+https://github.com/coolture-app/coolture-aws)'
DEFAULT_API_URL = 'http://localhost:3000'
# A longer wait would stall the whole run, and urllib3's own limit is 6 hours.
RETRY_AFTER_MAX_S = 120


class PacedRetry(Retry):
    # Only a throttle is retried, because a 5xx may come after a post was saved, whatever Retry-After says.
    RETRY_AFTER_STATUS_CODES = frozenset({429})

    def get_backoff_time(self) -> float:
        # urllib3 sends the first retry at once, which would break the one-a-second limit of the site and Nominatim.
        return max(super().get_backoff_time(), self.backoff_factor)


def http_session() -> requests.Session:
    # read=False keeps a POST from being sent again after a timeout, and lets the timeout arrive as requests.Timeout.
    retry = PacedRetry(total=3, connect=2, read=False, status_forcelist={429}, backoff_factor=2,
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

    def create_post(self, post: dict) -> None:
        print(json.dumps(post, ensure_ascii=False))


def run(site: Site, geocoder: Geocoder, sink: CooltureApi | DryRun, stats: Counter, limit: int | None,
        clock: Callable[[], datetime] = utc_now) -> None:
    existing = sink.existing_event_urls()

    for item in site.listing_items():
        stats['listed'] += 1
        if item.url in existing:
            stats['already_imported'] += 1
            continue
        if limit is not None and stats['created'] >= limit:
            break

        event = site.event(item)
        if event is None:
            stats['unreadable'] += 1
            log.warning('Left out, no details or date url=%s', item.url)
            continue
        if event.start <= clock():
            stats['already_started'] += 1
            continue

        place, postal_code = None, None
        if not event.online:
            place = geocoder.locate(event)
            postal_code = place and (event.postal_code or place.postal_code or geocoder.postcode_at(place))
            if not postal_code:
                # Left out rather than sent as ONLINE, which would hide it from the proximity signal.
                stats['no_location'] += 1
                log.warning('Left out, no location url=%s address=%s', item.url, (event.venue, event.street, event.city))
                continue

        post = to_post(event, place, postal_code)
        stats['online' if event.online else 'exact_location' if place.exact else 'city_location'] += 1
        failure = sink.create_post(post)
        if failure:
            stats['rejected'] += 1
            log.warning('Rejected url=%s reason=%s', item.url, failure)
        else:
            stats['created'] += 1


def _failed_request(error: requests.RequestException) -> str:
    where = f'{error.request.method} {error.request.url}' if error.request else 'a request'
    outcome = error.response.status_code if error.response is not None else type(error).__name__
    return f'{where} failed: {outcome}'


def main() -> int:
    parser = argparse.ArgumentParser(description='Import trojmiasto.pl events into Coolture')
    parser.add_argument('--limit', type=int, help='stop after this many new posts')
    parser.add_argument('--dry-run', action='store_true', help='print the posts as JSON lines instead of sending them')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', stream=sys.stderr)
    # A retry that fails ends in the one error below, so its warnings would only repeat it.
    logging.getLogger('urllib3').setLevel(logging.ERROR)

    stats: Counter = Counter()
    try:
        geocoder = Geocoder(http_session(), os.getenv('SCRAPER_NOMINATIM_URL', DEFAULT_URL))
        sink = DryRun() if args.dry_run else CooltureApi(
            http_session(), os.getenv('SCRAPER_API_URL', DEFAULT_API_URL),
            token=os.getenv('SCRAPER_API_TOKEN'), user_id=os.getenv('SCRAPER_USER_ID'))
        run(Site(http_session()), geocoder, sink, stats, args.limit)
    # Everything imported so far stays after an error, and the next run goes on from there.
    except (ApiError, GeocoderUnavailable) as error:
        log.error('Run stopped: %s', error)
        return 1
    except requests.RequestException as error:
        log.error('Run stopped: %s', _failed_request(error))
        return 1
    finally:
        log.info('Done: %s', dict(stats))

    if not stats['listed']:
        log.error('The listing gave no events, so its markup has probably changed')
        return 1
    return 1 if stats['rejected'] else 0


if __name__ == '__main__':
    sys.exit(main())
