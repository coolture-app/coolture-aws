import logging
import re
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass

import requests

from trojmiasto import Event

log = logging.getLogger(__name__)

# Our Photon, started by `make up`.
DEFAULT_URL = 'http://localhost:2322'
# Pomerania, as west, south, east, north. A name matched outside it is another place with the same name.
REGION = '16.5,53.3,20.0,55.0'
# Stops share venues' names ("Ergo Arena 04") and would be taken for the venue.
EXCLUDED = ('osm.highway.bus_stop', 'osm.public_transport', 'osm.amenity.shelter')
# Photon's structured search answers 400 to "35/38" or "27-31/M.30". The leading number finds the building.
HOUSE_NUMBER = re.compile(r'\d+[a-zA-Z]?')
# The site's splitter leaves a number like "11/U3" in the street, and without it Photon gives the street's middle.
TRAILING_NUMBER = re.compile(r'^(?P<street>.*[^\d\s])\s+(?P<number>\d\S*)$')
WRONG_SHAPE = (KeyError, IndexError, AttributeError, TypeError, ValueError)
# The site sometimes puts a hall in the street field ("w Sali Haxo"), which a search would place on some other street.
# The negative lookahead keeps "W. Stwosza", a street named for Wit Stwosz.
NOT_A_STREET = re.compile(r'^(w|we|sala|sali)\b(?!\.)', re.IGNORECASE)
# Words that say what kind of street it is, which Photon writes out or leaves out ("pl." for "Plac").
STREET_KINDS = {'ul', 'ulica', 'al', 'aleja', 'aleje', 'pl', 'plac', 'os', 'osiedle', 'rondo', 'skwer', 'bulwar'}


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
        self._answers: dict[tuple, Place | None] = {}

    def locate(self, event: Event) -> Place | None:
        """The street address, else the venue, else the city centre. The address goes first even when only its street is
        found, because a venue's name matches loosely: "Good Time" found a clinic 2.6 km away."""
        city = event.city
        if not city:
            return None

        queries = []
        street, building_number = event.street, event.building_number
        trailing = TRAILING_NUMBER.match(street or '')
        if trailing and not building_number:
            street, building_number = trailing['street'], trailing['number']
        if is_street(street):
            structured = {'street': street, 'city': city}
            number = HOUSE_NUMBER.match(building_number or '')
            if number:
                structured['housenumber'] = number.group()
            # The structured search can answer with another street of a similar name ("pl. Kaszubski" gave Trasa
            # Kaszubska), where the free text finds the right one, so each answer must name the street asked for.
            queries.append(('structured', structured, street))
            address = f'{street} {number.group()}' if number else street
            queries.append(('api', {'q': f'{address}, {city}', 'exclude': EXCLUDED}, street))
        if event.venue:
            queries.append(('api', {'q': f'{event.venue}, {city}', 'exclude': EXCLUDED}, None))

        for path, params, asked_street in queries:
            place = self._search(path, params, exact=True, street=asked_street)
            if place is not None:
                return place
        return self._search('api', {'q': city, 'layer': 'city'}, exact=False)

    def _search(self, path: str, query: dict, exact: bool, street: str | None = None) -> Place | None:
        key = (path, street, *sorted(query.items()))
        return self._remembered(
            key, lambda: self._first(path, {**query, 'countrycode': 'PL', 'bbox': REGION}, exact, street))

    def _remembered(self, key: tuple, ask: Callable[[], Place | None]) -> Place | None:
        if key not in self._answers:
            self._answers[key] = ask()
        return self._answers[key]

    def _first(self, path: str, params: dict, exact: bool, street: str | None) -> Place | None:
        try:
            response = self._session.get(f'{self._base_url}/{path}', timeout=20,
                                         params={**params, 'limit': 1, 'lang': 'default'})
            if response.status_code == 400:
                # Photon answers 400 to some inputs. Another event's run would stop at this one every time.
                log.warning('Photon refused path=%s params=%s', path, params)
                return None
            response.raise_for_status()
            features = response.json()['features']
            if features == []:
                return None
            properties = features[0]['properties']
            if street is not None and not same_street(street, properties):
                return None
            longitude, latitude = features[0]['geometry']['coordinates']
            return Place(float(latitude), float(longitude), properties.get('postcode'), exact)
        except requests.ConnectionError as error:
            raise GeocoderUnavailable(f'Photon is not answering at {self._base_url}. Locally: make geo-index once, '
                                      f'then make up. error={error}') from error
        except requests.RequestException as error:
            raise GeocoderUnavailable(f'Photon request failed path={path} params={params} error={error}') from error
        except WRONG_SHAPE as error:
            raise GeocoderUnavailable(f'Photon gave an unexpected answer path={path} error={error!r}') from error


def is_street(street: str | None) -> bool:
    return bool(street) and any(c.isalpha() for c in street) and not NOT_A_STREET.match(street.strip())


def same_street(asked: str, properties: dict) -> bool:
    """Whether Photon's answer is on the street asked for: every word of letters in the asked name begins a word of the
    answer's street, or of its name when the answer is the street itself, so "gen. Hallera" finds "Generała Józefa
    Hallera". A number is left out, since some streets arrive with their house number ("Rakoczego 11/U3"), unless it
    starts the name ("3 Maja"), where it must be the same number."""
    found = _words(properties.get('street') or properties.get('name') or '')
    words = [w for w in _words(asked) if w not in STREET_KINDS]
    wanted = [w for w in words if len(w) > 1 and w.isalpha()]
    if len(words) > 1 and words[0].isdigit() and words[1].isalpha():
        wanted.insert(0, words[0])
    return bool(wanted) and all(any(f == w if w.isdigit() else f.startswith(w) for f in found) for w in wanted)


def _words(text: str) -> list[str]:
    plain = unicodedata.normalize('NFKD', text.lower().replace('ł', 'l'))
    plain = ''.join(c for c in plain if not unicodedata.combining(c))
    return re.findall(r'[a-z0-9]+', plain)
