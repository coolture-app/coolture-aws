import json
from datetime import date, time

import pytest
import requests

from trojmiasto import Site, clean_description, parse_event_page, parse_listing


def listing_item(title='Koncert testowy', href='/imprezy/Koncert-imp1.html', hour='Godz.19:30', city='Gdańsk,',
                 day='2026-11-05', venue='Klub Testowy'):
    return f'''
    <div class="event__item">
      <div class="event__item__category"><a>Koncerty</a></div>
      <a class="event__item__title" href="{href}">{title}</a>
      <p class="event__item__date" content="{day}">czwartek, <span class="event__item__date__hour">{hour}</span></p>
      <span class="event__item__location__city">{city}</span>
      <a class="event__item__location__place">{venue}</a>
    </div>'''


def event_page(body='', **overrides):
    block = {
        '@type': 'Event',
        'name': 'Koncert testowy, Klub 5 listopada 2026 (czwartek), godz. 20:15',
        'description': 'Opis z bloku',
        'startDate': '2026-11-05T00:00:00+01:00',
        'eventAttendanceMode': 'https://schema.org/OfflineEventAttendanceMode',
        'location': {
            'name': 'Klub Testowy',
            'address': {'streetAddress': 'Klub Testowy, Gdynia, Portowa 9', 'postalCode': '81-350'},
        },
        **overrides,
    }
    return f'<script type="application/ld+json">{json.dumps(block)}</script>{body}'


def test_listing_reads_every_field():
    item = parse_listing(listing_item())[0]

    assert item.url == 'https://www.trojmiasto.pl/imprezy/Koncert-imp1.html'
    assert item.title == 'Koncert testowy'
    assert item.category == 'koncerty'
    assert item.city == 'Gdańsk'
    assert item.venue == 'Klub Testowy'
    assert item.start_date == date(2026, 11, 5)
    assert item.hour == time(19, 30)


def test_listing_skips_an_item_with_no_link():
    assert parse_listing('<div class="event__item"><span>Reklama</span></div>') == []


def test_listing_keeps_an_item_with_no_date():
    assert parse_listing(listing_item(day=''))[0].start_date is None


def test_the_region_is_not_a_listing_venue():
    assert parse_listing(listing_item(venue='Trójmiasto'))[0].venue is None


def test_listing_keeps_an_item_with_no_hour():
    assert parse_listing(listing_item(hour='cały dzień'))[0].hour is None


def test_event_page_reads_the_address_and_ignores_the_block_midnight():
    page = parse_event_page(event_page())

    assert page.start_date == date(2026, 11, 5)
    assert page.hour == time(20, 15)
    assert page.venue == 'Klub Testowy'
    assert page.street == 'Portowa'
    assert page.building_number == '9'
    assert page.postal_code == '81-350'
    assert page.online is False


def test_street_with_a_flat_number_and_a_remark():
    address = {'streetAddress': 'Fundacja, Gdańsk, Jaśkowa Dolina 4/11 (II piętro)'}

    page = parse_event_page(event_page(location={'name': 'Fundacja', 'address': address}))

    assert (page.street, page.building_number) == ('Jaśkowa Dolina', '4/11')


def test_street_without_a_number():
    address = {'streetAddress': 'Targ, Gdańsk, Targ Węglowy'}

    page = parse_event_page(event_page(location={'name': 'Targ', 'address': address}))

    assert (page.street, page.building_number) == ('Targ Węglowy', None)


def test_address_holding_only_a_city_has_no_street():
    page = parse_event_page(event_page(location={'name': 'Klub', 'address': {'streetAddress': 'Gdańsk'}}))

    assert page.street is None


def test_the_region_is_not_a_venue():
    page = parse_event_page(event_page(location={'name': 'Trójmiasto', 'address': {}}))

    assert page.venue is None


def test_location_given_as_text_gives_an_event_with_no_address():
    page = parse_event_page(event_page(location='Do ustalenia'))

    assert (page.venue, page.street, page.postal_code) == (None, None, None)


def test_address_given_as_text_gives_an_event_with_no_address():
    page = parse_event_page(event_page(location={'name': 'Klub', 'address': 'Trójmiasto'}))

    assert (page.venue, page.street, page.postal_code) == ('Klub', None, None)


def test_a_postal_code_that_is_not_text_is_ignored():
    address = {'streetAddress': 'Klub, Gdynia, Portowa 9', 'postalCode': 81350}

    page = parse_event_page(event_page(location={'name': 'Klub', 'address': address}))

    assert (page.street, page.postal_code) == ('Portowa', None)


@pytest.mark.parametrize('field', ['name', 'description', 'startDate', 'endDate', 'eventAttendanceMode', 'location'])
@pytest.mark.parametrize('value', [12, None, ['a'], {'a': 1}, True])
def test_a_field_of_any_other_type_does_not_stop_the_parser(field, value):
    assert parse_event_page(event_page(**{field: value})) is not None


