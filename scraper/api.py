import logging
import time
from collections.abc import Callable

import requests

log = logging.getLogger(__name__)

PAGE_LIMIT = 100
MAX_ATTEMPTS = 4
BACKOFF_S = 2.0
WRITE_PAUSE_S = 0.1


class ApiError(RuntimeError):
    pass


class CooltureApi:

    def __init__(self, session: requests.Session, base_url: str, token: str | None = None, user_id: str | None = None,
                 sleep: Callable[[float], None] = time.sleep) -> None:
        self._session = session
        self._base_url = base_url.rstrip('/')
        self._sleep = sleep
        if token:
            session.headers['Authorization'] = f'Bearer {token}'
        elif user_id:
            session.headers['X-User-Id'] = user_id
        else:
            raise ValueError('Set SCRAPER_API_TOKEN, or SCRAPER_USER_ID on the local stack')

    def _send(self, method: str, path: str, retry_on: set[int], **kwargs) -> requests.Response:
        for attempt in range(1, MAX_ATTEMPTS + 1):
            response = self._session.request(method, f'{self._base_url}{path}', timeout=40, **kwargs)
            if response.status_code not in retry_on or attempt == MAX_ATTEMPTS:
                return response
            self._sleep(BACKOFF_S * 2 ** (attempt - 1))
        raise AssertionError('unreachable')

    def me(self) -> str:
        # Also creates the bot's user on first use, which a post's author must be.
        response = self._send('GET', '/auth/me', {429, 500, 502, 503, 504})
        if response.status_code != 200:
            raise ApiError(f'GET /auth/me returned {response.status_code}: {response.text[:300]}')
        return response.json()['id']

    def existing_event_urls(self, author_id: str) -> set[str]:
        urls: set[str] = set()
        params: dict = {'authorId': author_id, 'includePast': 'true', 'limit': PAGE_LIMIT}
        while True:
            response = self._send('GET', '/posts', {429, 500, 502, 503, 504}, params=params)
            if response.status_code != 200:
                # Going on without the full list would post events twice.
                raise ApiError(f'GET /posts returned {response.status_code}: {response.text[:300]}')
            body = response.json()
            urls.update(item['eventUrl'] for item in body['items'] if item.get('eventUrl'))
            if not body['page']['hasMore']:
                return urls
            params['cursor'] = body['page']['nextCursor']

    def create_post(self, post: dict) -> str | None:
        # Only a throttled write is retried, because a 5xx may come after the post was saved.
        response = self._send('POST', '/posts', {429}, json=post)
        self._sleep(WRITE_PAUSE_S)
        if response.status_code in (401, 403):
            raise ApiError(f'POST /posts returned {response.status_code}: {response.text[:300]}')
        return None if response.status_code == 201 else f'{response.status_code}: {response.text[:300]}'
