import pytest
import requests

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

    def _answer(self, method, url, kwargs):
        self.calls.append((method, url, kwargs))
        return self.responses.pop(0)

    def get(self, url, timeout, **kwargs):
        return self._answer('GET', url, kwargs)

    def post(self, url, timeout, **kwargs):
        return self._answer('POST', url, kwargs)


@pytest.fixture(autouse=True)
def no_write_pause(monkeypatch):
    monkeypatch.setattr('api.WRITE_PAUSE_S', 0)


@pytest.fixture(autouse=True)
def ledger(monkeypatch, tmp_path):
    monkeypatch.setattr('api.LEDGER_DIR', tmp_path)
    return tmp_path / '.imported-api.test.txt'


def api(session):
    return CooltureApi(session, 'http://api.test/', user_id='bot')


ME = FakeResponse(200, {'id': 'bot-id'})


def page(urls, next_cursor=None):
    items = [{'eventUrl': url} for url in urls]
    return FakeResponse(200, {'items': items, 'page': {'limit': 100, 'hasMore': next_cursor is not None,
                                                       'nextCursor': next_cursor}})


def test_needs_an_identity():
    with pytest.raises(ApiError):
        CooltureApi(FakeSession([]), 'http://api.test')


def test_token_is_sent_as_a_bearer():
    session = FakeSession([])

    CooltureApi(session, 'http://api.test', token='abc')

    assert session.headers == {'Authorization': 'Bearer abc'}


def test_local_identity_names_the_bot():
    session = FakeSession([])

    CooltureApi(session, 'http://api.test', user_id='bot')

    assert session.headers == {'X-User-Id': 'bot', 'X-User-Name': 'coolture_bot'}


def test_existing_urls_ask_who_the_bot_is_and_walk_until_has_more_is_false():
    session = FakeSession([ME, page(['a', 'b'], 'next'), page([], 'later'), page(['c'])])

    assert api(session).existing_event_urls() == {'a', 'b', 'c'}

    assert session.calls[0][1] == 'http://api.test/auth/me'
    params = [call[2]['params'] for call in session.calls[1:]]
    assert params[0]['authorId'] == 'bot-id'
    assert params[0]['includePast'] == 'true'
    assert len(params) == 3


def test_cursor_of_a_page_is_sent_with_the_next_request():
    responses = [ME, page(['a'], 'cursor-1'), page(['b'])]
    seen = []

    class Recording(FakeSession):
        def get(self, url, timeout, **kwargs):
            if 'params' in kwargs:
                seen.append(kwargs['params'].get('cursor'))
            return super().get(url, timeout, **kwargs)

    api(Recording(responses)).existing_event_urls()

    assert seen == [None, 'cursor-1']


def test_posts_with_no_link_are_ignored():
    session = FakeSession([ME, FakeResponse(200, {'items': [{'title': 'bez linku'}], 'page': {'hasMore': False}})])

    assert api(session).existing_event_urls() == set()


def test_a_rejected_cursor_stops_the_run_instead_of_posting_duplicates():
    session = FakeSession([ME, FakeResponse(400, {'error': 'Invalid cursor'})])

    with pytest.raises(ApiError):
        api(session).existing_event_urls()


def test_create_returns_nothing_on_success():
    session = FakeSession([FakeResponse(201, {'id': 'post'})])

    post = {'title': 't', 'eventUrl': 'a'}

    assert api(session).create_post(post) is None
    assert session.calls[0][2]['json'] == post


def test_a_failure_that_may_have_saved_the_post_is_reported():
    session = FakeSession([FakeResponse(500, {'error': 'x'})])

    assert api(session).create_post({}).startswith('500')


def test_a_rejected_post_is_reported_and_the_run_goes_on():
    session = FakeSession([FakeResponse(400, {'error': 'Title is required'})])

    assert 'Title is required' in api(session).create_post({})


@pytest.mark.parametrize('status', [401, 403])
def test_a_rejected_token_stops_the_run(status):
    session = FakeSession([FakeResponse(status, {'error': 'x'})])

    with pytest.raises(ApiError):
        api(session).create_post({})


def test_a_post_that_timed_out_counts_as_rejected():
    class Slow(FakeSession):
        def post(self, url, timeout, **kwargs):
            raise requests.ReadTimeout('slow')

    assert 'timed out' in api(Slow([])).create_post({})


def test_a_listed_post_is_remembered(ledger):
    assert api(FakeSession([ME, page(['a'])])).existing_event_urls() == {'a'}

    assert ledger.read_text(encoding='utf-8').splitlines() == ['a']


def test_a_deleted_post_is_still_known(ledger):
    ledger.write_text('a\nb\n', encoding='utf-8')

    assert api(FakeSession([ME, page(['a'])])).existing_event_urls() == {'a', 'b'}
    assert ledger.read_text(encoding='utf-8').splitlines() == ['a', 'b']


def test_a_database_with_none_of_our_posts_starts_over(ledger):
    ledger.write_text('a\nb\n', encoding='utf-8')

    assert api(FakeSession([ME, page([])])).existing_event_urls() == set()
    assert ledger.read_text(encoding='utf-8') == ''


def test_a_created_post_is_remembered_at_once(ledger):
    ledger.write_text('a\n', encoding='utf-8')

    api(FakeSession([FakeResponse(201, {})])).create_post({'eventUrl': 'b'})

    assert ledger.read_text(encoding='utf-8').splitlines() == ['a', 'b']


def test_a_post_that_failed_is_not_remembered(ledger):
    api(FakeSession([FakeResponse(500, {})])).create_post({'eventUrl': 'b'})

    assert not ledger.exists()


def test_a_failed_list_leaves_what_is_remembered(ledger):
    ledger.write_text('a\n', encoding='utf-8')

    with pytest.raises(ApiError):
        api(FakeSession([ME, FakeResponse(500, {})])).existing_event_urls()

    assert ledger.read_text(encoding='utf-8') == 'a\n'


def test_each_environment_has_its_own_file_named_by_host(tmp_path):
    local = CooltureApi(FakeSession([ME, page(['a'])]), 'http://localhost:3000', user_id='bot')
    aws = CooltureApi(FakeSession([ME, page(['b'])]), 'https://dev.example.test/api', token='abc')

    assert local.existing_event_urls() == {'a'}
    assert aws.existing_event_urls() == {'b'}

    assert (tmp_path / '.imported-localhost.txt').read_text(encoding='utf-8') == 'a\n'
    assert (tmp_path / '.imported-dev.example.test.txt').read_text(encoding='utf-8') == 'b\n'


class NotJson(FakeResponse):
    def json(self):
        raise requests.exceptions.JSONDecodeError('Expecting value', '<html>', 0)


@pytest.mark.parametrize('response', [
    FakeResponse(200, {'name': 'no id'}),
    FakeResponse(200, ['a list']),
    NotJson(200, '<html>CloudFront</html>'),
])
def test_a_200_with_the_wrong_body_is_an_api_error_on_me(response):
    with pytest.raises(ApiError):
        api(FakeSession([response])).existing_event_urls()


@pytest.mark.parametrize('response', [
    FakeResponse(200, {'page': {'hasMore': False}}),
    FakeResponse(200, {'items': [], 'page': {'hasMore': True}}),
    FakeResponse(200, {'items': ['not an object'], 'page': {'hasMore': False}}),
    FakeResponse(200, ['a list']),
    NotJson(200, '<html>CloudFront</html>'),
])
def test_a_200_with_the_wrong_body_is_an_api_error_on_the_list(response):
    with pytest.raises(ApiError):
        api(FakeSession([ME, response])).existing_event_urls()
