from datetime import datetime, timezone

import pytest
import requests

from geocoder import Geocoder, GeocoderUnavailable, is_street, same_street
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
    """Photon, answering each search from `answers` by its street, q or city."""

    def __init__(self, answers=None, error=None):
        self.answers = answers or {}
        self.error = error
        self.requests = []

    def get(self, url, params, timeout):
        self.requests.append((url.rsplit('/', 1)[-1], params))
        if self.error:
            raise self.error
        asked = params.get('street') or params['q']
        return FakeResponse({'features': [self.answers[asked]] if asked in self.answers else []})


def feature(lon=18.5, lat=54.5, postcode='81-350', name=None):
    properties = {'postcode': postcode} if postcode else {}
    if name:
        properties['name'] = name
    return {'type': 'Feature', 'geometry': {'type': 'Point', 'coordinates': [lon, lat]}, 'properties': properties}


def geocoder(session):
    return Geocoder(session, 'http://photon.test/')


def event(venue, street, building_number, city):
    return Event(url='https://site.test/1', title='Koncert', category=None, city=city, venue=venue, street=street,
                 building_number=building_number, postal_code=None, online=False, description='Opis.',
                 start=datetime(2026, 11, 5, tzinfo=timezone.utc), end=None)


def test_street_address_wins_and_is_exact():
    session = FakeSession({'Portowa': feature(name='Portowa')})

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
    assert [params.get('q') for _, params in session.requests] == [None, 'Portowa 9, Gdynia', 'Klub, Gdynia']
    path, params = session.requests[2]
    assert path == 'api'
    assert 'osm.highway.bus_stop' in params['exclude']


def test_city_centre_is_the_last_resort_and_not_exact():
    session = FakeSession({'Gdynia': feature()})

    place = geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert place.exact is False
    assert session.requests[-1][1]['layer'] == 'city'


@pytest.mark.parametrize('postcode, expected', [('81-350', '81-350'), (None, None)])
def test_a_hit_gives_its_own_postal_code_and_asks_nothing_more(postcode, expected):
    session = FakeSession({'Gdynia': feature(postcode=postcode)})

    assert geocoder(session).locate(event(None, None, None, 'Gdynia')).postal_code == expected
    assert len(session.requests) == 1


def test_nothing_found():
    assert geocoder(FakeSession()).locate(event('Klub', 'Portowa', '9', 'Gdynia')) is None


def test_no_city_is_not_looked_up():
    session = FakeSession()

    assert geocoder(session).locate(event('Klub', 'Portowa', '9', None)) is None
    assert session.requests == []


@pytest.mark.parametrize('answers', [{'Portowa': feature(name='Portowa')}, {}], ids=['a hit', 'a miss'])
def test_each_address_is_asked_once_whatever_the_answer(answers):
    session = FakeSession(answers)
    subject = geocoder(session)

    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))
    asked = len(session.requests)
    subject.locate(event('Klub', 'Portowa', '9', 'Gdynia'))

    assert len(session.requests) == asked


def test_a_geocoder_that_is_not_running_says_how_to_start_it():
    session = FakeSession(error=requests.ConnectionError('refused'))

    with pytest.raises(GeocoderUnavailable, match='make geo-index'):
        geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))


@pytest.mark.parametrize('status', [403, 502])
def test_a_failed_request_on_a_street_does_not_fall_back_to_the_city_centre(status):
    session = FakeSession({'Gdynia': feature()})
    session.get = lambda url, params, timeout: FakeResponse({}, status=status)

    with pytest.raises(GeocoderUnavailable):
        geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia'))


def test_an_input_photon_refuses_finds_nothing_and_does_not_stop_the_run():
    session = FakeSession()
    session.get = lambda url, params, timeout: FakeResponse({}, status=400)

    assert geocoder(session).locate(event('Klub', 'Portowa', '9', 'Gdynia')) is None


def test_a_failed_request_is_not_remembered():
    session = FakeSession({'Portowa': feature(name='Portowa')}, error=requests.ConnectionError('down'))
    subject = geocoder(session)

    with pytest.raises(GeocoderUnavailable):
        subject.locate(event(None, 'Portowa', '9', 'Gdynia'))
    session.error = None

    assert subject.locate(event(None, 'Portowa', '9', 'Gdynia')).exact is True


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


def test_a_structured_answer_on_another_street_gives_way_to_the_free_text():
    session = FakeSession({'pl. Kaszubski': feature(18.47, 54.47, name='Trasa Kaszubska – Kaszëbskô Darga'),
                           'pl. Kaszubski 7B, Gdynia': feature(18.54, 54.52, name='Plac Kaszubski')})

    place = geocoder(session).locate(event('Cafe Strych', 'pl. Kaszubski', '7B', 'Gdynia'))

    assert (place.latitude, place.longitude, place.exact) == (54.52, 18.54, True)


def test_a_hall_in_the_street_field_is_not_searched_and_the_venue_places_it():
    session = FakeSession({'Hevelianum, Gdańsk': feature(18.64, 54.36)})

    place = geocoder(session).locate(event('Hevelianum', 'w Sali Haxo', None, 'Gdańsk'))

    assert (place.latitude, place.longitude) == (54.36, 18.64)
    assert [params.get('q') for _, params in session.requests] == ['Hevelianum, Gdańsk']


@pytest.mark.parametrize('street, expected', [
    ('Portowa', True), ('w Sali Haxo', False), ('W sali kameralnej', False), ('we Dworze Artusa', False),
    ('Sala Koncertowa', False), ('35/38', False), ('', False), (None, False), ('Wały Jagiellońskie', True),
    ('Wielopole', True), ('W. Stwosza', True), ('w. Witosa', True), ('W Sali Haxo', False),
])
def test_what_counts_as_a_street(street, expected):
    assert is_street(street) is expected


@pytest.mark.parametrize('asked, properties, expected', [
    ('pl. Kaszubski', {'name': 'Plac Kaszubski'}, True),
    ('pl. Kaszubski', {'name': 'Trasa Kaszubska – Kaszëbskô Darga'}, False),
    ('Gradowa', {'street': 'Gradowa', 'name': 'Hevelianum'}, True),
    ('gen. J. Hallera', {'name': 'Generała Józefa Hallera'}, True),
    ('al. Zwycięstwa', {'name': 'Aleja Zwycięstwa'}, True),
    ('Łąkowa', {'name': 'Lakowa'}, True),
    ('3 Maja', {'name': '3 Maja'}, True),
    ('3 Maja', {'name': '1 Maja'}, False),
    ('3 Maja', {'name': '30 Maja'}, False),
    ('10 Lutego', {'street': '1 Lutego'}, False),
    ('ul. 3 Maja 11', {'street': '3 Maja'}, True),
    ('Franciszka Rakoczego 11/U3', {'street': 'Franciszka Rakoczego', 'name': 'Sun Way Travel'}, True),
    ('Portowa', {}, False),
    ('ul.', {'name': 'Portowa'}, False),
])
def test_the_same_street(asked, properties, expected):
    assert same_street(asked, properties) is expected


def test_a_number_left_in_the_street_is_sent_as_the_house_number():
    session = FakeSession({'Franciszka Rakoczego': feature(name='Sun Way Travel')})
    session.answers['Franciszka Rakoczego']['properties']['street'] = 'Franciszka Rakoczego'

    geocoder(session).locate(event('Good Time', 'Franciszka Rakoczego 11/U3', None, 'Gdańsk'))

    assert session.requests[0][1]['street'] == 'Franciszka Rakoczego'
    assert session.requests[0][1]['housenumber'] == '11'
