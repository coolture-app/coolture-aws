import re
from collections.abc import Callable
from dataclasses import dataclass

import requests

from trojmiasto import Event

# Our Photon: the container make up starts once make geo-index has built its index, the same index the app uses on AWS.
DEFAULT_URL = 'http://localhost:2322'
# Pomerania, as west, south, east, north. A name matched outside it is another place with the same name.
REGION = '16.5,53.3,20.0,55.0'
# Stops share venues' names ("Ergo Arena 04") and would be taken for the venue.
EXCLUDED = ('osm.highway.bus_stop', 'osm.public_transport', 'osm.amenity.shelter')
# Photon's structured search answers 400 to "35/38" or "27-31/M.30". The leading number finds the building.
HOUSE_NUMBER = re.compile(r'\d+[a-zA-Z]?')
WRONG_SHAPE = (KeyError, IndexError, AttributeError, TypeError, ValueError)


class GeocoderUnavailable(RuntimeError):
    """The service couldn't be asked, which is different from it finding nothing and is never remembered."""


@dataclass(frozen=True)
class Place:
    latitude: float
    longitude: float
    postal_code: str | None
    exact: bool


class Geocoder:

    def __init__(self, session: requests.Session, base_url: str = DEFAULT_URL) -> None:
        self._session = session
        self._base_url = base_url.rstrip('/')
        self._answers: dict[tuple, Place | str | None] = {}

    def locate(self, event: Event) -> Place | None:
        """The street address, else the venue, else the city centre. The address goes first even when only its street is
        found, because a venue's name matches loosely: "Good Time" found a clinic 2.6 km away."""
        city = event.city
        if not city:
            return None

        queries = []
        if event.street:
            query = {'street': event.street, 'city': city}
            number = HOUSE_NUMBER.match(event.building_number or '')
            if number:
                query['housenumber'] = number.group()
            queries.append(('structured', query))
        if event.venue:
            queries.append(('api', {'q': f'{event.venue}, {city}', 'exclude': EXCLUDED}))

        for path, query in queries:
            place = self._search(path, query, exact=True)
            if place is not None:
                return place
        return self._search('api', {'q': city, 'layer': 'city'}, exact=False)

    def postcode_at(self, place: Place) -> str | None:
        # A city has no postal code of its own, so this takes the one at its centre.
        key = ('reverse', place.latitude, place.longitude)
        return self._remembered(key, lambda: self._reverse(place))

    def _search(self, path: str, query: dict, exact: bool) -> Place | None:
        key = (path, *sorted(query.items()))
        return self._remembered(key, lambda: self._first(path, {**query, 'countrycode': 'PL', 'bbox': REGION}, exact))

    def _remembered(self, key: tuple, ask: Callable[[], Place | str | None]) -> Place | str | None:
        if key not in self._answers:
            self._answers[key] = ask()
        return self._answers[key]

    def _reverse(self, place: Place) -> str | None:
        found = self._first('reverse', {'lat': place.latitude, 'lon': place.longitude}, exact=False)
        return found.postal_code if found else None

    def _first(self, path: str, params: dict, exact: bool) -> Place | None:
        try:
            response = self._session.get(f'{self._base_url}/{path}', timeout=20,
                                         params={**params, 'limit': 1, 'lang': 'default'})
            response.raise_for_status()
            features = response.json()['features']
            if features == []:
                return None
            longitude, latitude = features[0]['geometry']['coordinates']
            return Place(float(latitude), float(longitude), features[0]['properties'].get('postcode'), exact)
        except requests.ConnectionError as error:
            raise GeocoderUnavailable(f'Photon is not answering at {self._base_url}. Locally: make geo-index once, '
                                      f'then make up. error={error}') from error
        except requests.RequestException as error:
            raise GeocoderUnavailable(f'Photon request failed path={path} params={params} error={error}') from error
        except WRONG_SHAPE as error:
            raise GeocoderUnavailable(f'Photon gave an unexpected answer path={path} error={error!r}') from error
