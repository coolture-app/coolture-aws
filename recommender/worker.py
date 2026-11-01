import os

import psycopg
from pydantic import BaseModel


class GraphScores(BaseModel):
    runNumber: int
    scores: dict[str, float]


def apply_scores(conn: psycopg.Connection, message: GraphScores) -> bool:
    with conn.transaction():
        conn.execute('LOCK TABLE rec.graph_scores IN EXCLUSIVE MODE')
        latest = conn.execute('SELECT max(run_number) FROM rec.graph_scores').fetchone()[0]
        if latest is not None and message.runNumber <= latest:
            return False
        conn.execute('DELETE FROM rec.graph_scores')
        with conn.cursor() as cursor:
            cursor.executemany('INSERT INTO rec.graph_scores (node_id, score, run_number) VALUES (%s, %s, %s)',
                               [(node_id, score, message.runNumber) for node_id, score in message.scores.items()])
    return True


def handler(event, context):
    with psycopg.connect(os.environ['DATABASE_URL']) as conn:
        for record in event['Records']:
            apply_scores(conn, GraphScores.model_validate_json(record['body']))
