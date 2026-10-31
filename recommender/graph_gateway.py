import json
import os
import time
from datetime import timedelta

import boto3
import pandas as pd
from graphdatascience.session import AuraAPICredentials, DbmsConnectionInfo, GdsSessions, SessionMemory
from neo4j import GraphDatabase

SESSION_NAME = 'graph-gateway'
GRAPH_NAME = 'graph-gateway'
SESSION_TTL = timedelta(minutes=30)


def read_graph(driver) -> tuple[list[str], list[tuple[str, str]]]:
    nodes, _, _ = driver.execute_query('MATCH (n) WHERE n.id IS NOT NULL RETURN n.id AS id')
    edges, _, _ = driver.execute_query('MATCH (a)-->(b) WHERE a.id IS NOT NULL AND b.id IS NOT NULL '
                                       'RETURN a.id AS source, b.id AS target')
    return [node['id'] for node in nodes], [(edge['source'], edge['target']) for edge in edges]


def page_rank(gds, ids: list[str], edges: list[tuple[str, str]]) -> dict[str, float]:
    positions = {node_id: position for position, node_id in enumerate(ids)}
    nodes_frame = pd.DataFrame({'nodeId': range(len(ids))})
    edges_frame = pd.DataFrame([(positions[source], positions[target]) for source, target in edges],
                               columns=['sourceNodeId', 'targetNodeId'])
    graph = gds.graph.construct(GRAPH_NAME, nodes_frame, edges_frame, overwrite=True)
    scores = gds.page_rank.stream(graph)
    return {ids[row.nodeId]: row.score for row in scores.itertuples()}


def scores_message(run_number: int, scores: dict[str, float]) -> str:
    return json.dumps({'runNumber': run_number, 'scores': scores})


def handler(event, context):
    uri, username, password = os.environ['NEO4J_URI'], os.environ['NEO4J_USERNAME'], os.environ['NEO4J_PASSWORD']
    with GraphDatabase.driver(uri, auth=(username, password)) as driver:
        ids, edges = read_graph(driver)
    if not ids:
        return {'published': False}

    sessions = GdsSessions(AuraAPICredentials(os.environ['AURA_CLIENT_ID'], os.environ['AURA_CLIENT_SECRET']))
    gds = sessions.get_or_create(SESSION_NAME, memory=SessionMemory.m_2GB, ttl=SESSION_TTL, show_progress=False,
                                 db_connection=DbmsConnectionInfo(uri=uri, username=username, password=password))
    try:
        scores = page_rank(gds, ids, edges)
    finally:
        gds.delete()

    run_number = int(time.time() * 1000)
    queue = boto3.client('sqs', endpoint_url=os.environ.get('SQS_ENDPOINT_URL') or None)
    queue.send_message(QueueUrl=os.environ['SCORES_QUEUE_URL'], MessageBody=scores_message(run_number, scores))
    return {'published': True, 'runNumber': run_number, 'scores': len(scores)}
