import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
import requests

from scrape import http_session


class Stub:
    """Answers on the loopback only, because how urllib3 and requests treat a retry can't be seen through a fake session."""

    def __init__(self):
        self.url = ''
        self.requests = 0
        self.answers = []
        self.released = threading.Event()


@pytest.fixture
def stub():
    state = Stub()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def _answer(self):
            self.rfile.read(int(self.headers.get('Content-Length') or 0))
            state.requests += 1
            status, headers = state.answers.pop(0) if state.answers else (200, {})
            if status is None:
                state.released.wait(5)
                return
            self.send_response(status)
            for name, value in {**headers, 'Content-Length': '0'}.items():
                self.send_header(name, value)
            self.end_headers()

        do_GET = do_POST = _answer

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    state.url = f'http://127.0.0.1:{server.server_port}'
    yield state
    state.released.set()
    server.shutdown()
    server.server_close()


@pytest.fixture
def pauses(monkeypatch):
    waited = []
    monkeypatch.setattr('urllib3.util.retry.time.sleep', waited.append)
    return waited


def test_a_throttle_is_retried_after_the_time_the_server_asks_for(stub, pauses):
    stub.answers = [(429, {'Retry-After': '7'})]

    assert http_session().get(stub.url).status_code == 200
    assert pauses == [7]


def test_a_retry_with_no_stated_time_still_waits(stub, pauses):
    stub.answers = [(429, {}), (429, {})]

    assert http_session().get(stub.url).status_code == 200
    assert pauses == [2, 4]


def test_a_lasting_throttle_ends_with_the_response(stub, pauses):
    stub.answers = [(429, {})] * 10

    assert http_session().get(stub.url).status_code == 429
    assert stub.requests == 4


def test_a_failure_on_a_read_is_not_retried(stub, pauses):
    stub.answers = [(503, {})]

    assert http_session().get(stub.url).status_code == 503
    assert stub.requests == 1


@pytest.mark.parametrize('headers', [{}, {'Retry-After': '1'}])
def test_a_post_is_not_sent_again_after_a_failure_that_may_have_saved_it(stub, pauses, headers):
    stub.answers = [(503, headers)]

    assert http_session().post(stub.url, json={}).status_code == 503
    assert stub.requests == 1


def test_a_post_is_not_sent_again_after_a_timeout(stub, pauses):
    stub.answers = [(None, {})]

    with pytest.raises(requests.Timeout):
        http_session().post(stub.url, json={}, timeout=0.2)

    assert stub.requests == 1
