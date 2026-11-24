"""Lets the scheduled scraper, which runs outside the VPC to reach trojmiasto.pl, ask the geocoder in the VPC.

Its function URL takes only IAM-signed requests, and only Photon's read paths go through.
"""
import os
import urllib.error
import urllib.request

PHOTON_URL = os.environ.get("PHOTON_URL", "")
PATHS = {"/api", "/structured", "/reverse", "/status"}
TIMEOUT_S = 10


def _answer(status: int, body: str) -> dict:
    return {"statusCode": status, "headers": {"content-type": "application/json"}, "body": body}


def handler(event, context):
    path = event.get("rawPath", "")
    if path not in PATHS:
        return _answer(404, '{"message": "not found"}')
    query = event.get("rawQueryString", "")
    url = f"{PHOTON_URL}{path}" + (f"?{query}" if query else "")
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT_S) as response:
            return _answer(response.status, response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        return _answer(error.code, error.read().decode("utf-8", errors="replace"))
    except OSError as error:
        return _answer(502, f'{{"message": "Photon did not answer: {type(error).__name__}"}}')
