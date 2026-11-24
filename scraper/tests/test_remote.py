import io
import json
import logging
from datetime import datetime, timedelta, timezone

import pytest
import requests
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials
from botocore.exceptions import ClientError

import remote
from api import ApiError
from remote import RUN_LIMIT, CloudWatchLog, S3Ledger, SignedForLambdaUrl, bot_id_token, stack_outputs, stream_name


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


OUTPUTS = {'BotSecretArn': 'bot-secret', 'OriginVerifySecretName': 'origin-secret', 'UserPoolId': 'pool',
           'UserPoolClientId': 'client', 'ApiUrl': 'https://api.test/dev', 'LedgerBucket': 'ledger',
           'GeocoderUrl': 'https://geo.test/', 'LogGroup': '/coolture/dev/scraper'}


class FakeCloudFormation:
    def __init__(self):
        self.asked = []

    def describe_stacks(self, StackName):
        self.asked.append(StackName)
        return {'Stacks': [{'Outputs': [{'OutputKey': key, 'OutputValue': value} for key, value in OUTPUTS.items()]}]}


class FakeLogs:
    def __init__(self, fail=None):
        self.fail = fail
        self.streams = []
        self.batches = []

    def create_log_stream(self, logGroupName, logStreamName):
        if self.fail == 'create_log_stream':
            raise ClientError({'Error': {'Code': 'AccessDeniedException'}}, 'CreateLogStream')
        self.streams.append((logGroupName, logStreamName))

    def put_log_events(self, logGroupName, logStreamName, logEvents):
        if self.fail == 'put_log_events':
            raise ClientError({'Error': {'Code': 'ThrottlingException'}}, 'PutLogEvents')
        self.batches.append([event['message'] for event in logEvents])


class FakeSession:
    region_name = 'eu-north-1'
    last = None

    def __init__(self, region_name=None):
        self.s3 = FakeS3()
        self.cloudformation = FakeCloudFormation()
        self.logs = FakeLogs()
        FakeSession.last = self

    def client(self, name):
        if name == 'secretsmanager':
            return type('Secrets', (), {'get_secret_value': staticmethod(lambda SecretId: {'SecretString': {
                'bot-secret': json.dumps({'username': 'coolture_bot', 'password': 'pw'}),
                'origin-secret': 'origin',
            }[SecretId]})})()
        if name == 'cognito-idp':
            return FakeCognito({'AuthenticationResult': {'IdToken': 'id-token'}})
        return {'cloudformation': self.cloudformation, 'logs': self.logs}.get(name, self.s3)

    def get_credentials(self):
        return Credentials('AKID', 'SECRET')


def test_settings_are_the_stacks_outputs():
    assert stack_outputs(FakeCloudFormation(), 'coolture-scraper-dev') == OUTPUTS


@pytest.fixture
def aws(monkeypatch):
    monkeypatch.setattr(remote.boto3.session, 'Session', FakeSession)
    monkeypatch.delenv('SCRAPER_ENV', raising=False)


def test_a_run_goes_through_the_api_as_the_bot_and_stops_in_time(monkeypatch, aws):
    calls = []
    monkeypatch.setattr(remote, 'run', lambda *args, stop_at: (calls.append((args, stop_at)), args[3].update(listed=1)))
    before = remote.utc_now()

    assert remote.main() == 0

    after = remote.utc_now()
    (site, geocoder, api, stats, limit), stop_at = calls[0]
    assert FakeSession.last.cloudformation.asked == ['coolture-scraper-dev']
    assert api._session.headers['Authorization'] == 'Bearer id-token'
    assert api._session.headers['X-Origin-Verify'] == 'origin'
    assert isinstance(geocoder._session.auth, SignedForLambdaUrl)
    assert limit is None
    pauses = [site._pause() for _ in range(20)]
    assert all(1.0 <= pause <= 3.0 for pause in pauses) and len(set(pauses)) > 1
    assert before + RUN_LIMIT <= stop_at <= after + RUN_LIMIT


def test_the_runs_log_goes_to_its_own_stream(monkeypatch, aws):
    monkeypatch.setattr(remote, 'run', lambda *args, stop_at: args[3].update(listed=2))

    remote.main()

    logs = FakeSession.last.logs
    [(group, stream)] = logs.streams
    assert group == '/coolture/dev/scraper'
    assert stream.startswith(remote.utc_now().strftime('%Y-%m-%d'))
    assert logs.batches[-1][-1] == "INFO Done: {'listed': 2}"
    assert not any(isinstance(handler, CloudWatchLog) for handler in logging.getLogger().handlers)


@pytest.mark.parametrize('stats', [{}, {'listed': 3, 'rejected': 1}])
def test_a_run_that_lists_nothing_or_has_a_rejected_post_fails(monkeypatch, aws, stats):
    monkeypatch.setattr(remote, 'run', lambda *args, stop_at: args[3].update(stats))

    assert remote.main() == 1


def test_a_stopped_run_says_why_in_one_line_and_fails(monkeypatch, aws, caplog):
    def fail(*args, stop_at):
        args[3].update(listed=3)
        raise ApiError('POST /posts returned 500')

    monkeypatch.setattr(remote, 'run', fail)

    assert remote.main() == 1
    assert 'Run stopped: POST /posts returned 500' in caplog.text
    assert "INFO Done: {'listed': 3}" in FakeSession.last.logs.batches[-1]


def test_the_stream_is_named_by_the_runs_start():
    assert stream_name(datetime(2026, 11, 25, 3, 14, 7, tzinfo=timezone.utc)) == '2026-11-25T03-14-07Z'


def record(message):
    return logging.LogRecord('scraper', logging.INFO, __file__, 1, message, None, None)


def test_the_log_is_sent_in_batches_and_at_the_end():
    logs = FakeLogs()
    handler = CloudWatchLog(logs, 'group', 'stream')

    for number in range(CloudWatchLog.BATCH + 1):
        handler.emit(record(f'line {number}'))
    handler.close()

    assert [len(batch) for batch in logs.batches] == [CloudWatchLog.BATCH, 1]


def test_the_log_is_sent_every_30_seconds_on_a_slow_run():
    now = [datetime(2026, 11, 25, tzinfo=timezone.utc)]
    logs = FakeLogs()
    handler = CloudWatchLog(logs, 'group', 'stream', clock=lambda: now[0])

    handler.emit(record('first'))
    now[0] += timedelta(seconds=CloudWatchLog.EVERY_S)
    handler.emit(record('second'))

    assert logs.batches == [['INFO first', 'INFO second']]


@pytest.mark.parametrize('failing', ['create_log_stream', 'put_log_events'])
def test_a_log_that_cannot_be_sent_never_stops_the_run(capsys, failing):
    handler = CloudWatchLog(FakeLogs(fail=failing), 'group', 'stream')

    handler.emit(record('line'))
    handler.close()

    assert 'The log could not go to CloudWatch' in capsys.readouterr().err
