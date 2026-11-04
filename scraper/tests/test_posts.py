from datetime import datetime, timezone

import pytest

from geocoder import Place
from posts import DESCRIPTION_MAX, VENUE_MAX, cut, cut_description, to_post
from trojmiasto import WARSAW, Event

PLACE = Place(54.35, 18.65, '80-001', exact=True)


def event(**overrides):
    fields = dict(url='https://www.trojmiasto.pl/imprezy/x-imp1.html', title='Koncert', category='koncerty',
                  city='Gdańsk', venue='Klub', street='Portowa', building_number='9', postal_code='81-350',
                  online=False, description='Opis wydarzenia.', start=datetime(2026, 11, 5, 19, 0, tzinfo=WARSAW), end=None)
    return Event(**{**fields, **overrides})


def test_offline_post_matches_create_post_request():
    assert to_post(event(), PLACE, '81-350') == {
        'title': 'Koncert',
        'description': 'Opis wydarzenia.',
        'eventUrl': 'https://www.trojmiasto.pl/imprezy/x-imp1.html',
        'startsAt': '2026-11-05T18:00:00Z',
        'tags': ['koncerty'],
        'type': 'OFFLINE',
        'visibility': 'PUBLIC',
        'location': {
            'countryCode': 'POL',
            'city': 'Gdańsk',
            'postalCode': '81-350',
            'coordinates': {'latitude': 54.35, 'longitude': 18.65},
            'venueName': 'Klub',
            'street': 'Portowa',
            'buildingNum': '9',
        },
    }


def test_start_is_warsaw_time_in_summer_too():
    summer = event(start=datetime(2027, 7, 1, 19, 0, tzinfo=WARSAW))

    assert to_post(summer, PLACE, '81-350')['startsAt'] == '2027-07-01T17:00:00Z'


def test_end_is_sent_in_utc():
    post = to_post(event(end=datetime(2026, 11, 5, 21, 0, tzinfo=WARSAW)), PLACE, '81-350')

    assert post['endsAt'] == '2026-11-05T20:00:00Z'


def test_no_end_is_not_sent():
    assert 'endsAt' not in to_post(event(), PLACE, '81-350')


def test_online_post_has_no_location():
    post = to_post(event(online=True), None, None)

    assert post['type'] == 'ONLINE'
    assert 'location' not in post


def test_values_a_venue_has_not_given_are_left_out_of_the_location():
    post = to_post(event(venue=None, street=None, building_number=None), PLACE, '81-350')

    assert set(post['location']) == {'countryCode', 'city', 'postalCode', 'coordinates'}


def test_no_tag_when_there_is_no_category():
    assert to_post(event(category=None), PLACE, '81-350')['tags'] == []


def test_category_with_a_comma_gives_separate_tags():
    assert to_post(event(category='sport, rekreacja'), PLACE, '81-350')['tags'] == ['sport', 'rekreacja']


def test_long_values_stay_within_the_api_limits():
    long_event = event(title='słowo ' * 60, category='kategoria ' * 10, city='miasto ' * 40, venue='nazwa ' * 30,
                       street='ulica ' * 40, building_number='1' * 30, description='zdanie ' * 400)

    post = to_post(long_event, PLACE, '8' * 30)

    assert len(post['title']) <= 128
    assert len(post['description']) <= DESCRIPTION_MAX
    assert len(post['tags'][0]) <= 32
    assert len(post['location']['venueName']) <= VENUE_MAX
    assert len(post['location']['street']) <= 128
    assert len(post['location']['city']) <= 128
    assert len(post['location']['buildingNum']) <= 16
    assert len(post['location']['postalCode']) <= 16


@pytest.mark.parametrize('text', ['krótki tekst', 'x' * 10])
def test_cut_leaves_short_text(text):
    assert cut(text, 20) == text


def test_cut_breaks_at_a_word_and_marks_the_cut():
    assert cut('Wielki koncert muzyki filmowej', 16) == 'Wielki koncert…'


def test_cut_leaves_no_mark_alone_on_a_line():
    assert cut('- jeden\n- dwa trzy', 14) == '- jeden…'


@pytest.mark.parametrize('gap', [' ', '\n'])
def test_description_is_cut_at_a_sentence_end(gap):
    result = cut_description(f'Pierwsze zdanie jest tutaj.{gap}' * 20 + 'Ostatnie ' * 100)

    assert result.endswith('tutaj.')
    assert len(result) <= DESCRIPTION_MAX


def test_description_is_not_cut_at_an_abbreviation():
    result = cut_description('Pierwsze zdanie jest tutaj. ' * 30 + 'Gramy m.in. Piazzollę ' * 30)

    assert result.endswith('tutaj.')


def test_description_with_no_sentence_end_is_cut_at_a_word():
    result = cut_description('słowo ' * 300)

    assert result.endswith('…')
    assert len(result) <= DESCRIPTION_MAX


def test_start_keeps_its_time_zone_through_the_post():
    assert to_post(event(), PLACE, '81-350')['startsAt'].endswith('Z')
    assert event().start.astimezone(timezone.utc).hour == 18
