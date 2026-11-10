from datetime import datetime, timezone

import pytest
import requests

from geocoder import Geocoder, GeocoderUnavailable, Place
from trojmiasto import Event


class FakeResponse:
    def __init__(self, body, status=200):
        self._body = body
        self.status_code = status

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(str(self.status_code))

    def json(self):
        return self._body


class FakeSession:
    """Photon, answering each search from `answers` by its street, q or city, and every reverse with `reverse`."""

    def __init__(self, answers=None, error=None):
        self.answers = answers or {}
        self.error = error
        self.reverse = [feature(postcode='80-001')]
        self.requests = []

    def get(self, url, params, timeout):
        self.requests.append((url.rsplit('/', 1)[-1], params))
        if self.error:
            raise self.error
        if url.endswith('/reverse'):
            return FakeResponse({'features': self.reverse})
        asked = params.get('street') or params['q']
        return FakeResponse({'features': [self.answers[asked]] if asked in self.answers else []})


def feature(lon=18.5, lat=54.5, postcode='81-350'):
    properties = {'postcode': postcode} if postcode else {}
    return {'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [lon, lat]}, 'properties': properties}


def geocoder(session):
    return Geocoder(session, 'http://photon.test/')


def event(venue, street, building_number, city):
    return Event(url='https://site.test/1', title='Koncert', category=None, city=city, venue=venue, street=street,
                 building_number=building_number, postal_code=None, online=False, description='Opis.',
                 start=datetime(2026, 11, 5, tzinfo=timezone.utc), end=None)


def test_street_address_wins_and_is_exact():
    session = FakeSession({'Portowa': feature()})

    place = geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert (place.latitude, place.longitude, place.exact) == (54.5, 18.5, True)
    assert session.requests == [('structured', {'street': 'Portowa', 'city': 'Gdynia', 'housenumber': '9',
                                                'countrycode': 'PL', 'bbox': '16.5,53.3,20.0,55.0', 'limit': 1,
                                                'lang': 'default'})]


@pytest.mark.parametrize('building_number, sent', [
    ('9', '9'),
    ('12a', '12a'),
    ('35/38', '35'),
    ('27-31/M.30', '27'),
    ('11/U3', '11'),
])
def test_only_the_leading_house_number_is_sent(building_number, sent):
    session = FakeSession({'Portowa': feature()})

    geocoder(session).locate(event(None, 'Portowa', building_number, 'Gdynia'))

    assert session.requests[0][1]['housenumber'] == sent


def test_a_street_with_no_number_is_searched_without_one():
    session = FakeSession({'Portowa': feature()})

    geocoder(session).locate(event(None, 'Portowa', None, 'Gdynia'))

    assert 'housenumber' not in session.requests[0][1]


def test_venue_is_tried_when_the_street_misses_and_leaves_out_stops():
    session = FakeSession({'Klub, Gdynia': feature()})

    place = geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert place.exact is True
    path, params = session.requests[1]
    assert (path, params['q']) == ('api', 'Klub, Gdynia')
    assert 'osm.highway.bus_stop' in params['exclude']


def test_city_centre_is_the_last_resort_and_not_exact():
    session = FakeSession({'Gdynia': feature()})

    place = geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert place.exact is False
    assert session.requests[-1][1]['layer'] == 'city'


def test_a_hit_keeps_its_own_postal_code_and_asks_nothing_more():
    session = FakeSession({'Gdynia': feature()})

    assert geocoder(session).locate(event(None, None, None, 'Gdynia')).postal_code == '81-350'
    assert len(session.requests) == 1


def test_a_hit_with_no_postal_code_asks_nothing_more_by_itself():
    session = FakeSession({'Gdynia': feature(postcode=None)})

    assert geocoder(session).locate(event(None, None, None, 'Gdynia')).postal_code is None
    assert len(session.requests) == 1


def test_postcode_at_asks_at_the_point_of_the_place():
    session = FakeSession()

    assert geocoder(session).postcode_at(Place(54.5, 18.5, None, exact=False)) == '80-001'
    path, params = session.requests[0]
    assert (path, params['lat'], params['lon']) == ('reverse', 54.5, 18.5)
    # Photon's reverse lookup answers 400 to a country filter.
    assert 'countrycode' not in params


def test_postcode_at_asks_once_for_a_place():
    session = FakeSession()
    subject = geocoder(session)

    subject.postcode_at(Place(54.5, 18.5, None, exact=False))
    subject.postcode_at(Place(54.5, 18.5, None, exact=False))

    assert len(session.requests) == 1


def test_no_postal_code_at_the_point_either():
    session = FakeSession()
    session.reverse = []

    assert geocoder(session).postcode_at(Place(54.5, 18.5, None, exact=False)) is None


def test_nothing_found():
    assert geocoder(FakeSession()).locate(event('Klub', 'Portowa', '9', 'Gdynia')) is None


def test_no_city_is_not_looked_up():
    session = FakeSession()

    assert geocoder(session).locate(event('Klub', 'Portowa', '9', None)) is None
    assert session.requests == []


def test_each_address_is_asked_once():
    session = FakeSession({'Portowa': feature()})
    subject = geocoder(session)

    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))
    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert len(session.requests) == 1


def test_a_miss_is_remembered_too():
    session = FakeSession()
    subject = geocoder(session)

    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))
    asked = len(session.requests)
    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert len(session.requests) == asked


def test_a_geocoder_that_is_not_running_says_how_to_start_it():
    session = FakeSession(error=requests.ConnectionError('refused'))

    with pytest.raises(GeocoderUnavailable, match='make geo-index'):
        geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))


def test_a_failed_request_on_a_street_does_not_fall_back_to_the_city_centre():
    session = FakeSession({'Gdynia': feature()})
    session.get = lambda url, params, timeout: FakeResponse({}, status=400)

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))


def test_a_failed_request_is_not_remembered():
    session = FakeSession({'Portowa': feature()}, error=requests.ConnectionError('down'))
    subject = geocoder(session)

    with pytest.raises(GeocoderUnavailable):
        subject.locate(event(None, 'Portowa', '9', 'Gdynia'))
    session.error = None

    assert subject.locate(event(None, 'Portowa', '9', 'Gdynia')).exact is True


def test_a_failure_of_the_reverse_lookup_stops_the_run_too():
    session = FakeSession(error=requests.Timeout('slow'))

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).postcode_at(Place(54.5, 18.5, None, exact=False))


def test_a_body_that_is_not_json_means_the_service_could_not_be_asked():
    class NotJson(FakeResponse):
        def json(self):
            raise requests.exceptions.JSONDecodeError('Expecting value', '<html>', 0)

    session = FakeSession()
    session.get = lambda url, params, timeout: NotJson({})

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).locate(event(None, 'Portowa', '9', 'Gdynia'))


@pytest.mark.parametrize('body', [
    {'message': 'Bad request'},
    {'features': 'none'},
    {'features': [{'properties': {}}]},
    {'features': [{'geometry': {'coordinates': [18.5]}, 'properties': {}}]},
])
def test_an_answer_of_the_wrong_shape_means_the_service_could_not_be_asked(body):
    session = FakeSession()
    session.get = lambda url, params, timeout: FakeResponse(body)

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).locate(event(None, 'Portowa', '9', 'Gdynia'))


def test_a_reverse_answer_of_the_wrong_shape_means_the_service_could_not_be_asked():
    session = FakeSession()
    session.reverse = [{'properties': 'none'}]

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).postcode_at(Place(54.5, 18.5, None, exact=False))
