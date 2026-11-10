"""The scheduled scraper on AWS: a Lambda outside the VPC, so it can reach trojmiasto.pl, that EventBridge Scheduler
starts at a random moment in every 6 hours (aws/scraper.yaml). Each run does what make scrape does, as the bot, and
stops in time for its 15 minutes; the next run goes on from there.

boto3 and botocore come with the Lambda runtime, so only this file imports them.
"""
import json
import logging
import os
from collections import Counter
from datetime import timedelta

import boto3
import requests
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.exceptions import ClientError

from api import CooltureApi
from geocoder import Geocoder
from scrape import http_session, run, utc_now
from trojmiasto import Site

log = logging.getLogger('scraper')

# Time left after the deadline for the post being sent, with its photos, and for the totals.
MARGIN = timedelta(minutes=3)


class S3Ledger:
    """The ledger in S3, so each run knows what earlier ones imported, deleted posts included."""

    def __init__(self, s3, bucket: str, key: str) -> None:
        self._s3 = s3
        self._bucket = bucket
        self._key = key
        self._urls: set[str] = set()

    def read(self) -> set[str]:
        try:
            body = self._s3.get_object(Bucket=self._bucket, Key=self._key)['Body'].read().decode('utf-8')
        except ClientError as error:
            if error.response['Error']['Code'] != 'NoSuchKey':
                raise
            body = ''
        self._urls = set(body.splitlines())
        return set(self._urls)

    def replace(self, urls: set[str]) -> None:
        self._urls = set(urls)
        self._write()

    def add(self, url: str) -> None:
        self._urls.add(url)
        self._write()

    def _write(self) -> None:
        body = ''.join(f'{url}\n' for url in sorted(self._urls))
        self._s3.put_object(Bucket=self._bucket, Key=self._key, Body=body.encode('utf-8'), ContentType='text/plain')


class SignedForLambdaUrl(requests.auth.AuthBase):
    """The geocoder proxy's function URL takes only IAM-signed requests."""

    def __init__(self, credentials, region: str) -> None:
        self._credentials = credentials
        self._region = region

    def __call__(self, request: requests.PreparedRequest) -> requests.PreparedRequest:
        signed = AWSRequest(method=request.method, url=request.url, data=request.body)
        SigV4Auth(self._credentials.get_frozen_credentials(), 'lambda', self._region).add_auth(signed)
        request.headers.update(dict(signed.headers))
        return request


def bot_id_token(cognito, pool_id: str, client_id: str, username: str, password: str) -> str:
    # The authorizer takes only tokens issued to the app's client, so the bot signs in through it.
    answer = cognito.admin_initiate_auth(UserPoolId=pool_id, ClientId=client_id, AuthFlow='ADMIN_USER_PASSWORD_AUTH',
                                         AuthParameters={'USERNAME': username, 'PASSWORD': password})
    if 'AuthenticationResult' not in answer:
        raise RuntimeError(f'The bot has to answer {answer.get("ChallengeName")}: set a permanent password for it')
    return answer['AuthenticationResult']['IdToken']


def secret(secrets, secret_id: str) -> str:
    return secrets.get_secret_value(SecretId=secret_id)['SecretString']


def handler(event, context) -> dict:
    logging.getLogger().setLevel(logging.INFO)
    # A retry that fails ends in one error, so its warnings would only repeat it.
    logging.getLogger('urllib3').setLevel(logging.ERROR)
    env = os.environ
    session = boto3.session.Session()
    secrets = session.client('secretsmanager')

    bot = json.loads(secret(secrets, env['BOT_SECRET']))
    token = bot_id_token(session.client('cognito-idp'), env['USER_POOL_ID'], env['USER_POOL_CLIENT_ID'],
                         bot['username'], bot['password'])
    api_session = http_session()
    # The API's authorizer lets through only requests that came via CloudFront, or carry its secret.
    api_session.headers['X-Origin-Verify'] = secret(secrets, env['ORIGIN_VERIFY_SECRET'])
    api = CooltureApi(api_session, http_session(), env['API_URL'], token=token,
                      ledger=S3Ledger(session.client('s3'), env['LEDGER_BUCKET'], 'imported.txt'))
    geocoder_session = http_session(retry_timeouts=True)
    geocoder_session.auth = SignedForLambdaUrl(session.get_credentials(), session.region_name)

    stats: Counter = Counter()
    stop_at = utc_now() + timedelta(milliseconds=context.get_remaining_time_in_millis()) - MARGIN
    try:
        run(Site(http_session(retry_timeouts=True)), Geocoder(geocoder_session, env['GEOCODER_URL']), api, stats,
            None, stop_at=stop_at)
    finally:
        log.info('Done: %s', dict(stats))
    return dict(stats)
