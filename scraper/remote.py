"""Imports into an AWS environment from a computer outside AWS, as the bot: trojmiasto.pl's Cloudflare challenges every
request from AWS's addresses. An always-on computer runs it on a timer (systemd/), and anyone can run it once with
make scrape-aws. Everything it needs comes from the scraper stack (aws/scraper.yaml) through its runner role.
"""
import json
import logging
import os
import random
import sys
from collections import Counter
from datetime import datetime, timedelta
from urllib.parse import urlsplit, urlunsplit

import boto3
import requests
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.exceptions import BotoCoreError, ClientError

from api import ApiError, CooltureApi
from geocoder import Geocoder, GeocoderUnavailable
from scrape import failed_request, exit_code, http_session, run, utc_now
from trojmiasto import Site

log = logging.getLogger('scraper')

DEFAULT_REGION = 'eu-north-1'
# The bot's id token and the runner role's session both last an hour. A run stops between two events after this, and
# the next one goes on from there.
RUN_LIMIT = timedelta(minutes=50)
# At least a second between the site's pages, and never a steady beat, so the runs don't look like a timer.
SITE_PAUSE_S = (1.0, 3.0)


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
        # requests writes a space as +, which botocore signs as it is, while SigV4 and AWS take %20. A literal + is
        # already %2B.
        parts = urlsplit(request.url)
        request.url = urlunsplit(parts._replace(query=parts.query.replace('+', '%20')))
        signed = AWSRequest(method=request.method, url=request.url, data=request.body)
        SigV4Auth(self._credentials.get_frozen_credentials(), 'lambda', self._region).add_auth(signed)
        request.headers.update(dict(signed.headers))
        return request


class CloudWatchLog(logging.Handler):
    """Sends the run's log to its own stream in the scraper's log group, so the team can read it without the computer
    it ran on. A failure to send never stops the run: the computer's own log keeps every line."""

    BATCH = 100
    EVERY_S = 30.0

    def __init__(self, logs, group: str, stream: str, clock=utc_now) -> None:
        super().__init__(logging.INFO)
        self.setFormatter(logging.Formatter('%(levelname)s %(message)s'))
        self._logs = logs
        self._group = group
        self._stream = stream
        self._clock = clock
        self._events: list[dict] = []
        self._sent_at = clock()
        self._sending = False
        self._broken = not self._call('create_log_stream', logGroupName=group, logStreamName=stream)

    def emit(self, record: logging.LogRecord) -> None:
        if self._broken or self._sending:
            return
        self._events.append({'timestamp': int(record.created * 1000), 'message': self.format(record)})
        if len(self._events) >= self.BATCH or (self._clock() - self._sent_at).total_seconds() >= self.EVERY_S:
            self.flush()

    def flush(self) -> None:
        if self._broken or not self._events:
            return
        events, self._events = self._events, []
        self._sent_at = self._clock()
        self._call('put_log_events', logGroupName=self._group, logStreamName=self._stream, logEvents=events)

    def close(self) -> None:
        self.flush()
        super().close()

    def _call(self, action: str, **params) -> bool:
        # botocore's own log lines would come back here while sending.
        self._sending = True
        try:
            getattr(self._logs, action)(**params)
            return True
        except (BotoCoreError, ClientError) as error:
            print(f'The log could not go to CloudWatch ({action}): {error}', file=sys.stderr)
            return False
        finally:
            self._sending = False


def bot_id_token(cognito, pool_id: str, client_id: str, username: str, password: str) -> str:
    # The authorizer takes only tokens issued to the app's client, so the bot signs in through it.
    answer = cognito.admin_initiate_auth(UserPoolId=pool_id, ClientId=client_id, AuthFlow='ADMIN_USER_PASSWORD_AUTH',
                                         AuthParameters={'USERNAME': username, 'PASSWORD': password})
    if 'AuthenticationResult' not in answer:
        raise RuntimeError(f'The bot has to answer {answer.get("ChallengeName")}: set a permanent password for it')
    return answer['AuthenticationResult']['IdToken']


def stack_outputs(cloudformation, stack: str) -> dict[str, str]:
    outputs = cloudformation.describe_stacks(StackName=stack)['Stacks'][0].get('Outputs', [])
    return {output['OutputKey']: output['OutputValue'] for output in outputs}


def stream_name(started: datetime) -> str:
    return started.strftime('%Y-%m-%dT%H-%M-%SZ')


def secret(secrets, secret_id: str) -> str:
    return secrets.get_secret_value(SecretId=secret_id)['SecretString']


def import_events(session, settings: dict[str, str], stats: Counter, stop_at: datetime) -> None:
    secrets = session.client('secretsmanager')
    bot = json.loads(secret(secrets, settings['BotSecretArn']))
    token = bot_id_token(session.client('cognito-idp'), settings['UserPoolId'], settings['UserPoolClientId'],
                         bot['username'], bot['password'])
    api_session = http_session()
    # Outside CloudFront, the API's authorizer lets through only requests that carry its secret.
    api_session.headers['X-Origin-Verify'] = secret(secrets, settings['OriginVerifySecretName'])
    api = CooltureApi(api_session, http_session(), settings['ApiUrl'], token=token,
                      ledger=S3Ledger(session.client('s3'), settings['LedgerBucket'], 'imported.txt'))
    geocoder_session = http_session(retry_timeouts=True)
    geocoder_session.auth = SignedForLambdaUrl(session.get_credentials(), session.region_name)
    site = Site(http_session(retry_timeouts=True), pause=lambda: random.uniform(*SITE_PAUSE_S))
    run(site, Geocoder(geocoder_session, settings['GeocoderUrl']), api, stats, None, stop_at=stop_at)


def main() -> int:
    logging.basicConfig(format='%(asctime)s %(levelname)s %(message)s', stream=sys.stderr)
    logging.getLogger().setLevel(logging.INFO)
    # A retry that fails ends in the one error below, so its warnings would only repeat it.
    logging.getLogger('urllib3').setLevel(logging.ERROR)
    for name in ('boto3', 'botocore'):
        logging.getLogger(name).setLevel(logging.WARNING)

    started = utc_now()
    session = boto3.session.Session()
    if not session.region_name:
        session = boto3.session.Session(region_name=DEFAULT_REGION)
    stack = f'coolture-scraper-{os.getenv("SCRAPER_ENV", "dev")}'
    settings = stack_outputs(session.client('cloudformation'), stack)
    cloudwatch = CloudWatchLog(session.client('logs'), settings['LogGroup'], stream_name(started))
    logging.getLogger().addHandler(cloudwatch)

    stats: Counter = Counter()
    code = 1
    try:
        import_events(session, settings, stats, started + RUN_LIMIT)
        code = exit_code(stats)
    # Everything imported so far stays after an error, and the next run goes on from there.
    except (ApiError, GeocoderUnavailable) as error:
        log.error('Run stopped: %s', error)
    except requests.RequestException as error:
        log.error('Run stopped: %s', failed_request(error))
    except Exception:
        log.exception('Run stopped')
    finally:
        log.info('Done: %s', dict(stats))
        logging.getLogger().removeHandler(cloudwatch)
        cloudwatch.close()
    return code


if __name__ == '__main__':
    sys.exit(main())
