import time
from collections.abc import Callable
from dataclasses import dataclass

import requests

from trojmiasto import Event

DEFAULT_URL = 'https://nominatim.openstreetmap.org'
# The public service allows one request per second.
PAUSE_S = 1.0
# Pomerania, as west, north, east, south. A name matched outside it is another place with the same name.
REGION = '16.5,55.0,20.0,53.3'
WRONG_SHAPE = (KeyError, AttributeError, TypeError, ValueError)


class GeocoderUnavailable(RuntimeError):
    """The service couldn't be asked, which is different from it finding nothing and is never remembered."""


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
        self._answers: dict[tuple, Place | str | None] = {}

    def locate(self, event: Event) -> Place | None:
        city = event.city
        if not city:
            return None

        queries = []
        if event.street:
            address = f'{event.building_number} {event.street}' if event.building_number else event.street
            queries.append({'street': address, 'city': city, 'country': 'Polska'})
        if event.venue:
            queries.append({'q': f'{event.venue}, {city}'})

        for query in queries:
            place = self._search(query, exact=True)
            if place is not None:
                return place
        return self._search({'city': city, 'country': 'Polska'}, exact=False)

    def postcode_at(self, place: Place) -> str | None:
        # A city has no postal code of its own, so this takes the one at its centre.
        key = ('reverse', place.latitude, place.longitude)
        return self._remembered(key, lambda: self._reverse(place))

    def _search(self, query: dict, exact: bool) -> Place | None:
        key = ('search', *sorted(query.items()))
        return self._remembered(key, lambda: self._request(query, exact))

    def _remembered(self, key: tuple, ask: Callable[[], Place | str | None]) -> Place | str | None:
        if key not in self._answers:
            self._answers[key] = ask()
        return self._answers[key]

    def _request(self, query: dict, exact: bool) -> Place | None:
        hits = self._get('search', {**query, 'limit': 1, 'countrycodes': 'pl', 'viewbox': REGION, 'bounded': 1})
        if not hits:
            return None
        try:
            hit = hits[0]
            return Place(float(hit['lat']), float(hit['lon']), hit.get('address', {}).get('postcode'), exact)
        except WRONG_SHAPE as error:
            raise GeocoderUnavailable(f'Nominatim gave an unexpected answer query={query} error={error!r}') from error

    def _reverse(self, place: Place) -> str | None:
        found = self._get('reverse', {'lat': place.latitude, 'lon': place.longitude})
        return found.get('address', {}).get('postcode') if isinstance(found, dict) else None

    def _get(self, path: str, params: dict) -> list | dict:
        self._sleep(self._pause_s)
        try:
            response = self._session.get(f'{self._base_url}/{path}', timeout=20,
                                         params={**params, 'format': 'jsonv2', 'addressdetails': 1})
            response.raise_for_status()
            return response.json()
        except requests.RequestException as error:
            raise GeocoderUnavailable(f'Nominatim request failed path={path} params={params} error={error}') from error
