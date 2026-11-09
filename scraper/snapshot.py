"""A saved set of scraped events and their photos, so a local stack gets realistic data without scraping the site again.

`scrape.py --snapshot` writes it and `seed.py` loads it. It lives in a gitignored folder, because scraped text and photos
stay out of the public repository.
"""
import hashlib
import json
import math
from collections import defaultdict
from collections.abc import Callable, Iterable, Iterator
from dataclasses import dataclass
from datetime import date, datetime, timezone
from itertools import islice, zip_longest
from pathlib import Path, PurePosixPath

from api import MAX_PHOTOS, Outcome
from images import Image
from trojmiasto import ListingItem

DEFAULT_DIR = Path(__file__).parent / 'snapshot'
DEFAULT_SIZE = 120
POSTS_FILE = 'posts.jsonl'
IMAGES_DIR = 'images'
# Written last, so an interrupted scrape isn't taken for a finished one.
COMPLETE_FILE = 'complete'
NO_CATEGORY = '-'
# Between proportional (1) and equal (0): big categories get more, small ones still get enough to matter.
ALLOCATION_POWER = 0.5
SUFFIXES = {'image/webp': '.webp', 'image/jpeg': '.jpg', 'image/png': '.png', 'image/gif': '.gif'}


def category_of(item: ListingItem) -> str:
    """The site's top category, which is also a post's first tag."""
    return (item.category or NO_CATEGORY).split(', ')[0]


def allocate(supply: dict[str, int], total: int, power: float = ALLOCATION_POWER) -> dict[str, int]:
    """Splits `total` between categories in proportion to supply ** power, never giving one more than it has.

    This is power allocation (Bankier, 1988). Rounding goes by largest remainder, so the quotas add up to the total.
    """
    quotas = dict.fromkeys(supply, 0)
    open_ = {category for category, count in supply.items() if count > 0}
    left = min(total, sum(supply.values()))
    shares: dict[str, float] = {}
    while open_:
        weights = {category: supply[category] ** power for category in open_}
        shares = {category: left * weight / sum(weights.values()) for category, weight in weights.items()}
        # A category whose share is more than it has takes all it has, and the rest is shared again.
        full = {category for category in open_ if shares[category] >= supply[category]}
        if not full:
            break
        for category in full:
            quotas[category] = supply[category]
            left -= supply[category]
        open_ -= full
    if open_:
        floors = {category: math.floor(shares[category]) for category in open_}
        by_remainder = sorted(open_, key=lambda category: (floors[category] - shares[category], category))
        for category in by_remainder[:left - sum(floors.values())]:
            floors[category] += 1
        quotas.update(floors)
    return quotas


def spread_over_dates(items: Iterable[ListingItem]) -> list[ListingItem]:
    """Orders items so that any first few cover as many days as they can: each day's first event, then each day's second."""
    by_day: dict[date, list[ListingItem]] = defaultdict(list)
    for item in items:
        by_day[item.start_date or date.max].append(item)
    rounds = zip_longest(*(by_day[day] for day in sorted(by_day)))
    return [item for round_ in rounds for item in round_ if item is not None]


@dataclass(frozen=True)
class Entry:
    scraped_at: datetime
    post: dict
    images: tuple[dict, ...]


def read(directory: Path) -> list[Entry]:
    posts = directory / POSTS_FILE
    if not posts.exists():
        return []
    entries = []
    for line in posts.read_text(encoding='utf-8').splitlines():
        if line.strip():
            saved = json.loads(line)
            entries.append(Entry(datetime.fromisoformat(saved['scrapedAt']), saved['post'], tuple(saved['images'])))
    return entries


def is_complete(directory: Path) -> bool:
    return (directory / COMPLETE_FILE).exists()


def load_images(directory: Path, entry: Entry) -> Iterator[Image]:
    for saved in entry.images:
        content = (directory / IMAGES_DIR / saved['file']).read_bytes()
        yield Image(content, saved['mimeType'], saved['fileName'], saved.get('width'), saved.get('height'))


def _suffix(image: Image) -> str:
    return SUFFIXES.get(image.mime_type) or PurePosixPath(image.file_name).suffix or '.bin'


class Snapshot:
    """Takes the place of the API while scraping, and saves each post with its photos instead of sending it."""

    def __init__(self, directory: Path, clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc)) -> None:
        self.directory = directory
        self._clock = clock
        (directory / IMAGES_DIR).mkdir(parents=True, exist_ok=True)
        # A run that adds to a snapshot leaves it unfinished until it ends.
        (directory / COMPLETE_FILE).unlink(missing_ok=True)

    def existing_event_urls(self) -> set[str]:
        return {entry.post['eventUrl'] for entry in read(self.directory)}

    def create_post(self, post: dict, images: Iterable[Image] = ()) -> Outcome:
        # Named after the event, so a run that stopped halfway writes the same files again rather than new ones.
        key = hashlib.sha1(post['eventUrl'].encode()).hexdigest()[:12]
        saved = []
        for number, image in enumerate(islice(images, MAX_PHOTOS)):
            name = f'{key}-{number}{_suffix(image)}'
            (self.directory / IMAGES_DIR / name).write_bytes(image.content)
            saved.append({'file': name, 'fileName': image.file_name, 'mimeType': image.mime_type,
                          'width': image.width, 'height': image.height})
        line = json.dumps({'scrapedAt': self._clock().isoformat(), 'post': post, 'images': saved}, ensure_ascii=False)
        # The line comes after its photos, so every line in the file has all of them.
        with (self.directory / POSTS_FILE).open('a', encoding='utf-8', newline='\n') as posts:
            posts.write(line + '\n')
        return Outcome(photos=len(saved))

    def mark_complete(self) -> None:
        (self.directory / COMPLETE_FILE).write_text('', encoding='utf-8')
