import io
import json
from datetime import timedelta

import pytest
import requests
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials
from botocore.exceptions import ClientError

import lambda_handler
from lambda_handler import MARGIN, S3Ledger, SignedForLambdaUrl, bot_id_token


class FakeS3:
    def __init__(self, body=None, error='NoSuchKey'):
        self.body = body
        self.error = error
        self.puts = []

    def get_object(self, Bucket, Key):
        if self.body is None:
            raise ClientError({'Error': {'Code': self.error}}, 'GetObject')
        return {'Body': io.BytesIO(self.body.encode('utf-8'))}

    def put_object(self, Bucket, Key, Body, ContentType):
        self.puts.append(Body.decode('utf-8'))


def test_a_ledger_not_written_yet_is_empty():
    assert S3Ledger(FakeS3(), 'bucket', 'imported.txt').read() == set()


def test_a_ledger_reads_what_earlier_runs_wrote():
    assert S3Ledger(FakeS3('a\nb\n'), 'bucket', 'imported.txt').read() == {'a', 'b'}


def test_a_ledger_that_cannot_be_read_stops_the_run():
    with pytest.raises(ClientError):
        S3Ledger(FakeS3(error='AccessDenied'), 'bucket', 'imported.txt').read()


def test_a_ledger_saves_each_change_at_once():
    s3 = FakeS3('a\n')
    ledger = S3Ledger(s3, 'bucket', 'imported.txt')

    ledger.replace(ledger.read() | {'c'})
    ledger.add('b')

    assert s3.puts == ['a\nc\n', 'a\nb\nc\n']


def test_geocoder_requests_are_signed_for_a_lambda_url():
    request = requests.Request('GET', 'https://abc.lambda-url.eu-north-1.on.aws/structured',
                               params={'street': 'Morska', 'city': 'Reda'}).prepare()

    SignedForLambdaUrl(Credentials('AKID', 'SECRET'), 'eu-north-1')(request)

    assert request.headers['Authorization'].startswith('AWS4-HMAC-SHA256 Credential=AKID/')
    assert '/eu-north-1/lambda/aws4_request' in request.headers['Authorization']
    assert 'X-Amz-Date' in request.headers


def test_a_space_is_sent_and_signed_as_percent_20_which_aws_expects():
    request = requests.Request('GET', 'https://abc.lambda-url.eu-north-1.on.aws/api',
                               params={'q': 'Klub Żak, Gdańsk', 'tag': 'a+b'}).prepare()
    expected = AWSRequest(method='GET', url='https://abc.lambda-url.eu-north-1.on.aws/api'
                                            '?q=Klub%20%C5%BBak%2C%20Gda%C5%84sk&tag=a%2Bb')

    SignedForLambdaUrl(Credentials('AKID', 'SECRET'), 'eu-north-1')(request)

    assert request.url == expected.url
    signer = SigV4Auth(Credentials('AKID', 'SECRET'), 'lambda', 'eu-north-1')
    expected.context['timestamp'] = request.headers['X-Amz-Date']
    expected.headers['X-Amz-Date'] = request.headers['X-Amz-Date']
    assert request.headers['Authorization'].endswith(
        signer.signature(signer.string_to_sign(expected, signer.canonical_request(expected)), expected))


class FakeCognito:
    def __init__(self, answer):
        self.answer = answer
        self.asked = []

    def admin_initiate_auth(self, **params):
        self.asked.append(params)
        return self.answer


def test_the_bot_signs_in_through_the_apps_client():
    cognito = FakeCognito({'AuthenticationResult': {'IdToken': 'id-token'}})

    assert bot_id_token(cognito, 'pool', 'client', 'coolture_bot', 'secret') == 'id-token'
    assert cognito.asked == [{'UserPoolId': 'pool', 'ClientId': 'client', 'AuthFlow': 'ADMIN_USER_PASSWORD_AUTH',
                              'AuthParameters': {'USERNAME': 'coolture_bot', 'PASSWORD': 'secret'}}]


def test_a_bot_with_a_temporary_password_says_what_to_do():
    with pytest.raises(RuntimeError, match='permanent password'):
        bot_id_token(FakeCognito({'ChallengeName': 'NEW_PASSWORD_REQUIRED'}), 'pool', 'client', 'bot', 'x')


class FakeSession:
    region_name = 'eu-north-1'

    def __init__(self):
        self.s3 = FakeS3()

    def client(self, name):
        if name == 'secretsmanager':
            return type('Secrets', (), {'get_secret_value': staticmethod(lambda SecretId: {'SecretString': {
                'bot-secret': json.dumps({'username': 'coolture_bot', 'password': 'pw'}),
                'origin-secret': 'origin',
            }[SecretId]})})()
        if name == 'cognito-idp':
            return FakeCognito({'AuthenticationResult': {'IdToken': 'id-token'}})
        return self.s3

    def get_credentials(self):
        return Credentials('AKID', 'SECRET')


class FakeContext:
    def get_remaining_time_in_millis(self):
        return 15 * 60 * 1000


def test_a_run_goes_through_the_api_as_the_bot_and_stops_in_time(monkeypatch):
    calls = []
    monkeypatch.setattr(lambda_handler.boto3.session, 'Session', FakeSession)
    monkeypatch.setattr(lambda_handler, 'run', lambda *args, stop_at: calls.append((args, stop_at)))
    for name, value in {'BOT_SECRET': 'bot-secret', 'ORIGIN_VERIFY_SECRET': 'origin-secret', 'USER_POOL_ID': 'pool',
                        'USER_POOL_CLIENT_ID': 'client', 'API_URL': 'https://api.test/dev', 'LEDGER_BUCKET': 'ledger',
                        'GEOCODER_URL': 'https://geo.test/'}.items():
        monkeypatch.setenv(name, value)
    before = lambda_handler.utc_now()

    assert lambda_handler.handler({}, FakeContext()) == {}

    after = lambda_handler.utc_now()
    (site, geocoder, api, stats, limit), stop_at = calls[0]
    assert api._session.headers['Authorization'] == 'Bearer id-token'
    assert api._session.headers['X-Origin-Verify'] == 'origin'
    assert isinstance(geocoder._session.auth, SignedForLambdaUrl)
    assert limit is None
    pauses = [site._pause() for _ in range(20)]
    assert all(1.0 <= pause <= 3.0 for pause in pauses) and len(set(pauses)) > 1
    assert before + timedelta(minutes=15) - MARGIN <= stop_at <= after + timedelta(minutes=15) - MARGIN
