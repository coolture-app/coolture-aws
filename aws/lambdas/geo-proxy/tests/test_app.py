import io
import json
import urllib.error

import pytest

import app


class FakeResponse(io.BytesIO):
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


@pytest.fixture
def photon(monkeypatch):
    asked = []

    def urlopen(url, timeout):
        asked.append(url)
        return FakeResponse(b'{"features": []}')

    monkeypatch.setattr(app, "PHOTON_URL", "http://10.0.1.5:2322")
    monkeypatch.setattr(app.urllib.request, "urlopen", urlopen)
    return asked


def event(path, query=""):
    return {"rawPath": path, "rawQueryString": query}


def test_a_search_goes_to_photon_with_its_query(photon):
    answer = app.handler(event("/structured", "street=Morska&city=Reda"), None)

    assert photon == ["http://10.0.1.5:2322/structured?street=Morska&city=Reda"]
    assert (answer["statusCode"], json.loads(answer["body"])) == (200, {"features": []})


@pytest.mark.parametrize("path", ["/", "/update", "/metrics", "/api/../update"])
def test_other_paths_never_reach_photon(photon, path):
    assert app.handler(event(path), None)["statusCode"] == 404
    assert photon == []


def test_photon_refusing_a_request_passes_its_status_on(monkeypatch, photon):
    def refuse(url, timeout):
        raise urllib.error.HTTPError(url, 400, "Bad Request", {}, io.BytesIO(b'{"message": "bad"}'))

    monkeypatch.setattr(app.urllib.request, "urlopen", refuse)

    assert app.handler(event("/api", "q=x"), None)["statusCode"] == 400


def test_photon_not_answering_is_a_502(monkeypatch, photon):
    def down(url, timeout):
        raise urllib.error.URLError("refused")

    monkeypatch.setattr(app.urllib.request, "urlopen", down)

    assert app.handler(event("/api", "q=x"), None)["statusCode"] == 502
