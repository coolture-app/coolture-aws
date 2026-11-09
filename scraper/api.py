import logging
import threading
import time
from collections.abc import Iterable
from contextlib import suppress
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

import requests

from images import Image

log = logging.getLogger(__name__)

LEDGER_DIR = Path(__file__).parent
# seed.py loads posts from several threads, each with its own CooltureApi, into one ledger.
LEDGER_LOCK = threading.Lock()

PAGE_LIMIT = 100
TIMEOUT_S = 40
WRITE_PAUSE_S = 0.1
# PostLimits.MAX_PHOTOS: POST /posts refuses more.
MAX_PHOTOS = 8
# The local API names a new X-User-Id user after it. The API would turn a hyphen into this underscore.
LOCAL_USER_NAME = 'coolture_bot'
# A 200 can still hold the wrong thing, such as CloudFront's HTML when the URL lacks /api. Not JSON is a ValueError too.
WRONG_SHAPE = (KeyError, TypeError, AttributeError, ValueError)


class ApiError(RuntimeError):
    pass


@dataclass(frozen=True)
class Outcome:
    failure: str | None = None
    photos: int = 0


def _answer(response: requests.Response) -> str:
    return f'{response.status_code}: {" ".join(response.text[:300].split())}'


def _stop_if_refused(response: requests.Response, request: str) -> None:
    # A refused identity fails every later request the same way, so the run stops.
    if response.status_code in (401, 403):
        raise ApiError(f'{request} returned {_answer(response)}')


class CooltureApi:

    def __init__(self, session: requests.Session, storage: requests.Session, base_url: str, token: str | None = None,
                 user_id: str | None = None) -> None:
        self._session = session
        # Its own session, so the API's Authorization and X-User-Id headers never reach a presigned storage URL.
        self._storage = storage
        self._base_url = base_url.rstrip('/')
        # GET /posts never returns a deleted post, so this remembers what would otherwise be posted again.
        self._ledger = LEDGER_DIR / f'.imported-{urlsplit(base_url).hostname}.txt'
        if token:
            session.headers['Authorization'] = f'Bearer {token}'
        elif user_id:
            session.headers['X-User-Id'] = user_id
            session.headers['X-User-Name'] = LOCAL_USER_NAME
        else:
            raise ApiError('Set SCRAPER_API_TOKEN, or SCRAPER_USER_ID on the local stack')

    def _me(self) -> str:
        # Also creates the bot's user on first use, which a post's author must be.
        response = self._session.get(f'{self._base_url}/auth/me', timeout=TIMEOUT_S)
        if response.status_code != 200:
            raise ApiError(f'GET /auth/me returned {_answer(response)}')
        try:
            return response.json()['id']
        except WRONG_SHAPE as error:
            raise ApiError(f'GET /auth/me gave an unexpected body: {_answer(response)}') from error

    def existing_event_urls(self) -> set[str]:
        listed = self._listed_urls(self._me())
        # A database with none of our posts is a new one, for example after make reset-db, so the old list doesn't apply.
        remembered = self._read_ledger() if listed else set()
        self._write_ledger(listed | remembered)
        return listed | remembered

    def _read_ledger(self) -> set[str]:
        return set(self._ledger.read_text(encoding='utf-8').splitlines()) if self._ledger.exists() else set()

    def _write_ledger(self, urls: set[str]) -> None:
        self._ledger.write_text(''.join(f'{url}\n' for url in sorted(urls)), encoding='utf-8')

    def _listed_urls(self, author_id: str) -> set[str]:
        urls: set[str] = set()
        params: dict = {'authorId': author_id, 'includePast': 'true', 'limit': PAGE_LIMIT}
        while True:
            response = self._session.get(f'{self._base_url}/posts', params=params, timeout=TIMEOUT_S)
            if response.status_code != 200:
                # Going on without the full list would post events twice.
                raise ApiError(f'GET /posts returned {_answer(response)}')
            try:
                body = response.json()
                urls.update(item['eventUrl'] for item in body['items'] if item.get('eventUrl'))
                if not body['page']['hasMore']:
                    return urls
                params['cursor'] = body['page']['nextCursor']
            except WRONG_SHAPE as error:
                raise ApiError(f'GET /posts gave an unexpected body: {_answer(response)}') from error

    def create_post(self, post: dict, images: Iterable[Image] = ()) -> Outcome:
        """Uploads the images, up to the API's limit, and sends the post with them.

        The images are deleted again when the post is refused, or when something stops the run before it's sent.
        """
        media_ids: list[str] = []
        try:
            for image in images:
                media_id = self._upload(image)
                if media_id:
                    media_ids.append(media_id)
                if len(media_ids) == MAX_PHOTOS:
                    break
        except BaseException:
            self._delete_media(media_ids)
            raise
        try:
            response = self._session.post(f'{self._base_url}/posts', json={**post, 'mediaIds': media_ids} if media_ids else post,
                                          timeout=TIMEOUT_S)
        except requests.Timeout as error:
            # The post may have been saved, like after a 5xx, and the next run's list settles it. Its images stay with it.
            return Outcome(f'timed out: {error}')
        time.sleep(WRITE_PAUSE_S)
        if 400 <= response.status_code < 500:
            self._delete_media(media_ids)
        _stop_if_refused(response, 'POST /posts')
        if response.status_code != 201:
            return Outcome(_answer(response))
        with LEDGER_LOCK, self._ledger.open('a', encoding='utf-8') as ledger:
            ledger.write(f'{post["eventUrl"]}\n')
        return Outcome(photos=len(media_ids))

    def _upload(self, image: Image) -> str | None:
        """Gives the new media's id, or None when this image is left out."""
        fields = {'purpose': 'EVENT_MEDIA', 'mimeType': image.mime_type, 'sizeBytes': len(image.content),
                  'fileName': image.file_name}
        if image.width and image.height:
            fields |= {'width': image.width, 'height': image.height}
        try:
            response = self._session.post(f'{self._base_url}/media/uploads/init', json=fields, timeout=TIMEOUT_S)
            _stop_if_refused(response, 'POST /media/uploads/init')
            response.raise_for_status()
            upload = response.json()
            media_id = upload['mediaId']
        except (requests.RequestException, *WRONG_SHAPE) as error:
            log.warning('Image left out file=%s reason=%s', image.file_name, error)
            return None
        try:
            self._storage.put(upload['uploadUrl'], data=image.content, headers=upload['requiredHeaders'],
                              timeout=TIMEOUT_S).raise_for_status()
            response = self._session.post(f'{self._base_url}/media/uploads/{media_id}/complete', timeout=TIMEOUT_S)
            _stop_if_refused(response, f'POST /media/uploads/{media_id}/complete')
            response.raise_for_status()
        except (requests.RequestException, *WRONG_SHAPE) as error:
            # Otherwise the media would stay PENDING for good.
            self._delete_media([media_id])
            log.warning('Image left out file=%s reason=%s', image.file_name, error)
            return None
        except BaseException:
            self._delete_media([media_id])
            raise
        return media_id

    def _delete_media(self, media_ids: list[str]) -> None:
        for media_id in media_ids:
            # Cleaning up mustn't hide the error that led here, so a failed delete leaves only a PENDING row behind.
            with suppress(requests.RequestException):
                self._session.delete(f'{self._base_url}/media/{media_id}', timeout=TIMEOUT_S)
