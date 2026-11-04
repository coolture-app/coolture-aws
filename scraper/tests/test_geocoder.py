from datetime import datetime, timezone

import pytest
import requests

from geocoder import Geocoder, GeocoderUnavailable, Place
from trojmiasto import Event


class FakeResponse:
    def __init__(self, hits, status=200):
        self._hits = hits
        self.status_code = status

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(str(self.status_code))

    def json(self):
        return self._hits


class FakeSession:

    def __init__(self, answers=None, error=None):
        self.answers = answers or {}
        self.error = error
        self.reverse = {'address': {'postcode': '80-001'}}
        self.queries = []

    def get(self, url, params, timeout):
        self.queries.append(params)
        if self.error:
            raise self.error
        if url.endswith('/reverse'):
            return FakeResponse(self.reverse)
        asked = params.get('street') or params.get('q') or params['city']
        return FakeResponse([self.answers[asked]] if asked in self.answers else [])


def hit(lat='54.5', lon='18.5', postcode='81-350'):
    return {'lat': lat, 'lon': lon, 'address': {'postcode': postcode}}


def geocoder(session):
    return Geocoder(session, 'http://nominatim.test/', sleep=lambda _: None)


def event(venue, street, building_number, city):
    return Event(url='https://site.test/1', title='Koncert', category=None, city=city, venue=venue, street=street,
                 building_number=building_number, postal_code=None, online=False, description='Opis.',
                 start=datetime(2026, 11, 5, tzinfo=timezone.utc), end=None)


def test_street_address_wins_and_is_exact():
    session = FakeSession({'9 Portowa': hit()})

    place = geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert (place.latitude, place.longitude, place.exact) == (54.5, 18.5, True)
    assert len(session.queries) == 1


def test_venue_is_tried_when_the_street_misses():
    session = FakeSession({'Klub, Gdynia': hit()})

    place = geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert place.exact is True
    assert session.queries[1]['q'] == 'Klub, Gdynia'


def test_city_centre_is_the_last_resort_and_not_exact():
    session = FakeSession({'Gdynia': hit()})

    place = geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert place.exact is False


def test_a_hit_keeps_its_own_postal_code_and_asks_nothing_more():
    session = FakeSession({'Gdynia': hit()})

    assert geocoder(session).locate(event(None, None, None, 'Gdynia')).postal_code == '81-350'
    assert len(session.queries) == 1


def test_a_hit_with_no_postal_code_asks_nothing_more_by_itself():
    session = FakeSession({'Gdynia': hit(postcode=None)})

    assert geocoder(session).locate(event(None, None, None, 'Gdynia')).postal_code is None
    assert len(session.queries) == 1


def test_postcode_at_asks_at_the_point_of_the_place():
    session = FakeSession()

    assert geocoder(session).postcode_at(Place(54.5, 18.5, None, exact=False)) == '80-001'
    assert (session.queries[0]['lat'], session.queries[0]['lon']) == (54.5, 18.5)


def test_postcode_at_asks_once_for_a_place():
    session = FakeSession()
    subject = geocoder(session)

    subject.postcode_at(Place(54.5, 18.5, None, exact=False))
    subject.postcode_at(Place(54.5, 18.5, None, exact=False))

    assert len(session.queries) == 1


def test_no_postal_code_at_the_point_either():
    session = FakeSession()
    session.reverse = {'error': 'Unable to geocode'}

    assert geocoder(session).postcode_at(Place(54.5, 18.5, None, exact=False)) is None


def test_nothing_found():
    assert geocoder(FakeSession()).locate(event('Klub', 'Portowa', '9', 'Gdynia')) is None


def test_no_city_is_not_looked_up():
    session = FakeSession()

    assert geocoder(session).locate(event('Klub', 'Portowa', '9', None)) is None
    assert session.queries == []


def test_each_address_is_asked_once():
    session = FakeSession({'9 Portowa': hit()})
    subject = geocoder(session)

    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))
    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert len(session.queries) == 1


def test_a_miss_is_remembered_too():
    session = FakeSession()
    subject = geocoder(session)

    subject.locate(event(None, 'Portowa', '9', 'Gdynia'))
    asked = len(session.queries)
    subject.locate(event(None, 'Portowa', '9', 'Gdynia'))

    assert len(session.queries) == asked


def test_search_is_held_to_the_region():
    session = FakeSession({'9 Portowa': hit()})

    geocoder(session).locate(event(None, 'Portowa', '9', 'Gdynia'))

    assert session.queries[0]['countrycodes'] == 'pl'
    assert session.queries[0]['bounded'] == 1
    assert session.queries[0]['viewbox']
    assert session.queries[0]['format'] == 'jsonv2'


def test_every_request_waits_its_turn():
    pauses = []
    session = FakeSession()
    subject = Geocoder(session, 'http://nominatim.test', pause_s=1.0, sleep=pauses.append)

    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert pauses == [1.0, 1.0, 1.0]


def test_a_service_failure_stops_the_run_instead_of_finding_nothing():
    session = FakeSession(error=requests.ConnectionError('down'))

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))


def test_a_throttle_on_a_street_does_not_fall_back_to_the_city_centre():
    session = FakeSession({'Gdynia': hit()})
    session.get = lambda url, params, timeout: FakeResponse([], status=429)

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))


def test_a_failed_request_is_not_remembered():
    session = FakeSession({'9 Portowa': hit()}, error=requests.ConnectionError('down'))
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
    session.get = lambda url, params, timeout: NotJson([])

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).locate(event(None, 'Portowa', '9', 'Gdynia'))


def test_an_answer_of_the_wrong_shape_means_the_service_could_not_be_asked():
    session = FakeSession()
    session.get = lambda url, params, timeout: FakeResponse({'error': 'Bad request'})

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).locate(event(None, 'Portowa', '9', 'Gdynia'))
