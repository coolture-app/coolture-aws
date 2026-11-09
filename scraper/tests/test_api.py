from contextlib import suppress

import pytest
import requests

from api import MAX_PHOTOS, ApiError, CooltureApi, Outcome
from images import Image


class FakeResponse:
    def __init__(self, status, body=None):
        self.status_code = status
        self._body = body
        self.text = str(body)

    def json(self):
        return self._body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(self.status_code)


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

    def put(self, url, timeout, **kwargs):
        return self._answer('PUT', url, kwargs)

    def delete(self, url, timeout, **kwargs):
        return self._answer('DELETE', url, kwargs)


@pytest.fixture(autouse=True)
def no_write_pause(monkeypatch):
    monkeypatch.setattr('api.WRITE_PAUSE_S', 0)


@pytest.fixture(autouse=True)
def ledger(monkeypatch, tmp_path):
    monkeypatch.setattr('api.LEDGER_DIR', tmp_path)
    return tmp_path / '.imported-api.test.txt'


def api(session):
    return CooltureApi(session, FakeSession([]), 'http://api.test/', user_id='bot')


ME = FakeResponse(200, {'id': 'bot-id'})


def page(urls, next_cursor=None):
    items = [{'eventUrl': url} for url in urls]
    return FakeResponse(200, {'items': items, 'page': {'limit': 100, 'hasMore': next_cursor is not None,
                                                       'nextCursor': next_cursor}})


def test_needs_an_identity():
    with pytest.raises(ApiError):
        CooltureApi(FakeSession([]), FakeSession([]), 'http://api.test')


def test_token_is_sent_as_a_bearer():
    session = FakeSession([])

    CooltureApi(session, FakeSession([]), 'http://api.test', token='abc')

    assert session.headers == {'Authorization': 'Bearer abc'}


def test_local_identity_names_the_bot():
    session = FakeSession([])

    CooltureApi(session, FakeSession([]), 'http://api.test', user_id='bot')

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


def test_create_reports_no_failure_on_success():
    session = FakeSession([FakeResponse(201, {'id': 'post'})])

    post = {'title': 't', 'eventUrl': 'a'}

    assert api(session).create_post(post) == Outcome()
    assert session.calls[0][2]['json'] == post


def test_a_failure_that_may_have_saved_the_post_is_reported():
    session = FakeSession([FakeResponse(500, {'error': 'x'})])

    assert api(session).create_post({}).failure.startswith('500')


def test_a_rejected_post_is_reported_and_the_run_goes_on():
    session = FakeSession([FakeResponse(400, {'error': 'Title is required'})])

    assert 'Title is required' in api(session).create_post({}).failure


@pytest.mark.parametrize('status', [401, 403])
def test_a_rejected_token_stops_the_run(status):
    session = FakeSession([FakeResponse(status, {'error': 'x'})])

    with pytest.raises(ApiError):
        api(session).create_post({})


def test_a_post_that_timed_out_counts_as_rejected():
    class Slow(FakeSession):
        def post(self, url, timeout, **kwargs):
            raise requests.ReadTimeout('slow')

    assert 'timed out' in api(Slow([])).create_post({}).failure


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
    local = CooltureApi(FakeSession([ME, page(['a'])]), FakeSession([]), 'http://localhost:3000', user_id='bot')
    aws = CooltureApi(FakeSession([ME, page(['b'])]), FakeSession([]), 'https://dev.example.test/api', token='abc')

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



IMAGE = Image(b'img', 'image/webp', '1.webp', 1200, 800)
CREATED = FakeResponse(201, {'id': 'post'})
OK = FakeResponse(200)
GONE = FakeResponse(204)


def init(media_id='m1'):
    return FakeResponse(201, {'mediaId': media_id, 'uploadUrl': f'http://storage.test/{media_id}?sig',
                              'requiredHeaders': {'Content-Type': 'image/webp'}})


def uploading(api_responses, storage_responses=()):
    session, storage = FakeSession(api_responses), FakeSession(storage_responses)
    return CooltureApi(session, storage, 'http://api.test', token='t'), session, storage


