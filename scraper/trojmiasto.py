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
from bs4 import BeautifulSoup, NavigableString, Tag

from images import Image

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
# The same photo comes in several sizes, as in /1200x0/4274285.webp and /0x500/4274285__kr.webp.
PHOTO_ID = re.compile(r'/(\d+)(?:__\w+)?\.\w+$')
# Lists of other events and articles, whose photos aren't this event's.
OTHER_CONTENT = ['events-list', 'event__item', 'articles', 'SpectacleItem']
TRICITY = ('Gdańsk', 'Gdynia', 'Sopot')

# Paragraphs and their lines, as a post shows them.
PARAGRAPH_TAGS = 'p, div, h1, h2, h3, h4, h5, h6, ul, ol, table, blockquote'
LINE_EDGES = {'br', 'p', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'table', 'blockquote'}
HEADING_MAX = 60
SENTENCE_ENDS = ('.', '!', '?', '…', ':', ';')
LIST_ITEM = re.compile(r'^([•·*\-–—>]|\d)')
CUT_LINK = '\x00'
# A link's label, such as "KUP BILET" or "Posłuchaj UMI", means nothing without the link. Links holding a name or an address stay.
LINK_LABEL = re.compile(r'^(kup|kupuj|posłuchaj|zobacz|obejrzyj|przeczytaj|zapisz|wypełnij|sprawdź|zarezerwuj|rezerwuj|kliknij)\b',
                        re.IGNORECASE)
REPEATED_FACT = re.compile(r'^(kiedy|gdzie|termin|miejsce|data|godzina)\s*:', re.IGNORECASE)
FACT = re.compile(r'^[^\W\d][\w ]{1,24}:\s*\S')
# The site's own markup, left in some texts, such as [lead].
SITE_MARKUP = re.compile(r'\[/?lead\]', re.IGNORECASE)


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
    image_urls: tuple[str, ...] = ()


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
    image_urls: tuple[str, ...] = ()


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


def _alone_on_its_line(tag: Tag) -> bool:
    before = tag.previous_sibling
    while isinstance(before, NavigableString) and not before.strip():
        before = before.previous_sibling
    after = tag.next_sibling
    while isinstance(after, NavigableString) and not after.strip():
        after = after.next_sibling
    return all(node is None or isinstance(node, Tag) and node.name in LINE_EDGES for node in (before, after))


def _is_list_item(line: str) -> bool:
    return bool(LIST_ITEM.match(line))


def _is_heading(line: str) -> bool:
    letters = [c for c in line if c.isalpha()]
    return (len(line) <= HEADING_MAX and not _is_list_item(line)
            and (line.endswith(('?', ':')) or len(letters) >= 3 and all(c.isupper() for c in letters)))


def _clean_line(raw: str) -> str:
    line = clean_text(SITE_MARKUP.sub('', raw))
    if CUT_LINK in line:
        # "do kupienia online: KUP BILET" keeps the words before the link, without the colon that led to it.
        line = line.replace(CUT_LINK, ' ').strip().rstrip(' :–-,')
        line = clean_text(line)
    return line


def _paragraphs(text: str) -> list[list[str]]:
    """Groups the lines, where a blank line or a heading starts a new paragraph and a list keeps together."""
    paragraphs: list[list[str]] = []
    blank = True
    for raw in text.split('\n'):
        line = _clean_line(raw)
        if not line:
            blank = blank or not raw.strip() or CUT_LINK in raw
            continue
        previous = paragraphs[-1][-1] if paragraphs else None
        same_list = previous is not None and _is_list_item(previous) and _is_list_item(line)
        if previous is None or (blank and not same_list) or _is_heading(line) or _is_heading(previous):
            paragraphs.append([line])
        elif (not previous.endswith(SENTENCE_ENDS) and line[0].islower()
              and not _is_list_item(previous) and not _is_list_item(line)):
            # The source's own wrapping, in the middle of a sentence.
            paragraphs[-1][-1] = f'{previous} {line}'
        else:
            paragraphs[-1].append(line)
        blank = False
    return paragraphs


def _without_repeated_facts(paragraphs: list[list[str]]) -> list[list[str]]:
    # "Kiedy: …" and "Gdzie: …" at the start repeat the post's own start and address. "Bilety: …" and "Dla kogo: …" stay.
    kept: list[list[str]] = []
    at_start = True
    for lines in paragraphs:
        if at_start:
            lines = [line for line in lines if not REPEATED_FACT.match(line)]
            at_start = all(FACT.match(line) or _is_heading(line) for line in lines)
        if lines:
            kept.append(lines)
    return kept


def _page_text(body) -> str:
    for noise in body.select('.calendar-info-box, .imp__textInfo, .more_info, .articles'):
        noise.decompose()
    for link in body.select('a'):
        if LINK_LABEL.match(clean_text(link.get_text())):
            link.replace_with(CUT_LINK)
    for emphasis in [tag for tag in body.select('strong, b') if _alone_on_its_line(tag)]:
        emphasis.insert_before('\n\n')
        emphasis.insert_after('\n\n')
    for item in body.select('li'):
        bullet = '' if _is_list_item(clean_text(item.get_text())) else '• '
        item.insert_before(f'\n{bullet}')
        item.insert_after('\n')
    for block in body.select(PARAGRAPH_TAGS):
        block.insert_before('\n\n')
        block.insert_after('\n\n')
    for line_break in body.select('br'):
        line_break.replace_with('\n')
    paragraphs = _without_repeated_facts(_paragraphs(body.get_text()))
    text = '\n\n'.join('\n'.join(lines) for lines in paragraphs)
    return GLUED_SENTENCES.sub(r'\1 \2', text)


def _description(page: BeautifulSoup, block: dict) -> str:
    # The block's text stops at the first quotation mark, so it's longer only when the page has lost its text.
    body = page.select_one('.text-contents')
    text = _page_text(body) if body else ''
    return max(text, clean_description(_text(block, 'description')), key=len)


def _is_photo(url: str) -> bool:
    return '/zdj/' in urlparse(url).path


def _image_urls(page: BeautifulSoup) -> tuple[str, ...]:
    """The og:image first, as the post's cover, then the gallery's full-size photos, each photo once."""
    meta = page.select_one('meta[property="og:image"][content]')
    urls = [meta['content']] if meta else []
    # A gallery item's img is a cropped thumbnail, and data-href the whole photo.
    urls += [urljoin(SITE_URL, link['data-href']) for link in page.select('a.tinyGallery__item[data-href]')
             if not link.find_parent(class_=OTHER_CONTENT)]
    photos: dict[str, str] = {}
    for url in filter(_is_photo, urls):
        photo_id = PHOTO_ID.search(urlparse(url).path)
        photos.setdefault(photo_id[1] if photo_id else url, url)
    return tuple(photos.values())


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
        image_urls=_image_urls(soup),
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
        image_urls=page.image_urls,
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
        return Image.read(response.content, mime_type, PurePosixPath(urlparse(url).path).name)
