# Recommender

Python code of the recommender. It reads the app's tables in `public` directly and keeps its own tables there too,
prefixed with `rec_` (only the recommender writes them), created by ordinary migrations in
[`../migrations/`](../migrations/) (e.g. `V9__add_rec_graph_scores.sql`).

| File | What it does |
|---|---|
| `worker.py` | SQS handler: stores a graph-scores snapshot in `rec_graph_scores`, ignoring repeated or late runs |
| `graph_gateway.py` | Reads the graph from AuraDB, runs PageRank in an Aura analytics session, publishes the scores to SQS |
| `local_runner.py` | Runs either one locally: `worker` polls the queue, `gateway` runs the gateway once |
| `elasticmq.conf` | Local SQS queues (`coolture-recommendation-events` and its DLQ) |
| `docs/auradb-check.md` | Measurements of the AuraDB path |

All commands below run from the repository root.

## Worker (`rec` profile)

```bash
make rec-up     # make up + ElasticMQ on 127.0.0.1:9324 (SQS_PORT) + the worker
make rec-test   # pytest in the worker container against the local Postgres
make logs SERVICE=worker
```

The worker container mounts this folder, so code changes need only a restart (`docker compose --profile rec restart worker`).
Changes to `requirements.txt` need `make rec-up` to rebuild the image.

## Graph gateway (`graph` profile)

Needs a hosted AuraDB instance and an Aura API key. Copy `.env.example` to `.env` (ignored by git), fill it in, then:

```bash
docker compose --profile rec --profile graph run --rm graph-gateway
```

The gateway publishes one scores message to the local queue; with the `rec` profile running, the worker stores it in
`rec_graph_scores`.
