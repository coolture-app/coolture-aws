import hmac
import logging
import os
from typing import Any

import jwt

logger = logging.getLogger("authorizer")
logger.setLevel(logging.INFO)

ISSUER = os.environ.get("COGNITO_ISSUER", "")
CLIENT_ID = os.environ.get("COGNITO_CLIENT_ID", "")
ORIGIN_SECRET = os.environ.get("ORIGIN_VERIFY_SECRET", "")
REQUIRE_TOKEN = os.environ.get("AUTH_MODE", "optional") == "required"
CONTEXT_CLAIMS = ("email", "given_name", "family_name", "preferred_username")

_jwks_client = jwt.PyJWKClient(f"{ISSUER}/.well-known/jwks.json", cache_keys=True) if ISSUER else None

ANONYMOUS = {"isAuthorized": True, "context": {}}
DENIED = {"isAuthorized": False}


def _bearer_token(headers: dict[str, str]) -> str | None:
    value = headers.get("authorization")
    if not value:
        return None
    scheme, _, token = value.partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise jwt.InvalidTokenError("Authorization header is not a bearer token")
    return token.strip()


def verify(token: str) -> dict[str, Any]:
    signing_key = _jwks_client.get_signing_key_from_jwt(token).key
    claims = jwt.decode(
        token,
        signing_key,
        algorithms=["RS256"],
        issuer=ISSUER,
        options={"require": ["exp", "iat", "sub", "token_use"], "verify_aud": False},
    )

    token_use = claims["token_use"]
    if token_use == "access":
        client = claims.get("client_id")
    elif token_use == "id":
        client = claims.get("aud")
    else:
        raise jwt.InvalidTokenError(f"unsupported token_use: {token_use}")
    if client != CLIENT_ID:
        raise jwt.InvalidAudienceError("token was issued for another client")
    return claims


def _came_through_cloudfront(headers: dict[str, str]) -> bool:
    if not ORIGIN_SECRET:
        return True
    return hmac.compare_digest(headers.get("x-origin-verify", ""), ORIGIN_SECRET)


def lambda_handler(event: dict[str, Any], _context: Any) -> dict[str, Any]:
    headers = event.get("headers") or {}
    if not _came_through_cloudfront(headers):
        logger.info("rejecting request without a valid origin secret (not sent through CloudFront)")
        return DENIED
    try:
        token = _bearer_token(headers)
        if token is None:
            return DENIED if REQUIRE_TOKEN else ANONYMOUS
        if _jwks_client is None:
            logger.warning("COGNITO_ISSUER not configured (sam local?), treating request as anonymous")
            return ANONYMOUS

        claims = verify(token)
    except jwt.PyJWTError as e:
        logger.info("rejecting token: %s", e)
        return DENIED

    return {
        "isAuthorized": True,
        "context": {
            "sub": claims["sub"],
            "username": claims.get("username") or claims.get("cognito:username", ""),
            "groups": ",".join(claims.get("cognito:groups", [])),
            **{claim: str(claims.get(claim, "")) for claim in CONTEXT_CLAIMS},
        },
    }
