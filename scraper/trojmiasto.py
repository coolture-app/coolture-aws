import html
import json
import logging
import re
import time as clock
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import PurePosixPath
from urllib.parse import urljoin, urlparse
from zoneinfo import ZoneInfo

import requests
from bs4 import BeautifulSoup

log = logging.getLogger(__name__)

SITE_URL = 'https://www.trojmiasto.pl'
LISTING_URL = f'{SITE_URL}/imprezy/dni,30dni.html'
LISTING_PAGE_PARAMETER = 'strona'
SITE_PAUSE_S = 1.0
WARSAW = ZoneInfo('Europe/Warsaw')
NO_HOUR = time(12, 0)

HOUR = re.compile(r'godz\.\s*(\d{1,2})[:.](\d{2})', re.IGNORECASE)
END_HOUR = re.compile(r'godz\.\s*\d{1,2}[:.]\d{2}\s*[-–]\s*(\d{1,2})[:.](\d{2})', re.IGNORECASE)
BUILDING_NUMBER = re.compile(r'^(?P<street>.*\S)\s+(?P<number>\d+[a-zA-Z]?(?:/\d+[a-zA-Z]?)?)$')
REMARK = re.compile(r'\s*\([^)]*\)$')

LOWER = 'a-ząćęłńóśźż'
UPPER = 'A-ZĄĆĘŁŃÓŚŹŻ'
GLUED_WORDS = re.compile(rf'([{LOWER}]{{3}})([{UPPER}][{LOWER}])')
GLUED_SENTENCES = re.compile(rf'([{LOWER}\d)]{{2}}[.!?:])([{UPPER}][{LOWER}])')
NOT_A_VENUE = {'trójmiasto'}
TRICITY = ('Gdańsk', 'Gdynia', 'Sopot')


@dataclass(frozen=True)
class ListingItem:
    url: str
    title: str
    category: str | None
    city: str | None
    venue: str | None
    start_date: date | None
    hour: time | None
    end_hour: time | None = None


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
    image_url: str | None = None


@dataclass(frozen=True)
class Event:
    url: str
    title: str
    category: str | None
    city: str | None
    venue: str | None
    street: str | None
    building_number: str | None
    postal_code: str | None
    online: bool
    description: str
    start: datetime
    end: datetime | None
    image_url: str | None = None


@dataclass(frozen=True)
class Image:
    content: bytes
    mime_type: str
    file_name: str


def clean_text(value: str | None) -> str:
    return ' '.join((value or '').split())


def clean_description(value: str | None) -> str:
    text = clean_text(html.unescape(value or ''))
    text = GLUED_SENTENCES.sub(r'\1 \2', text)
    return GLUED_WORDS.sub(r'\1 \2', text)


def _clock_time(match: re.Match | None) -> time | None:
    if match is None or int(match[1]) > 23 or int(match[2]) > 59:
        return None
    return time(int(match[1]), int(match[2]))


def parse_hour(label: str | None) -> time | None:
    return _clock_time(HOUR.search(label or ''))


def parse_end_hour(label: str | None) -> time | None:
    # "Godz. 14:00 - 18:00" is a range, while "Godz. 17:00, 20:00" is two showings and gives no end.
    return _clock_time(END_HOUR.search(label or ''))


def _main_city(value: str) -> str:
    # The listing can name more than one place here, as in "Gdańsk Sopot" or "Gdańsk Oliwa".
    return next((city for city in TRICITY if value.startswith(city)), value)


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
            city=_main_city(text_of('.event__item__location__city').rstrip(', ')) or None,
            venue=_real_venue(text_of('.event__item__location__place')),
            start_date=_parse_day(element.select_one('.event__item__date')),
            hour=parse_hour(text_of('.event__item__date__hour')),
            end_hour=parse_end_hour(text_of('.event__item__date__hour')),
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


def _image_url(page: BeautifulSoup) -> str | None:
    meta = page.select_one('meta[property="og:image"][content]')
    return meta['content'] if meta and '/zdj/' in urlparse(meta['content']).path else None


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
        image_url=_image_url(soup),
    )


def _ends_at(end: datetime | None, start: datetime) -> datetime | None:
    if end is None:
        return None
    end = end if end.tzinfo else end.replace(tzinfo=WARSAW)
    # A multi-day event gives only its last day, as midnight, so it runs until that day ends.
    if end.timetz().replace(tzinfo=None) == time(0, 0):
        end = end + timedelta(days=1) - timedelta(minutes=1)
    return end if end > start else None


def _listed_end(end_hour: time | None, start: datetime) -> datetime | None:
    if end_hour is None:
        return None
    end = datetime.combine(start.date(), end_hour, tzinfo=WARSAW)
    return end if end > start else end + timedelta(days=1)


def _facts(item: ListingItem, venue: str | None) -> str:
    facts = ', '.join(part for part in (item.title, venue, item.city) if part)
    return f'{item.category.capitalize()}: {facts}.' if item.category else f'{facts}.'


def merge(item: ListingItem, page: EventPage) -> Event | None:
    """Decides, once, which of the listing and the page each value comes from."""
    # The listing has the date of this showing, and a page can cover several.
    day = item.start_date or page.start_date
    if day is None:
        return None
    start = datetime.combine(day, item.hour or page.hour or NO_HOUR, tzinfo=WARSAW)
    venue = page.venue or item.venue
    return Event(
        url=item.url,
        title=item.title,
        category=item.category,
        city=item.city,
        venue=venue,
        street=page.street,
        building_number=page.building_number,
        postal_code=page.postal_code,
        online=page.online,
        description=page.description or _facts(item, venue),
        start=start,
        end=_ends_at(page.end, start) or _listed_end(item.end_hour, start),
        image_url=page.image_url,
    )


class Site:

    def __init__(self, session: requests.Session, sleep: Callable[[float], None] = clock.sleep) -> None:
        self._session = session
        self._sleep = sleep

    def _get(self, url: str, params: dict | None = None) -> requests.Response:
        self._sleep(SITE_PAUSE_S)
        response = self._session.get(url, params=params, timeout=30)
        response.raise_for_status()
        return response

    def listing_items(self) -> Iterator[ListingItem]:
        """Walks the listing until a page adds nothing, because the site repeats its last page past the end."""
        seen: set[str] = set()
        page_number = 0
        while True:
            page_html = self._get(LISTING_URL, {LISTING_PAGE_PARAMETER: page_number}).text
            items = [i for i in parse_listing(page_html) if i.url not in seen]
            if not items:
                return
            for item in items:
                seen.add(item.url)
                yield item
            page_number += 1

    def event(self, item: ListingItem) -> Event | None:
        try:
            page = parse_event_page(self._get(item.url).text)
        except requests.RequestException as error:
            log.warning('Event page unread url=%s error=%s', item.url, error)
            return None
        return merge(item, page) if page else None

    def image(self, url: str) -> Image | None:
        try:
            response = self._get(url)
        except requests.RequestException as error:
            log.warning('Image unread url=%s error=%s', url, error)
            return None
        mime_type = response.headers.get('Content-Type', '').split(';')[0].strip().lower()
        return Image(response.content, mime_type, PurePosixPath(urlparse(url).path).name)
