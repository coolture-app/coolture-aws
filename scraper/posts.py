import re
from datetime import datetime, timezone

from geocoder import Place
from trojmiasto import Event

COUNTRY = 'POL'

TITLE_MAX = 128
DESCRIPTION_MAX = 1000
TAG_MAX = 32
VENUE_MAX = 64
BUILDING_NUMBER_MAX = 16
ADDRESS_MAX = 128
POSTAL_CODE_MAX = 16
SENTENCE_END = re.compile(r'[a-ząćęłńóśźż]{3}[.!?]\s')
LINE_END = re.compile(r'\n')


def utf16_len(text: str) -> int:
    # The API is Java, and @Size counts UTF-16 units, so an emoji counts as two.
    return len(text.encode('utf-16-le')) // 2


def _head(text: str, units: int) -> str:
    # Decoding with 'ignore' drops half a surrogate pair left at the end.
    return text.encode('utf-16-le')[:units * 2].decode('utf-16-le', 'ignore')


def cut(text: str, limit: int) -> str:
    if utf16_len(text) <= limit:
        return text
    return _head(text, limit - 1).rsplit(' ', 1)[0].rstrip(' ,;:-–\n') + '…'


def cut_description(text: str) -> str:
    if utf16_len(text) <= DESCRIPTION_MAX:
        return text
    head = _head(text, DESCRIPTION_MAX)
    # A sentence or a line ends there, so a list or a schedule isn't cut in the middle of an item.
    ends = [match.end() - 1 for match in SENTENCE_END.finditer(head)] + [match.start() for match in LINE_END.finditer(head)]
    end = max(ends, default=0)
    return head[:end].rstrip() if end >= DESCRIPTION_MAX // 2 else cut(text, DESCRIPTION_MAX)


def _iso(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')


def _location(event: Event, place: Place, postal_code: str) -> dict:
    location = {
        'countryCode': COUNTRY,
        'city': cut(event.city, ADDRESS_MAX),
        'postalCode': cut(postal_code, POSTAL_CODE_MAX),
        'coordinates': {'latitude': place.latitude, 'longitude': place.longitude},
    }
    if event.venue:
        location['venueName'] = cut(event.venue, VENUE_MAX)
    if event.street:
        location['street'] = cut(event.street, ADDRESS_MAX)
    if event.building_number:
        location['buildingNum'] = cut(event.building_number, BUILDING_NUMBER_MAX)
    return location


def to_post(event: Event, place: Place | None, postal_code: str | None) -> dict:
    post = {
        'title': cut(event.title, TITLE_MAX),
        'description': cut_description(event.description),
        'eventUrl': event.url,
        'startsAt': _iso(event.start),
        'tags': [cut(tag, TAG_MAX) for tag in event.category.split(', ')] if event.category else [],
        'type': 'ONLINE' if event.online else 'OFFLINE',
        'visibility': 'PUBLIC',
    }
    if event.end is not None:
        post['endsAt'] = _iso(event.end)
    if place is not None and postal_code:
        post['location'] = _location(event, place, postal_code)
    return post