def requests_made(session):
    return [(method, url) for method, url, _ in session.calls]


def test_images_are_uploaded_and_sent_with_the_post_in_order():
    client, session, storage = uploading([init('m1'), OK, init('m2'), OK, CREATED], [OK, OK])

    outcome = client.create_post({'eventUrl': 'a'}, [IMAGE, Image(b'png', 'image/png', '2.png')])

    assert outcome == Outcome(photos=2)
    assert session.calls[0][2]['json'] == {'purpose': 'EVENT_MEDIA', 'mimeType': 'image/webp', 'sizeBytes': 3,
                                           'fileName': '1.webp', 'width': 1200, 'height': 800}
    # An image whose size can't be read is sent without one.
    assert session.calls[2][2]['json'] == {'purpose': 'EVENT_MEDIA', 'mimeType': 'image/png', 'sizeBytes': 3,
                                           'fileName': '2.png'}
    assert storage.calls[0] == ('PUT', 'http://storage.test/m1?sig',
                                {'data': b'img', 'headers': {'Content-Type': 'image/webp'}})
    assert session.calls[1][1] == 'http://api.test/media/uploads/m1/complete'
    assert session.calls[4][2]['json'] == {'eventUrl': 'a', 'mediaIds': ['m1', 'm2']}


def test_a_post_with_no_images_is_sent_without_media():
    client, session, _ = uploading([CREATED])

    assert client.create_post({'eventUrl': 'a'}, []) == Outcome()
    assert session.calls[0][2]['json'] == {'eventUrl': 'a'}


@pytest.mark.parametrize('response', [
    FakeResponse(400),
    FakeResponse(201, {'no': 'id'}),
    NotJson(201, '<html>'),
])
def test_an_image_the_api_will_not_take_is_left_out(response):
    client, session, _ = uploading([response, CREATED])

    assert client.create_post({'eventUrl': 'a'}, [IMAGE]) == Outcome()
    assert session.calls[-1][2]['json'] == {'eventUrl': 'a'}


@pytest.mark.parametrize('api_responses, storage_responses', [
    ([init()], [FakeResponse(403)]),
    ([FakeResponse(201, {'mediaId': 'm1'})], []),
    ([init(), FakeResponse(400)], [OK]),
    ([init(), FakeResponse(500)], [OK]),
])
def test_a_failed_upload_deletes_its_media_and_the_post_goes_on_without_it(api_responses, storage_responses):
    client, session, _ = uploading([*api_responses, GONE, CREATED], storage_responses)

    assert client.create_post({'eventUrl': 'a'}, [IMAGE]) == Outcome()
    assert ('DELETE', 'http://api.test/media/m1') in requests_made(session)
    assert session.calls[-1][2]['json'] == {'eventUrl': 'a'}


def test_a_storage_that_cannot_be_reached_deletes_the_media():
    class Down(FakeSession):
        def put(self, url, timeout, **kwargs):
            raise requests.ConnectionError('down')

    session = FakeSession([init(), GONE, CREATED])
    client = CooltureApi(session, Down([]), 'http://api.test', token='t')

    assert client.create_post({'eventUrl': 'a'}, [IMAGE]) == Outcome()
    assert ('DELETE', 'http://api.test/media/m1') in requests_made(session)


def test_the_rest_go_on_after_an_image_that_failed():
    client, session, _ = uploading([init('m1'), GONE, init('m2'), OK, CREATED], [FakeResponse(403), OK])

    assert client.create_post({'eventUrl': 'a'}, [IMAGE, IMAGE]) == Outcome(photos=1)
    assert session.calls[-1][2]['json']['mediaIds'] == ['m2']


