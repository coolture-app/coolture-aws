import json
from datetime import date, datetime, time, timezone

from pathlib import Path

import pytest
import requests

from images import Image
from posts import cut_description
from trojmiasto import EventPage, ListingItem, Site, clean_description, merge, parse_event_page, parse_listing

FIXTURES = Path(__file__).parent / 'fixtures'


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


@pytest.mark.parametrize('listed_city, city', [
    ('Gdańsk,', 'Gdańsk'),
    ('Gdańsk Sopot,', 'Gdańsk'),
    ('Gdańsk Gdynia,', 'Gdańsk'),
    ('Gdańsk Oliwa,', 'Gdańsk'),
    ('Gdańsk-Czołpino-Gdańsk,', 'Gdańsk'),
    ('Sopot Brodwino,', 'Sopot'),
    ('Gdynia,', 'Gdynia'),
    ('Pruszcz Gdański,', 'Pruszcz Gdański'),
    ('Wejherowo,', 'Wejherowo'),
    ('Rumia,', 'Rumia'),
    ('Bąkowo,', 'Bąkowo'),
    ('Reda,', 'Reda'),
    ('Lokalizacja,', 'Lokalizacja'),
])
def test_listing_keeps_one_real_city(listed_city, city):
    assert parse_listing(listing_item(city=listed_city))[0].city == city


def test_listing_keeps_an_item_with_no_city():
    assert parse_listing(listing_item(city=''))[0].city is None


def test_listing_reads_both_ends_of_an_hour_range():
    item = parse_listing(listing_item(hour='Godz. 14:00 - 18:00'))[0]

    assert (item.hour, item.end_hour) == (time(14, 0), time(18, 0))


def test_a_single_hour_has_no_end():
    assert parse_listing(listing_item())[0].end_hour is None


def test_a_list_of_hours_is_not_a_range():
    item = parse_listing(listing_item(hour='Godz. 17:00, 20:00'))[0]

    assert (item.hour, item.end_hour) == (time(17, 0), None)


def test_a_range_ending_at_an_impossible_hour_has_no_end():
    assert parse_listing(listing_item(hour='Godz. 14:00 - 25:00'))[0].end_hour is None


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

    assert parse_event_page(event_page(body)).description == 'Pierwszy akapit\n\nDrugi akapit.'


def test_a_short_page_text_does_not_replace_a_longer_block():
    body = '<div class="text-contents"><a href="/x">Zobacz</a></div>'

    assert parse_event_page(event_page(body, description='Długi opis z bloku.')).description == 'Długi opis z bloku.'


def test_description_keeps_lines_and_inline_punctuation():
    body = '<div class="text-contents">Jeden<br/><br/><strong>Dwa</strong>, trzy<ul><li>Cztery</li></ul></div>'

    assert parse_event_page(event_page(body)).description == 'Jeden\n\nDwa, trzy\n\n• Cztery'


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


def listed(**overrides):
    fields = dict(url='https://www.trojmiasto.pl/imprezy/x-imp1.html', title='Koncert', category='koncerty',
                  city='Gdańsk', venue=None, start_date=None, hour=time(19, 0))
    return ListingItem(**{**fields, **overrides})


def read_page(**overrides):
    fields = dict(start_date=date(2026, 11, 5), end=None, online=False, description='Opis wydarzenia.',
                  venue='Klub', street='Portowa', building_number='9', postal_code='81-350', hour=None)
    return EventPage(**{**fields, **overrides})


def test_merge_takes_the_address_from_the_page_and_the_rest_from_the_listing():
    event = merge(listed(), read_page())

    assert (event.url, event.title, event.category, event.city) \
        == ('https://www.trojmiasto.pl/imprezy/x-imp1.html', 'Koncert', 'koncerty', 'Gdańsk')
    assert (event.venue, event.street, event.building_number, event.postal_code) == ('Klub', 'Portowa', '9', '81-350')
    assert event.description == 'Opis wydarzenia.'
    assert event.online is False


def test_start_is_warsaw_time():
    assert merge(listed(), read_page()).start.astimezone(timezone.utc).hour == 18


def test_hour_falls_back_to_the_page_then_to_noon():
    assert merge(listed(hour=None), read_page(hour=time(20, 15))).start.hour == 20
    assert merge(listed(hour=None), read_page()).start.hour == 12


def test_listing_date_wins_because_a_page_can_cover_several_showings():
    assert merge(listed(start_date=date(2026, 11, 20)), read_page()).start.day == 20


def test_date_from_the_listing_alone():
    assert merge(listed(start_date=date(2026, 11, 20)), read_page(start_date=None)).start.day == 20


def test_no_date_anywhere_gives_no_event():
    assert merge(listed(), read_page(start_date=None)) is None


def test_theatre_event_takes_its_venue_from_the_listing():
    theatre = read_page(start_date=None, venue=None, street=None, building_number=None, postal_code=None)

    assert merge(listed(venue='Teatr', start_date=date(2026, 11, 20)), theatre).venue == 'Teatr'


