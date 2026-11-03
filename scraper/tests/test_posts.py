from datetime import date, datetime, time, timezone

import pytest

from geocoder import Place
from posts import DESCRIPTION_MAX, VENUE_MAX, build_post, cut, cut_description, starts_at
from trojmiasto import EventPage, ListingItem

PLACE = Place(54.35, 18.65, '80-001', exact=True)


def item(**overrides):
    fields = dict(url='https://www.trojmiasto.pl/imprezy/x-imp1.html', title='Koncert', category='koncerty',
                  city='Gdańsk', venue=None, start_date=None, hour=time(19, 0))
    return ListingItem(**{**fields, **overrides})


def page(**overrides):
    fields = dict(start_date=date(2026, 11, 5), end=None, online=False, description='Opis wydarzenia.',
                  venue='Klub', street='Portowa', building_number='9', postal_code='81-350', hour=None)
    return EventPage(**{**fields, **overrides})


def test_offline_post_matches_create_post_request():
    assert build_post(item(), page(), PLACE) == {
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
    assert build_post(item(), page(start_date=date(2027, 7, 1)), PLACE)['startsAt'] == '2027-07-01T17:00:00Z'


def test_hour_falls_back_to_the_page_then_to_noon():
    assert starts_at(item(hour=None), page(hour=time(20, 15))).hour == 20
    assert starts_at(item(hour=None), page()).hour == 12


def test_listing_date_wins_because_a_page_can_cover_several_showings():
    assert starts_at(item(start_date=date(2026, 11, 20)), page()).day == 20


def test_date_from_the_listing_alone():
    assert starts_at(item(start_date=date(2026, 11, 20)), page(start_date=None)).day == 20


def test_no_date_anywhere():
    assert starts_at(item(), page(start_date=None)) is None


def test_theatre_event_takes_its_venue_from_the_listing():
    theatre = page(start_date=None, venue=None, street=None, building_number=None, postal_code=None)

    post = build_post(item(venue='Teatr', start_date=date(2026, 11, 20)), theatre, PLACE)

    assert post['location']['venueName'] == 'Teatr'
    assert post['location']['postalCode'] == '80-001'


def test_online_post_has_no_location():
    post = build_post(item(), page(online=True), None)

    assert post['type'] == 'ONLINE'
    assert 'location' not in post


def test_offline_event_with_no_place_is_left_out_not_sent_as_online():
    assert build_post(item(), page(), None) is None


def test_postal_code_falls_back_to_the_geocoder():
    post = build_post(item(), page(postal_code=None), PLACE)

    assert post['location']['postalCode'] == '80-001'


def test_no_postal_code_anywhere_is_left_out():
    assert build_post(item(), page(postal_code=None), Place(54.3, 18.6, None, exact=True)) is None


def test_multi_day_event_runs_to_the_end_of_its_last_day():
    end = datetime.fromisoformat('2026-11-08T00:00:00+01:00')

    assert build_post(item(), page(end=end), PLACE)['endsAt'] == '2026-11-08T22:59:00Z'


def test_end_before_the_start_is_dropped():
    end = datetime.fromisoformat('2026-11-05T10:00:00+01:00')

    assert 'endsAt' not in build_post(item(), page(end=end), PLACE)


def test_empty_description_is_built_from_the_facts():
    assert build_post(item(), page(description=''), PLACE)['description'] == 'Koncerty: Koncert, Klub, Gdańsk.'


def test_no_tag_when_there_is_no_category():
    assert build_post(item(category=None), page(), PLACE)['tags'] == []


def test_category_with_a_comma_gives_separate_tags():
    assert build_post(item(category='sport, rekreacja'), page(), PLACE)['tags'] == ['sport', 'rekreacja']


def test_long_values_stay_within_the_api_limits():
    long_item = item(title='słowo ' * 60, category='kategoria ' * 10, city='miasto ' * 40)
    long_page = page(venue='nazwa ' * 30, street='ulica ' * 40, building_number='1' * 30,
                     postal_code='8' * 30, description='zdanie ' * 400)

    post = build_post(long_item, long_page, PLACE)

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


def test_starts_at_is_timezone_aware():
    assert starts_at(item(), page()).astimezone(timezone.utc).hour == 18