def test_uploads_stop_at_the_api_limit_without_reading_more_images():
    read = []

    def images():
        for number in range(MAX_PHOTOS + 3):
            read.append(number)
            yield IMAGE

    uploads = [response for number in range(MAX_PHOTOS) for response in (init(f'm{number}'), OK)]
    client, session, _ = uploading([*uploads, CREATED], [OK] * MAX_PHOTOS)

    assert client.create_post({'eventUrl': 'a'}, images()) == Outcome(photos=MAX_PHOTOS)
    assert len(read) == MAX_PHOTOS
    assert len(session.calls[-1][2]['json']['mediaIds']) == MAX_PHOTOS


@pytest.mark.parametrize('status', [401, 403])
def test_a_refused_identity_at_init_stops_the_run_and_deletes_the_earlier_images(status):
    client, session, _ = uploading([init('m1'), OK, FakeResponse(status), GONE], [OK])

    with pytest.raises(ApiError, match='uploads/init'):
        client.create_post({'eventUrl': 'a'}, [IMAGE, IMAGE])
    assert requests_made(session)[-1] == ('DELETE', 'http://api.test/media/m1')


@pytest.mark.parametrize('status', [401, 403])
def test_a_refused_identity_at_complete_stops_the_run_and_deletes_the_images(status):
    client, session, _ = uploading([init('m1'), OK, init('m2'), FakeResponse(status), GONE, GONE], [OK, OK])

    with pytest.raises(ApiError, match='complete'):
        client.create_post({'eventUrl': 'a'}, [IMAGE, IMAGE])
    assert requests_made(session)[-2:] == [('DELETE', 'http://api.test/media/m2'),
                                           ('DELETE', 'http://api.test/media/m1')]


def test_an_error_while_reading_the_images_deletes_the_ones_uploaded():
    def images():
        yield IMAGE
        raise KeyboardInterrupt

    client, session, _ = uploading([init('m1'), OK, GONE], [OK])

    with pytest.raises(KeyboardInterrupt):
        client.create_post({'eventUrl': 'a'}, images())
    assert requests_made(session)[-1] == ('DELETE', 'http://api.test/media/m1')


@pytest.mark.parametrize('status', [400, 401, 409])
def test_the_images_of_a_refused_post_are_deleted(status):
    client, session, _ = uploading([init('m1'), OK, init('m2'), OK, FakeResponse(status), GONE, GONE], [OK, OK])

    with suppress(ApiError):
        client.create_post({'eventUrl': 'a'}, [IMAGE, IMAGE])
    assert requests_made(session)[-2:] == [('DELETE', 'http://api.test/media/m1'),
                                           ('DELETE', 'http://api.test/media/m2')]


def test_the_images_are_kept_when_the_post_may_have_been_saved():
    client, session, _ = uploading([init('m1'), OK, FakeResponse(504)], [OK])

    assert client.create_post({'eventUrl': 'a'}, [IMAGE]).failure.startswith('504')
    assert 'DELETE' not in [method for method, _ in requests_made(session)]


def test_the_images_are_kept_when_the_post_timed_out():
    class SlowPost(FakeSession):
        def post(self, url, timeout, **kwargs):
            if url.endswith('/posts'):
                raise requests.ReadTimeout('slow')
            return super().post(url, timeout, **kwargs)

    session = SlowPost([init('m1'), OK])
    client = CooltureApi(session, FakeSession([OK]), 'http://api.test', token='t')

    assert 'timed out' in client.create_post({'eventUrl': 'a'}, [IMAGE]).failure
    assert 'DELETE' not in [method for method, _ in requests_made(session)]


def test_a_failed_delete_does_not_stop_the_run():
    class DeleteFails(FakeSession):
        def delete(self, url, timeout, **kwargs):
            raise requests.ConnectionError('down')

    session = DeleteFails([init('m1'), OK, FakeResponse(400)])
    client = CooltureApi(session, FakeSession([OK]), 'http://api.test', token='t')

    assert client.create_post({'eventUrl': 'a'}, [IMAGE]).failure.startswith('400')


def test_the_storage_never_gets_the_api_identity():
    client, session, storage = uploading([init(), OK, CREATED], [OK])

    client.create_post({'eventUrl': 'a'}, [IMAGE])

    assert session.headers == {'Authorization': 'Bearer t'}
    assert storage.headers == {}
