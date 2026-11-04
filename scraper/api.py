import logging
import time

import requests

log = logging.getLogger(__name__)

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
        urls: set[str] = set()
        params: dict = {'authorId': self._me(), 'includePast': 'true', 'limit': PAGE_LIMIT}
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
        return None if response.status_code == 201 else _answer(response)