def test_page_venue_wins_over_the_listing():
    assert merge(listed(venue='Teatr'), read_page()).venue == 'Klub'


def test_multi_day_event_runs_to_the_end_of_its_last_day():
    end = datetime.fromisoformat('2026-11-08T00:00:00+01:00')

    assert merge(listed(), read_page(end=end)).end.astimezone(timezone.utc) \
        == datetime(2026, 11, 8, 22, 59, tzinfo=timezone.utc)


def test_end_before_the_start_is_dropped():
    end = datetime.fromisoformat('2026-11-05T10:00:00+01:00')

    assert merge(listed(), read_page(end=end)).end is None


def test_end_hour_of_the_listing_is_used_when_the_page_gives_no_end():
    event = merge(listed(hour=time(14, 0), end_hour=time(18, 0)), read_page())

    assert (event.end.day, event.end.hour) == (5, 18)


def test_end_hour_at_or_before_the_start_is_on_the_next_day():
    assert merge(listed(hour=time(22, 0), end_hour=time(2, 0)), read_page()).end.day == 6
    assert merge(listed(hour=time(22, 0), end_hour=time(22, 0)), read_page()).end.day == 6


def test_end_of_the_page_wins_over_the_end_hour_of_the_listing():
    end = datetime.fromisoformat('2026-11-05T21:00:00+01:00')

    assert merge(listed(hour=time(14, 0), end_hour=time(18, 0)), read_page(end=end)).end.hour == 21


def test_empty_description_is_built_from_the_facts():
    assert merge(listed(), read_page(description='')).description == 'Koncerty: Koncert, Klub, Gdańsk.'


def test_page_failure_gives_no_event():
    class Failing:
        def get(self, url, params, timeout):
            raise requests.ConnectionError('down')

    assert Site(Failing(), sleep=lambda _: None).event(listed()) is None


def test_page_with_nothing_on_it_gives_no_event():
    session = FakeListingSession([])
    session.get = lambda url, params, timeout: FakeSessionResponse('<html></html>')

    assert Site(session, sleep=lambda _: None).event(listed()) is None


def test_a_read_page_gives_the_merged_event():
    session = FakeListingSession([])
    session.get = lambda url, params, timeout: FakeSessionResponse(event_page())

    event = Site(session, sleep=lambda _: None).event(listed())

    assert (event.street, event.start.day, event.start.hour) == ('Portowa', 5, 19)


def fixture(name):
    return parse_event_page((FIXTURES / name).read_text(encoding='utf-8'))


def test_a_concert_reads_as_paragraphs_without_link_labels_or_the_start_and_address():
    assert fixture('concert.html').description == '\n'.join([
        'Dla kogo: bez ograniczeń wiekowych',
        'Bilety: 30 zł, do kupienia online',
        '',
        'Kto zagra?',
        '',
        '19:00 | Mewy',
        '',
        'Zespół z Gdyni, który gra od dziesięciu lat. Łączy gitary z elektroniką i śpiewa po polsku.',
        'Skład: Anna Nowak (wokal), Piotr Wiśniewski (gitara).',
        '',
        '20:30 | Fale',
        '',
        'Grupa założona w 1990 roku, która po latach wraca na scenę z nowym materiałem. Więcej o zespole na fale.example.',
    ])


def test_a_festival_keeps_its_schedule_lists_and_headings_and_joins_wrapped_lines():
    assert fixture('festival.html').description == '\n'.join([
        'Pięć wieczorów muzyki w Gdyni, gdzie wspólnie będziemy świętować jubileusz festiwalu.',
        '',
        'Harmonogram',
        '',
        'Sobota, 7 listopada 2026',
        '',
        '• 17:00 - Kwartet Bałtycki (Scena Nowa)',
        '• 20:00 - Orkiestra Portowa (Scena Duża) - wyprzedane',
        '',
        'Niedziela, 8 listopada 2026',
        '',
        '• 18:00 - Chór Mewa (Sala Kameralna)',
        '',
        'DLACZEGO MUSISZ TAM BYĆ?',
        '',
        'Bo takiego programu nie było od lat. Każdy wieczór kończy się wspólnym śpiewaniem.',
        '',
        'KWARTET BAŁTYCKI',
        '',
        'Czterech muzyków, którzy grają muzykę dawną na nowych instrumentach.',
        '',
        'Program:',
        '',
        '• nr 1 g-moll',
        '• nr 2 D-dur',
    ])


def test_a_long_description_is_cut_at_the_end_of_a_line():
    description = fixture('festival.html').description
    cut = cut_description(description * 4)

    assert len(cut) <= 1000
    assert (description * 4).startswith(cut)
    assert not cut.endswith('…')


def test_polish_letters_survive_the_cleaning_and_the_cut():
    # Ł is the bytes C5 81, and a reader in Windows' cp1250 shows them as "Ĺ\udc81": the text itself is fine.
    description = cut_description(fixture('concert.html').description)

    assert 'Łączy' in description
    assert not [char for char in description if 0xD800 <= ord(char) <= 0xDFFF]


