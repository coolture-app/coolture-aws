import argparse
import json
import logging
import os
import sys
import time
from collections import Counter
from collections.abc import Callable, Iterator
from datetime import datetime, timezone

import requests

import trojmiasto
from api import ApiError, CooltureApi
from geocoder import DEFAULT_URL, Geocoder
from posts import build_post, starts_at

log = logging.getLogger('scraper')

USER_AGENT = 'coolture-scraper/1.0 (+https://github.com/coolture-app/coolture-aws)'
SITE_PAUSE_S = 1.0
DEFAULT_API_URL = 'http://localhost:3000'


class Site:

    def __init__(self, session: requests.Session, sleep: Callable[[float], None] = time.sleep) -> None:
        self._session = session
        self._sleep = sleep

    def _get(self, url: str, params: dict | None = None) -> str:
        self._sleep(SITE_PAUSE_S)
        response = self._session.get(url, params=params, timeout=30)
        response.raise_for_status()
        return response.text

    def listing_items(self) -> Iterator[trojmiasto.ListingItem]:
        """Walks the listing until a page adds nothing, because the site repeats its last page past the end."""
        seen: set[str] = set()
        page_number = 0
        while True:
            page_html = self._get(trojmiasto.LISTING_URL, {trojmiasto.LISTING_PAGE_PARAMETER: page_number})
            items = [i for i in trojmiasto.parse_listing(page_html) if i.url not in seen]
            if not items:
                return
            for item in items:
                seen.add(item.url)
                yield item
            page_number += 1

    def event_page(self, url: str) -> trojmiasto.EventPage | None:
        try:
            return trojmiasto.parse_event_page(self._get(url))
        except requests.RequestException as error:
            log.warning('Event page unread url=%s error=%s', url, error)
            return None


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

    site_session = requests.Session()
    site_session.headers['User-Agent'] = USER_AGENT
    api_session = requests.Session()
    api_session.headers['User-Agent'] = USER_AGENT

    geocoder = Geocoder(site_session, os.getenv('SCRAPER_NOMINATIM_URL', DEFAULT_URL))
    api = None
    if not args.dry_run:
        api = CooltureApi(api_session, os.getenv('SCRAPER_API_URL', DEFAULT_API_URL),
                          token=os.getenv('SCRAPER_API_TOKEN'), user_id=os.getenv('SCRAPER_USER_ID'))

    try:
        stats = run(Site(site_session), geocoder, api, datetime.now(timezone.utc), args.limit)
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
