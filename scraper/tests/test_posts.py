from datetime import datetime, timezone

import pytest

from geocoder import Place
from posts import DESCRIPTION_MAX, TITLE_MAX, VENUE_MAX, cut, cut_description, to_post, utf16_len
from trojmiasto import WARSAW, Event

PLACE = Place(54.35, 18.65, '80-001', exact=True)


def event(**overrides):
    fields = dict(url='https://www.trojmiasto.pl/imprezy/x-imp1.html', title='Koncert', category='koncerty',
                  city='Gdańsk', venue='Klub', street='Portowa', building_number='9', postal_code='81-350',
                  online=False, description='Opis wydarzenia.', start=datetime(2026, 11, 5, 19, 0, tzinfo=WARSAW), end=None)
    return Event(**{**fields, **overrides})


def test_offline_post_matches_create_post_request():
    assert to_post(event(), PLACE) == {
        'title': 'Koncert',
        'description': 'Opis wydarzenia.',
        'eventUrl': 'https://www.trojmiasto.pl/imprezy/x-imp1.html',
        'startsAt': '2026-11-05T18:00:00Z',
        'tags': ['koncerty'],
        'type': 'OFFLINE',
        'visibility': 'PUBLIC',
        'location': {
            'countryCode': 'PL',
            'city': 'Gdańsk',
            'postalCode': '81-350',
            'coordinates': {'latitude': 54.35, 'longitude': 18.65},
            'venueName': 'Klub',
            'street': 'Portowa',
            'buildingNum': '9',
        },
    }


def test_a_place_with_no_postal_code_is_sent_without_one():
    post = to_post(event(postal_code=None), Place(54.35, 18.65, None, exact=False))

    assert 'postalCode' not in post['location']


def test_start_is_warsaw_time_in_summer_too():
    summer = event(start=datetime(2027, 7, 1, 19, 0, tzinfo=WARSAW))

    assert to_post(summer, PLACE)['startsAt'] == '2027-07-01T17:00:00Z'


def test_end_is_sent_in_utc():
    post = to_post(event(end=datetime(2026, 11, 5, 21, 0, tzinfo=WARSAW)), PLACE)

    assert post['endsAt'] == '2026-11-05T20:00:00Z'


def test_no_end_is_not_sent():
    assert 'endsAt' not in to_post(event(), PLACE)


def test_online_post_has_no_location():
    post = to_post(event(online=True), None)

    assert post['type'] == 'ONLINE'
    assert 'location' not in post


def test_values_a_venue_has_not_given_are_left_out_of_the_location():
    post = to_post(event(venue=None, street=None, building_number=None), PLACE)

    assert set(post['location']) == {'countryCode', 'city', 'postalCode', 'coordinates'}


def test_the_post_keeps_the_street_as_published():
    assert to_post(event(street='ul. Morska'), PLACE)['location']['street'] == 'ul. Morska'


def test_no_tag_when_there_is_no_category():
    assert to_post(event(category=None), PLACE)['tags'] == []


def test_category_with_a_comma_gives_separate_tags():
    assert to_post(event(category='sport, rekreacja'), PLACE)['tags'] == ['sport', 'rekreacja']


def test_long_values_stay_within_the_api_limits():
    long_event = event(title='słowo ' * 60, category='kategoria ' * 10, city='miasto ' * 40, venue='nazwa ' * 30,
                       street='ulica ' * 40, building_number='1' * 30, postal_code='8' * 30,
                       description='zdanie ' * 400)

    post = to_post(long_event, PLACE)

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


def test_utf16_len_counts_an_emoji_as_two():
    assert utf16_len('a😀') == 3


def test_text_exactly_at_the_limit_is_left_alone():
    assert cut('a' * 126 + '😀', 128) == 'a' * 126 + '😀'


def test_an_emoji_over_the_limit_is_cut():
    result = cut('a' * 127 + '😀', 128)

    assert result == 'a' * 127 + '…'
    assert utf16_len(result) == 128


def test_a_cut_does_not_split_an_emoji():
    result = cut('a' * 126 + '😀' + 'b' * 3, 128)

    assert result == 'a' * 126 + '…'
    assert utf16_len(result) == 127


def test_a_title_of_128_code_points_and_129_units_fits_after_the_cut():
    post = to_post(event(title='a' * 127 + '😀'), PLACE)

    assert utf16_len(post['title']) <= TITLE_MAX


def test_a_description_of_1000_code_points_and_1030_units_fits_after_the_cut():
    result = cut_description('😀 ' * 30 + 'a' * 940)

    assert utf16_len(result) <= DESCRIPTION_MAX
    result.encode('utf-8')


def test_a_description_is_not_split_inside_an_emoji_at_its_limit():
    result = cut_description('a' * 999 + '😀' + 'b' * 10)

    assert utf16_len(result) <= DESCRIPTION_MAX
    result.encode('utf-8')


def test_a_description_with_emoji_is_cut_at_a_sentence_end():
    result = cut_description('😀 Pierwsze zdanie jest tutaj. ' * 40)

    assert result.endswith('tutaj.')
    assert utf16_len(result) <= DESCRIPTION_MAX


def test_postal_code_and_building_number_stay_within_the_limits():
    post = to_post(event(building_number='1' * 15 + '😀', postal_code='8' * 15 + '😀'), PLACE)

    assert utf16_len(post['location']['buildingNum']) <= 16
    assert utf16_len(post['location']['postalCode']) <= 16
    post['location']['buildingNum'].encode('utf-8')


def test_description_with_no_sentence_end_is_cut_at_a_word():
    result = cut_description('słowo ' * 300)

    assert result.endswith('…')
    assert len(result) <= DESCRIPTION_MAX


def test_start_keeps_its_time_zone_through_the_post():
    assert to_post(event(), PLACE)['startsAt'].endswith('Z')
    assert event().start.astimezone(timezone.utc).hour == 18
