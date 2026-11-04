import html
import json
import logging
import re
import time as clock
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from datetime import date, datetime, time
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

log = logging.getLogger(__name__)

SITE_URL = 'https://www.trojmiasto.pl'
LISTING_URL = f'{SITE_URL}/imprezy/dni,30dni.html'
LISTING_PAGE_PARAMETER = 'strona'
SITE_PAUSE_S = 1.0

HOUR = re.compile(r'godz\.\s*(\d{1,2})[:.](\d{2})', re.IGNORECASE)
BUILDING_NUMBER = re.compile(r'^(?P<street>.*\S)\s+(?P<number>\d+[a-zA-Z]?(?:/\d+[a-zA-Z]?)?)$')
REMARK = re.compile(r'\s*\([^)]*\)$')

LOWER = 'a-ząćęłńóśźż'
UPPER = 'A-ZĄĆĘŁŃÓŚŹŻ'
GLUED_WORDS = re.compile(rf'([{LOWER}]{{3}})([{UPPER}][{LOWER}])')
GLUED_SENTENCES = re.compile(rf'([{LOWER}\d)]{{2}}[.!?:])([{UPPER}][{LOWER}])')
NOT_A_VENUE = {'trójmiasto'}


@dataclass(frozen=True)
class ListingItem:
    url: str
    title: str
    category: str | None
    city: str | None
    venue: str | None
    start_date: date | None
    hour: time | None


@dataclass(frozen=True)
class EventPage:
    start_date: date | None
    end: datetime | None
    online: bool
    description: str
    venue: str | None
    street: str | None
    building_number: str | None
    postal_code: str | None
    hour: time | None


def clean_text(value: str | None) -> str:
    return ' '.join((value or '').split())


def clean_description(value: str | None) -> str:
    text = clean_text(html.unescape(value or ''))
    text = GLUED_SENTENCES.sub(r'\1 \2', text)
    return GLUED_WORDS.sub(r'\1 \2', text)


def parse_hour(label: str | None) -> time | None:
    match = HOUR.search(label or '')
    if match is None or int(match[1]) > 23 or int(match[2]) > 59:
        return None
    return time(int(match[1]), int(match[2]))


def _real_venue(name: str | None) -> str | None:
    return name if name and name.casefold() not in NOT_A_VENUE else None


def _parse_day(element) -> date | None:
    try:
        return date.fromisoformat(element.get('content', '')) if element else None
    except ValueError:
        return None


def parse_listing(page_html: str) -> list[ListingItem]:
    items = []
    for element in BeautifulSoup(page_html, 'html.parser').select('.event__item'):
        link = element.select_one('.event__item__title[href]')
        title = clean_text(link.get_text()) if link else ''
        if not title:
            continue

        def text_of(selector: str) -> str:
            found = element.select_one(selector)
            return clean_text(found.get_text()) if found else ''

        items.append(ListingItem(
            url=urljoin(SITE_URL, link['href']),
            title=title,
            category=text_of('.event__item__category').lower() or None,
            city=text_of('.event__item__location__city').rstrip(', ') or None,
            venue=_real_venue(text_of('.event__item__location__place')),
            start_date=_parse_day(element.select_one('.event__item__date')),
            hour=parse_hour(text_of('.event__item__date__hour')),
        ))
    return items


def _text(mapping: object, key: str) -> str:
    # The block is untrusted JSON, and any of its fields can be missing or hold another type.
    value = mapping.get(key) if isinstance(mapping, dict) else None
    return clean_text(value) if isinstance(value, str) else ''


def _event_block(page: BeautifulSoup) -> dict | None:
    for script in page.select('script[type="application/ld+json"]'):
        try:
            block = json.loads(script.string or '')
        except ValueError:
            continue
        for entry in block if isinstance(block, list) else [block]:
            if isinstance(entry, dict) and 'Event' in str(entry.get('@type', '')):
                return entry
    return None


def _split_street(street_address: str) -> tuple[str | None, str | None]:
    # The value looks like "Venue, City, Street 12".
    if ',' not in street_address:
        return None, None
    street = REMARK.sub('', street_address.rsplit(',', 1)[1].strip())
    match = BUILDING_NUMBER.match(street)
    if match is None:
        return street or None, None
    return match['street'], match['number']


def _parse_moment(value: str) -> datetime | None:
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


def _page_text(body) -> str:
    for noise in body.select('.calendar-info-box, .imp__textInfo, .more_info, .articles'):
        noise.decompose()
    for line_break in body.select('br, p, li, div, h2'):
        line_break.insert_before('\n')
        line_break.insert_after('\n')
    text = '\n'.join(filter(None, map(clean_text, body.get_text().splitlines())))
    return GLUED_SENTENCES.sub(r'\1 \2', text)


def _description(page: BeautifulSoup, block: dict) -> str:
    # The block's text stops at the first quotation mark, so it's longer only when the page has lost its text.
    body = page.select_one('.text-contents')
    text = _page_text(body) if body else ''
    return max(text, clean_description(_text(block, 'description')), key=len)


def parse_event_page(page_html: str) -> EventPage | None:
    soup = BeautifulSoup(page_html, 'html.parser')
    # Theatre pages have no schema.org block, so the page gives only the text and the listing gives the rest.
    block = _event_block(soup) or {}
    description = _description(soup, block)
    if not block and not description:
        return None

    start = _parse_moment(_text(block, 'startDate'))
    location = block.get('location')
    place = location[0] if isinstance(location, list) and location else location
    # An address can also be plain text, such as "Trójmiasto", which _text reads as no fields.
    address = place.get('address') if isinstance(place, dict) else None
    street, building_number = _split_street(_text(address, 'streetAddress'))

    return EventPage(
        start_date=start.date() if start else None,
        end=_parse_moment(_text(block, 'endDate')),
        online='online' in _text(block, 'eventAttendanceMode').lower(),
        description=description,
        venue=_real_venue(_text(place, 'name')),
        street=street,
        building_number=building_number,
        postal_code=_text(address, 'postalCode') or None,
        # startDate is always midnight, and the name holds the hour.
        hour=parse_hour(_text(block, 'name')),
    )


class Site:

    def __init__(self, session: requests.Session, sleep: Callable[[float], None] = clock.sleep) -> None:
        self._session = session
        self._sleep = sleep

    def _get(self, url: str, params: dict | None = None) -> str:
        self._sleep(SITE_PAUSE_S)
        response = self._session.get(url, params=params, timeout=30)
        response.raise_for_status()
        return response.text

    def listing_items(self) -> Iterator[ListingItem]:
        """Walks the listing until a page adds nothing, because the site repeats its last page past the end."""
        seen: set[str] = set()
        page_number = 0
        while True:
            page_html = self._get(LISTING_URL, {LISTING_PAGE_PARAMETER: page_number})
            items = [i for i in parse_listing(page_html) if i.url not in seen]
            if not items:
                return
            for item in items:
                seen.add(item.url)
                yield item
            page_number += 1

    def event_page(self, url: str) -> EventPage | None:
        try:
            return parse_event_page(self._get(url))
        except requests.RequestException as error:
            log.warning('Event page unread url=%s error=%s', url, error)
            return None
