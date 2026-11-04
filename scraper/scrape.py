import argparse
import json
import logging
import os
import sys
from collections import Counter
from datetime import datetime, timezone

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from api import ApiError, CooltureApi
from geocoder import DEFAULT_URL, Geocoder
from posts import build_post, starts_at
from trojmiasto import Site

log = logging.getLogger('scraper')

USER_AGENT = 'coolture-scraper/1.0 (+https://github.com/coolture-app/coolture-aws)'
DEFAULT_API_URL = 'http://localhost:3000'


class PacedRetry(Retry):
    # Only a throttle is retried, because a 5xx may come after a post was saved, whatever Retry-After says.
    RETRY_AFTER_STATUS_CODES = frozenset({429})

    def get_backoff_time(self) -> float:
        # urllib3 sends the first retry at once, which would break the one-a-second limit of the site and Nominatim.
        return max(super().get_backoff_time(), self.backoff_factor)


def http_session() -> requests.Session:
    # read=False keeps a POST from being sent again after a timeout, and lets the timeout arrive as requests.Timeout.
    retry = PacedRetry(total=3, connect=2, read=False, status_forcelist={429}, backoff_factor=2,
                       allowed_methods=None, respect_retry_after_header=True, raise_on_status=False)
    session = requests.Session()
    session.headers['User-Agent'] = USER_AGENT
    session.mount('https://', HTTPAdapter(max_retries=retry))
    session.mount('http://', HTTPAdapter(max_retries=retry))
    return session


def run(site: Site, geocoder: Geocoder, api: CooltureApi | None, now: datetime, limit: int | None) -> Counter:
    stats: Counter = Counter()
    existing = api.existing_event_urls(api.me()) if api else set()

    for item in site.listing_items():
        stats['listed'] += 1
        if item.url in existing:
            stats['already_imported'] += 1
            continue
        if limit is not None and stats['created'] >= limit:
            break

        page = site.event_page(item.url)
        start = starts_at(item, page) if page else None
        if start is None:
            stats['unreadable'] += 1
            log.warning('Left out, no details or date url=%s', item.url)
            continue
        if start <= now:
            stats['already_started'] += 1
            continue

        place = None
        if not page.online:
            place = geocoder.locate(page.venue or item.venue, page.street, page.building_number, item.city)
        post = build_post(item, page, place)
        if post is None:
            stats['no_location'] += 1
            log.warning('Left out, no location url=%s address=%s', item.url, (page.venue or item.venue, page.street, item.city))
            continue

        stats['online' if page.online else 'exact_location' if place.exact else 'city_location'] += 1
        if api is None:
            print(json.dumps(post, ensure_ascii=False))
            stats['created'] += 1
            continue

        failure = api.create_post(post)
        if failure:
            stats['rejected'] += 1
            log.warning('Rejected url=%s reason=%s', item.url, failure)
        else:
            stats['created'] += 1

    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description='Import trojmiasto.pl events into Coolture')
    parser.add_argument('--limit', type=int, help='stop after this many new posts')
    parser.add_argument('--dry-run', action='store_true', help='print the posts as JSON lines instead of sending them')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(message)s', stream=sys.stderr)

    geocoder = Geocoder(http_session(), os.getenv('SCRAPER_NOMINATIM_URL', DEFAULT_URL))
    api = None
    if not args.dry_run:
        api = CooltureApi(http_session(), os.getenv('SCRAPER_API_URL', DEFAULT_API_URL),
                          token=os.getenv('SCRAPER_API_TOKEN'), user_id=os.getenv('SCRAPER_USER_ID'))

    try:
        stats = run(Site(http_session()), geocoder, api, datetime.now(timezone.utc), args.limit)
    except ApiError as error:
        log.error('%s', error)
        return 1

    log.info('Done: %s', dict(stats))
    if not stats['listed']:
        log.error('The listing gave no events, so its markup has probably changed')
        return 1
    return 1 if stats['rejected'] else 0


if __name__ == '__main__':
    sys.exit(main())