def test_a_link_holding_a_name_or_an_address_stays():
    body = '<div class="text-contents">Napisz na <a href="mailto:a@b.pl">a@b.pl</a> albo zobacz <a href="/x">Klub Mewa</a>.</div>'

    assert parse_event_page(event_page(body)).description == 'Napisz na a@b.pl albo zobacz Klub Mewa.'


@pytest.mark.parametrize('line', ['Termin: 26 listopada 2026, godz. 11:30', 'Miejsce: Klub Mewa, Gdańsk'])
def test_other_lines_that_repeat_the_start_or_the_address_go_too(line):
    body = f'<div class="text-contents">{line}<br/>Wstęp: wolny<br/><br/>Opis spotkania.</div>'

    assert parse_event_page(event_page(body)).description == 'Wstęp: wolny\n\nOpis spotkania.'


def test_a_where_line_later_in_the_text_stays():
    body = '<div class="text-contents">Opis spotkania.<br/><br/>Gdzie: w sali na piętrze.</div>'

    assert parse_event_page(event_page(body)).description == 'Opis spotkania.\n\nGdzie: w sali na piętrze.'


def test_a_line_that_starts_a_sentence_is_not_joined_to_the_one_before():
    body = '<div class="text-contents">Kierownik: Jan Kowalski<br/>Kontakt przez stronę</div>'

    assert parse_event_page(event_page(body)).description == 'Kierownik: Jan Kowalski\nKontakt przez stronę'


def test_a_gallery_page_gives_the_cover_then_each_full_size_photo_once():
    assert fixture('gallery.html').image_urls == (
        'https://s-trojmiasto.pl/zdj/c/n/3/9002/1200x0/9002001.webp?r=2026110901',
        'https://s-trojmiasto.pl/zdj/c/n/10/9002/1920x0/9002002.webp',
        'https://s-trojmiasto.pl/zdj/c/n/10/9002/1920x0/9002003.webp',
    )


def test_a_page_with_no_gallery_gives_only_its_cover():
    assert fixture('concert.html').image_urls == ('https://s-trojmiasto.pl/zdj/c/n/3/9001/1200x0/9001001.webp?r=2026110901',)


PHOTO = 'https://s-trojmiasto.pl/zdj/c/n/3/1/1200x0/4192984.webp?r=1'


@pytest.mark.parametrize('body, expected', [
    (f'<meta property="og:image" content="{PHOTO}">', (PHOTO,)),
    ('<meta property="og:image" content="https://s-trojmiasto.pl/_img/logo.png">', ()),
    ('', ()),
])
def test_the_cover_is_an_og_image_from_the_photo_folder(body, expected):
    assert parse_event_page(event_page(body)).image_urls == expected


def test_a_gallery_with_no_cover_still_gives_its_photos():
    body = ('<div class="text-contents">Opis.<div class="tinyGallery"><a class="tinyGallery__item" '
            'data-href="/zdj/c/n/10/1/1920x0/7.webp" href="#"><img src="/zdj/c/n/10/1/0x500/7__kr.webp"/></a></div></div>')

    assert parse_event_page(event_page(body)).image_urls == ('https://www.trojmiasto.pl/zdj/c/n/10/1/1920x0/7.webp',)


def test_merged_event_keeps_the_images():
    assert merge(listed(), read_page(image_urls=(PHOTO,))).image_urls == (PHOTO,)


class FakeImageSession:
    def __init__(self, status=200, content=b'img'):
        self.status = status
        self.content = content

    def get(self, url, params, timeout):
        response = requests.Response()
        response.status_code, response._content = self.status, self.content
        response.headers['Content-Type'] = 'IMAGE/WEBP; charset=binary'
        return response


def test_image_is_downloaded_with_its_type_and_name():
    image = Site(FakeImageSession(), sleep=lambda _: None).image(PHOTO)

    assert image == Image(b'img', 'image/webp', '4192984.webp')


def test_image_is_downloaded_with_its_size():
    webp = b'RIFF\0\0\0\0WEBPVP8X\x0a\0\0\0\0\0\0\0' + (1199).to_bytes(3, 'little') + (799).to_bytes(3, 'little')

    image = Site(FakeImageSession(content=webp), sleep=lambda _: None).image(PHOTO)

    assert (image.width, image.height) == (1200, 800)


def test_missing_image_gives_nothing():
    assert Site(FakeImageSession(404), sleep=lambda _: None).image(PHOTO) is None



def test_every_site_request_waits_a_random_pause_of_at_least_a_second():
    pauses = []
    session = FakeListingSession([listing_html(1, 2), listing_html(2, 3)])

    list(Site(session, sleep=pauses.append).listing_items())

    assert len(pauses) == 3
    assert all(1.0 <= pause <= 3.0 for pause in pauses)
    assert len(set(pauses)) > 1
