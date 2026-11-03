import logging
import time
from collections.abc import Callable
from dataclasses import dataclass

import requests

log = logging.getLogger(__name__)

DEFAULT_URL = 'https://nominatim.openstreetmap.org'
# The public service allows one request per second.
PAUSE_S = 1.0
# Pomerania, as west, north, east, south. A name matched outside it is another place with the same name.
REGION = '16.5,55.0,20.0,53.3'


@dataclass(frozen=True)
class Place:
    latitude: float
    longitude: float
    postal_code: str | None
    exact: bool


class Geocoder:

    def __init__(self, session: requests.Session, base_url: str = DEFAULT_URL, pause_s: float = PAUSE_S,
                 sleep: Callable[[float], None] = time.sleep) -> None:
        self._session = session
        self._base_url = base_url.rstrip('/')
        self._pause_s = pause_s
        self._sleep = sleep
        self._answers: dict[tuple, Place | None] = {}

    def locate(self, venue: str | None, street: str | None, building_number: str | None, city: str | None) -> Place | None:
        if not city:
            return None

        queries = []
        if street:
            address = f'{building_number} {street}' if building_number else street
            queries.append({'street': address, 'city': city, 'country': 'Polska'})
        if venue:
            queries.append({'q': f'{venue}, {city}'})

        for query in queries:
            place = self._search(query, exact=True)
            if place is not None:
                return place
        place = self._search({'city': city, 'country': 'Polska'}, exact=False)
        if place is None and ' ' in city:
            # The listing can name two places here, as in "Sopot Brodwino".
            place = self._search({'city': city.split()[0], 'country': 'Polska'}, exact=False)
        return place

    def _search(self, query: dict, exact: bool) -> Place | None:
        key = tuple(sorted(query.items()))
        if key not in self._answers:
            self._answers[key] = self._request(query, exact)
        return self._answers[key]

    def _request(self, query: dict, exact: bool) -> Place | None:
        hits = self._get('search', {**query, 'limit': 1, 'countrycodes': 'pl', 'viewbox': REGION, 'bounded': 1})
        if not hits:
            return None
        hit = hits[0]
        # A city has no postal code of its own, so this takes the one at its centre.
        postcode = hit.get('address', {}).get('postcode') or self._postcode_at(hit['lat'], hit['lon'])
        return Place(float(hit['lat']), float(hit['lon']), postcode, exact)

    def _postcode_at(self, latitude: str, longitude: str) -> str | None:
        found = self._get('reverse', {'lat': latitude, 'lon': longitude})
        return found.get('address', {}).get('postcode') if found else None

    def _get(self, path: str, params: dict) -> list | dict | None:
        self._sleep(self._pause_s)
        try:
            response = self._session.get(f'{self._base_url}/{path}', timeout=20,
                                         params={**params, 'format': 'jsonv2', 'addressdetails': 1})
            response.raise_for_status()
            return response.json()
        except (requests.RequestException, ValueError) as error:
            log.warning('Nominatim request failed path=%s params=%s error=%s', path, params, error)
            return None
