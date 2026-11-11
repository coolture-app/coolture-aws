import time
from types import SimpleNamespace

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

import app

PRIVATE_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
SUB = "11111111-1111-1111-1111-111111111111"
ANONYMOUS = {"isAuthorized": True, "context": {}}
DENIED = {"isAuthorized": False}


@pytest.fixture(autouse=True)
def jwks(monkeypatch):
    client = SimpleNamespace(get_signing_key_from_jwt=lambda _token: SimpleNamespace(key=PRIVATE_KEY.public_key()))
    monkeypatch.setattr(app, "_jwks_client", client)


def token(key=PRIVATE_KEY, **overrides) -> str:
    now = int(time.time())
    claims = {
        "sub": SUB,
        "iss": app.ISSUER,
        "token_use": "access",
        "client_id": app.CLIENT_ID,
        "username": SUB,
        "cognito:groups": ["COOLTURE_ADMIN"],
        "iat": now,
        "exp": now + 3600,
    }
    claims.update(overrides)
    return jwt.encode({k: v for k, v in claims.items() if v is not None}, key, algorithm="RS256")


def request(authorization: str | None = None, origin_secret: str | None = None) -> dict:
    headers = {}
    if authorization:
        headers["authorization"] = authorization
    if origin_secret:
        headers["x-origin-verify"] = origin_secret
    return {"headers": headers}


def authorized(sub: str, username: str, groups: str, **claims: str) -> dict:
    context = {"sub": sub, "username": username, "groups": groups}
    context.update({claim: claims.get(claim, "") for claim in app.CONTEXT_CLAIMS})
    return {"isAuthorized": True, "context": context}


def test_should_allow_anonymous_request_when_authorization_header_is_missing():
    # given
    event = request()

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == ANONYMOUS


def test_should_pass_identity_and_groups_when_access_token_is_valid():
    # given
    event = request(f"Bearer {token()}")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == authorized(SUB, SUB, "COOLTURE_ADMIN")


def test_should_accept_id_token_when_audience_matches_client():
    # given
    id_token = token(token_use="id", client_id=None, aud=app.CLIENT_ID, username=None, **{"cognito:username": "jan"})
    event = request(f"Bearer {id_token}")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == authorized(SUB, "jan", "COOLTURE_ADMIN")


def test_should_pass_empty_groups_when_user_belongs_to_no_group():
    # given
    event = request(f"Bearer {token(**{'cognito:groups': None})}")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == authorized(SUB, SUB, "")


@pytest.mark.parametrize(
    "invalid_token",
    [
        pytest.param(lambda: token(exp=int(time.time()) - 10), id="expired"),
        pytest.param(lambda: token(iss="https://evil.example.com"), id="wrong-issuer"),
        pytest.param(lambda: token(client_id="other-client"), id="wrong-client"),
        pytest.param(lambda: token(token_use="refresh"), id="wrong-token-use"),
        pytest.param(lambda: token(key=OTHER_KEY), id="bad-signature"),
        pytest.param(lambda: "not-a-jwt", id="garbage"),
    ],
)
def test_should_deny_request_when_token_is_invalid(invalid_token):
    # given
    event = request(f"Bearer {invalid_token()}")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == DENIED


def test_should_deny_request_when_authorization_header_is_not_bearer():
    # given
    event = request("Basic dXNlcjpwYXNz")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == DENIED


def test_should_treat_request_as_anonymous_when_issuer_is_not_configured(monkeypatch):
    # given
    monkeypatch.setattr(app, "_jwks_client", None)
    event = request(f"Bearer {token()}")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == ANONYMOUS


def test_should_pass_profile_claims_when_id_token_contains_them():
    # given
    id_token = token(
        token_use="id",
        client_id=None,
        aud=app.CLIENT_ID,
        email="jan@example.com",
        given_name="Jan",
        family_name="Kowalski",
    )
    event = request(f"Bearer {id_token}")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == authorized(
        SUB, SUB, "COOLTURE_ADMIN", email="jan@example.com", given_name="Jan", family_name="Kowalski"
    )


def test_should_deny_request_when_origin_secret_is_missing(monkeypatch):
    # given
    monkeypatch.setattr(app, "ORIGIN_SECRET", "s3cr3t")
    event = request(f"Bearer {token()}")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == DENIED


def test_should_deny_request_when_origin_secret_is_wrong(monkeypatch):
    # given
    monkeypatch.setattr(app, "ORIGIN_SECRET", "s3cr3t")
    event = request(origin_secret="guess")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == DENIED


def test_should_allow_anonymous_request_when_origin_secret_matches(monkeypatch):
    # given
    monkeypatch.setattr(app, "ORIGIN_SECRET", "s3cr3t")
    event = request(origin_secret="s3cr3t")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == ANONYMOUS


def test_should_deny_anonymous_request_when_token_is_required(monkeypatch):
    # given
    monkeypatch.setattr(app, "REQUIRE_TOKEN", True)
    event = request()

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == DENIED


def test_should_pass_identity_when_token_is_required_and_valid(monkeypatch):
    # given
    monkeypatch.setattr(app, "REQUIRE_TOKEN", True)
    monkeypatch.setattr(app, "ORIGIN_SECRET", "s3cr3t")
    event = request(f"Bearer {token()}", origin_secret="s3cr3t")

    # when
    result = app.lambda_handler(event, None)

    # then
    assert result == authorized(SUB, SUB, "COOLTURE_ADMIN")
