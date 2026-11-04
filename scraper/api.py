import logging
import time
from pathlib import Path
from urllib.parse import urlsplit

import requests

log = logging.getLogger(__name__)

LEDGER_DIR = Path(__file__).parent

PAGE_LIMIT = 100
TIMEOUT_S = 40
WRITE_PAUSE_S = 0.1
# Without it the local user takes the dev default, which the seed already owns. The API would turn a hyphen into this underscore.
LOCAL_USER_NAME = 'coolture_bot'
# A 200 can still hold the wrong thing, such as CloudFront's HTML when the URL lacks /api. Not JSON is a ValueError too.
WRONG_SHAPE = (KeyError, TypeError, AttributeError, ValueError)


class ApiError(RuntimeError):
    pass


def _answer(response: requests.Response) -> str:
    return f'{response.status_code}: {" ".join(response.text[:300].split())}'


class CooltureApi:

    def __init__(self, session: requests.Session, base_url: str, token: str | None = None, user_id: str | None = None) -> None:
        self._session = session
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

    def create_post(self, post: dict) -> str | None:
        try:
            response = self._session.post(f'{self._base_url}/posts', json=post, timeout=TIMEOUT_S)
        except requests.Timeout as error:
            # The post may have been saved, like after a 5xx, and the next run's list settles it.
            return f'timed out: {error}'
        time.sleep(WRITE_PAUSE_S)
        if response.status_code in (401, 403):
            raise ApiError(f'POST /posts returned {_answer(response)}')
        if response.status_code != 201:
            return _answer(response)
        with self._ledger.open('a', encoding='utf-8') as ledger:
            ledger.write(f'{post["eventUrl"]}\n')
        return None