def test_online_event():
    page = parse_event_page(event_page(eventAttendanceMode='https://schema.org/OnlineEventAttendanceMode'))

    assert page.online is True


def test_page_with_no_block_and_no_text_gives_nothing():
    assert parse_event_page('<html><body>Nie znaleziono</body></html>') is None


def test_theatre_page_has_only_a_description():
    page = parse_event_page('<div class="text-contents"><p>Spektakl o miłości.</p></div>')

    assert page.description == 'Spektakl o miłości.'
    assert (page.start_date, page.venue, page.street, page.postal_code) == (None, None, None, None)
    assert page.online is False


def test_block_with_no_start_date_leaves_the_date_to_the_listing():
    assert parse_event_page(event_page(startDate='')).start_date is None


def test_broken_json_block_is_skipped():
    page = parse_event_page('<script type="application/ld+json">{oops</script>' + event_page())

    assert page is not None


def test_description_prefers_the_page_text_and_keeps_paragraphs_apart():
    body = '<div class="text-contents"><p>Pierwszy akapit</p><p>Drugi <b>akapit</b>.</p></div>'

    assert parse_event_page(event_page(body)).description == 'Pierwszy akapit\nDrugi akapit.'


def test_a_short_page_text_does_not_replace_a_longer_block():
    body = '<div class="text-contents"><a href="/x">Zobacz</a></div>'

    assert parse_event_page(event_page(body, description='Długi opis z bloku.')).description == 'Długi opis z bloku.'


def test_description_keeps_lines_and_inline_punctuation():
    body = '<div class="text-contents">Jeden<br/><br/><strong>Dwa</strong>, trzy<ul><li>Cztery</li></ul></div>'

    assert parse_event_page(event_page(body)).description == 'Jeden\nDwa, trzy\nCztery'


def test_page_text_unglues_sentences_but_leaves_brand_names():
    body = '<div class="text-contents">Stoiska z całego świata.Atrakcje od AmberSky</div>'

    assert parse_event_page(event_page(body)).description == 'Stoiska z całego świata. Atrakcje od AmberSky'


def test_description_leaves_out_the_site_links_and_footer():
    body = '''<div class="text-contents">Opis koncertu.<br/>
        <a class="calendar-info-box"><span>jazz - koncerty w Trójmieście</span></a>
        <div class="more_info"><h4>Zobacz relację</h4></div>
        <div class="imp__textInfo"><span>Treść tworzona na podstawie materiałów prasowych organizatora</span></div>
    </div>'''

    assert parse_event_page(event_page(body)).description == 'Opis koncertu.'


def test_description_falls_back_to_the_block():
    assert parse_event_page(event_page()).description == 'Opis z bloku'


def test_page_holding_only_site_links_falls_back_to_the_block():
    body = '<div class="text-contents"><a class="calendar-info-box">jazz - koncerty w Trójmieście</a></div>'

    assert parse_event_page(event_page(body)).description == 'Opis z bloku'


def test_block_description_is_unglued():
    assert clean_description('Muzyka FilmowaNajwiększe hity.Zapraszamy na koncert') \
        == 'Muzyka Filmowa Największe hity. Zapraszamy na koncert'


def test_text_after_a_colon_is_unglued():
    assert clean_description('Na miejscu:Strefa gastronomiczna') == 'Na miejscu: Strefa gastronomiczna'


def test_unglueing_leaves_initials_and_short_brand_names():
    assert clean_description('Obywatelka K.L. na YouTube') == 'Obywatelka K.L. na YouTube'


def test_block_description_decodes_entities():
    assert clean_description('Rock &amp; roll') == 'Rock & roll'


def listing_html(*numbers):
    return ''.join(f'<div class="event__item"><a class="event__item__title" href="/{n}">E{n}</a></div>'
                   for n in numbers)


class FakeSessionResponse:
    def __init__(self, text):
        self.text = text

    def raise_for_status(self):
        pass


class FakeListingSession:

    def __init__(self, pages):
        self.pages = pages
        self.requested = []

    def get(self, url, params, timeout):
        self.requested.append(params['strona'])
        return FakeSessionResponse(self.pages[min(params['strona'], len(self.pages) - 1)])


def test_listing_stops_when_a_page_adds_nothing():
    session = FakeListingSession([listing_html(1, 2), listing_html(2, 3)])

    urls = [i.url for i in Site(session, sleep=lambda _: None).listing_items()]

    assert [u.rsplit('/', 1)[1] for u in urls] == ['1', '2', '3']
    assert session.requested == [0, 1, 2]


def test_empty_listing_ends_at_once():
    session = FakeListingSession([''])

    assert list(Site(session, sleep=lambda _: None).listing_items()) == []


def test_event_page_failure_gives_nothing():
    class Failing:
        def get(self, url, params, timeout):
            raise requests.ConnectionError('down')

    assert Site(Failing(), sleep=lambda _: None).event_page('https://site.test/1') is None
