import logging
import os
import sys

import boto3

import graph_gateway
import worker

RECEIVE_WAIT_SECONDS = 10
logging.basicConfig(level=logging.INFO)
log = logging.getLogger('local_runner')


def poll_queue() -> None:
    queue = boto3.client('sqs', endpoint_url=os.environ['SQS_ENDPOINT_URL'])
    url = os.environ['SCORES_QUEUE_URL']
    while True:
        received = queue.receive_message(QueueUrl=url, MaxNumberOfMessages=10, WaitTimeSeconds=RECEIVE_WAIT_SECONDS)
        for message in received.get('Messages', []):
            try:
                worker.handler({'Records': [{'messageId': message['MessageId'], 'body': message['Body']}]}, None)
            except Exception:
                log.exception('message %s stays on the queue', message['MessageId'])
                continue
            queue.delete_message(QueueUrl=url, ReceiptHandle=message['ReceiptHandle'])
            log.info('applied message %s', message['MessageId'])


if __name__ == '__main__':
    match sys.argv[1:]:
        case ['worker']:
            poll_queue()
        case ['gateway']:
            print(graph_gateway.handler({}, None))
        case _:
            sys.exit('usage: local_runner.py worker | gateway')
