import pytest

from api import ApiError, CooltureApi


class FakeResponse:
    def __init__(self, status, body=None):
        self.status_code = status
        self._body = body
        self.text = str(body)

    def json(self):
        return self._body


class FakeSession:
    def __init__(self, responses):
        self.headers = {}
        self.responses = list(responses)
        self.calls = []

    def request(self, method, url, timeout, **kwargs):
        self.calls.append((method, url, kwargs))
        return self.responses.pop(0)


def api(session):
    return CooltureApi(session, 'http://api.test/', user_id='bot', sleep=lambda _: None)


def page(urls, next_cursor=None):
    items = [{'eventUrl': url} for url in urls]
    return FakeResponse(200, {'items': items, 'page': {'limit': 100, 'hasMore': next_cursor is not None,
                                                       'nextCursor': next_cursor}})


def test_needs_an_identity():
    with pytest.raises(ValueError):
        CooltureApi(FakeSession([]), 'http://api.test')


def test_token_is_sent_as_a_bearer():
    session = FakeSession([])

    CooltureApi(session, 'http://api.test', token='abc')

    assert session.headers['Authorization'] == 'Bearer abc'


def test_me_returns_the_id():
    session = FakeSession([FakeResponse(200, {'id': 'bot-id'})])

    assert api(session).me() == 'bot-id'
    assert session.calls[0][1] == 'http://api.test/auth/me'


def test_existing_urls_walk_until_has_more_is_false():
    session = FakeSession([page(['a', 'b'], 'next'), page([], 'later'), page(['c'])])

    assert api(session).existing_event_urls('bot-id') == {'a', 'b', 'c'}

    params = [call[2]['params'] for call in session.calls]
    assert params[0]['authorId'] == 'bot-id'
    assert params[0]['includePast'] == 'true'
    assert len(session.calls) == 3


def test_cursor_of_a_page_is_sent_with_the_next_request():
    responses = [page(['a'], 'cursor-1'), page(['b'])]
    seen = []

    class Recording(FakeSession):
        def request(self, method, url, timeout, **kwargs):
            seen.append(kwargs['params'].get('cursor'))
            return super().request(method, url, timeout, **kwargs)

    api(Recording(responses)).existing_event_urls('bot-id')

    assert seen == [None, 'cursor-1']


def test_posts_with_no_link_are_ignored():
    session = FakeSession([FakeResponse(200, {'items': [{'title': 'bez linku'}], 'page': {'hasMore': False}})])

    assert api(session).existing_event_urls('bot-id') == set()


def test_a_rejected_cursor_stops_the_run_instead_of_posting_duplicates():
    session = FakeSession([FakeResponse(400, {'error': 'Invalid cursor'})])

    with pytest.raises(ApiError):
        api(session).existing_event_urls('bot-id')


def test_reads_retry_a_throttle_and_a_failure():
    session = FakeSession([FakeResponse(429), FakeResponse(503), page(['a'])])

    assert api(session).existing_event_urls('bot-id') == {'a'}


def test_create_returns_nothing_on_success():
    session = FakeSession([FakeResponse(201, {'id': 'post'})])

    assert api(session).create_post({'title': 't'}) is None
    assert session.calls[0][2]['json'] == {'title': 't'}


def test_create_retries_a_throttle():
    session = FakeSession([FakeResponse(429), FakeResponse(201, {})])

    assert api(session).create_post({}) is None


def test_create_does_not_retry_a_failure_that_may_have_saved_the_post():
    session = FakeSession([FakeResponse(500, {'error': 'x'})])

    assert api(session).create_post({}).startswith('500')
    assert len(session.calls) == 1


def test_a_rejected_post_is_reported_and_the_run_goes_on():
    session = FakeSession([FakeResponse(400, {'error': 'Title is required'})])

    assert 'Title is required' in api(session).create_post({})


@pytest.mark.parametrize('status', [401, 403])
def test_a_rejected_token_stops_the_run(status):
    session = FakeSession([FakeResponse(status, {'error': 'x'})])

    with pytest.raises(ApiError):
        api(session).create_post({})
