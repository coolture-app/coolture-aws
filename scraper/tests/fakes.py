"""Doubles and factories the scraper's specs share."""
from datetime import date, datetime, time, timezone

from geocoder import Place
from images import Image
from trojmiasto import EventPage, ListingItem, merge

NOW = datetime(2026, 11, 2, 12, 0, tzinfo=timezone.utc)
PLACE = Place(54.35, 18.65, None, exact=True)


def listed(number=1, **overrides):
    fields = dict(url=f'https://site.test/{number}', title=f'Wydarzenie {number}', category='koncerty', city='Gdańsk',
                  venue='Klub', start_date=None, hour=time(19, 0))
    return ListingItem(**{**fields, **overrides})


def read_page(**overrides):
    fields = dict(start_date=date(2026, 11, 5), end=None, online=False, description='Opis wydarzenia.', venue='Klub',
                  street='Portowa', building_number='9', postal_code='81-350', hour=None)
    return EventPage(**{**fields, **overrides})


class FakeSite:
    def __init__(self, items, pages=None):
        self.items = items
        self.pages = pages or {}
        self.opened = []
        self.downloaded = []

    def listing_items(self):
        return iter(self.items)

    def event(self, item):
        self.opened.append(item.url)
        return merge(item, self.pages.get(item.url, read_page()))

    def image(self, url):
        self.downloaded.append(url)
        return None if 'broken' in url else Image(url.encode(), 'image/webp', url.rsplit('/', 1)[-1], 4, 3)


class FakeGeocoder:
    def __init__(self, place=PLACE):
        self.place = place
        self.calls = 0
        self.venues = []

    def locate(self, event):
        self.calls += 1
        self.venues.append(event.venue)
        return self.place
