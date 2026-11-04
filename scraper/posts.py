import re
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from geocoder import Place
from trojmiasto import EventPage, ListingItem

WARSAW = ZoneInfo('Europe/Warsaw')
NO_HOUR = time(12, 0)
COUNTRY = 'POL'

TITLE_MAX = 128
DESCRIPTION_MAX = 1000
TAG_MAX = 32
VENUE_MAX = 64
BUILDING_NUMBER_MAX = 16
ADDRESS_MAX = 128
POSTAL_CODE_MAX = 16
SENTENCE_END = re.compile(r'[a-ząćęłńóśźż]{3}[.!?]\s')


def cut(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    return text[:limit - 1].rsplit(' ', 1)[0].rstrip(' ,;:-–\n') + '…'


def cut_description(text: str) -> str:
    if len(text) <= DESCRIPTION_MAX:
        return text
    head = text[:DESCRIPTION_MAX]
    end = max((match.end() - 1 for match in SENTENCE_END.finditer(head)), default=0)
    return head[:end] if end >= DESCRIPTION_MAX // 2 else cut(text, DESCRIPTION_MAX)


def starts_at(item: ListingItem, page: EventPage) -> datetime | None:
    # The listing has the date of this showing, and a page can cover several.
    day = item.start_date or page.start_date
    return datetime.combine(day, item.hour or page.hour or NO_HOUR, tzinfo=WARSAW) if day else None


def _ends_at(page: EventPage, start: datetime) -> datetime | None:
    if page.end is None:
        return None
    end = page.end if page.end.tzinfo else page.end.replace(tzinfo=WARSAW)
    # A multi-day event gives only its last day, as midnight, so it runs until that day ends.
    if end.timetz().replace(tzinfo=None) == time(0, 0):
        end = end + timedelta(days=1) - timedelta(minutes=1)
    return end if end > start else None


def _description(item: ListingItem, page: EventPage) -> str:
    if page.description:
        return cut_description(page.description)
    facts = ', '.join(part for part in (item.title, page.venue or item.venue, item.city) if part)
    return cut_description(f'{item.category.capitalize()}: {facts}.' if item.category else f'{facts}.')


def _iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')


def _location(item: ListingItem, page: EventPage, place: Place, postal_code: str) -> dict:
    location = {
        'countryCode': COUNTRY,
        'city': cut(item.city, ADDRESS_MAX),
        'postalCode': postal_code[:POSTAL_CODE_MAX],
        'coordinates': {'latitude': place.latitude, 'longitude': place.longitude},
    }
    if page.venue or item.venue:
        location['venueName'] = cut(page.venue or item.venue, VENUE_MAX)
    if page.street:
        location['street'] = cut(page.street, ADDRESS_MAX)
    if page.building_number:
        location['buildingNum'] = page.building_number[:BUILDING_NUMBER_MAX]
    return location


def build_post(item: ListingItem, page: EventPage, place: Place | None) -> dict | None:
    # An event held in a place is left out rather than sent as ONLINE, which would hide it from the proximity signal.
    start = starts_at(item, page)
    post = {
        'title': cut(item.title, TITLE_MAX),
        'description': _description(item, page),
        'eventUrl': item.url,
        'startsAt': _iso(start),
        'tags': [cut(tag, TAG_MAX) for tag in item.category.split(', ')] if item.category else [],
        'type': 'ONLINE' if page.online else 'OFFLINE',
        'visibility': 'PUBLIC',
    }

    end = _ends_at(page, start)
    if end is not None:
        post['endsAt'] = _iso(end)

    if not page.online:
        postal_code = page.postal_code or (place.postal_code if place else None)
        if place is None or postal_code is None or not item.city:
            return None
        post['location'] = _location(item, page, place, postal_code)
    return post
