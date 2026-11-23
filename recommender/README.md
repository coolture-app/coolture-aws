# Recommender

The Python side of the feed: a Lambda that embeds posts and interests, which the feed turns into the viewer's vector
itself (see [Interests and user vectors](#interests-and-user-vectors)), and one that scores events with personalised PageRank
(see [Personalised PageRank](#personalised-pagerank)). Everything here reads the app's tables in `public`
directly. Its own tables are prefixed `rec_`, only the recommender writes them, and they're created by ordinary migrations
in [`../migrations/`](../migrations/). How the feed uses the vectors is in `post-read`'s
[contract](../aws/lambdas/post-read/contracts/README.md).

| Path | What it does |
|---|---|
| `post-embedding/` | Container image: embeds a post's title, description, venue and city, interests and kinds of event, with `mmlw-roberta-base`, and derives each post's kinds into `rec_post_kinds` |
| `ppr/` | Zip function: personalised PageRank for every user, into `rec_ppr_scores` |
| `template.yaml` | Both functions' stack, `coolture-embeddings-<env>`, deployed, rebuilt or removed on its own |

All commands below run from the repository root.

## Embeddings

```
post-write (create or edit)
   └─► PutObject s3://coolture-<env>-embedding-requests-<account>/posts/<postId>   (empty object)
          └─ S3 notification ─► SQS coolture-<env>-post-embedding ─► PostEmbeddingFunction ─► post_embeddings
                                   └─ after 3 failed receives ─► ...-post-embedding-dlq (14 days)
```

- **Why S3 in the middle:** VPC Lambdas have no internet. Reaching SQS would need a paid interface endpoint or
  NAT, while the S3 gateway endpoint and S3 → SQS notifications are free.
- The queue, DLQ and requests bucket are in `aws/backend.yaml`, and `post-write` publishes
  after the database commit. A failed publish is only logged.
- Notifications usually arrive within seconds, sometimes a minute or more. The worker reads the post from the database
  when it runs, so duplicated or late events still embed the latest text. Deleted posts and `s3:TestEvent` are skipped.
  Request objects expire after a day.
- `{"action":"embed-interests"}` to `PostEmbeddingFunction` embeds every interest with no vector, or whose
  `embedding_text` or model changed, and every kind of event the same way (a kind's vector is the normalised mean of its
  `embedding_texts`, in `event_kind_embeddings`). `{"action":"derive-kinds"}` re-derives every post's kinds from the
  stored post vectors, so a changed kind text takes both.
- `{"action":"embed-posts"}` embeds every post that isn't deleted and has no vector of the current model or one older than its
  last edit, newest first, and derives its kinds. It stops in time to answer before the function's timeout, with
  `{"posts": <embedded>, "left": <still without one>}`. `scripts/deploy.sh` sends `embed-interests`, `derive-kinds`
  and `embed-posts` after deploying this stack, and `embed-posts` again while posts are left.
- **Kinds of event.** After storing a post's vector the worker writes its kinds to `rec_post_kinds`, best first: the
  closest kind, plus the second within a margin, with Dla dzieci kept or added only when close enough to the best other
  kind (`post-embedding/src/kinds.py`, with its constants). Until every kind has a vector, posts get none.
- Building `PostEmbeddingFunction` needs Docker: it's a container image with CPU-only torch and the model baked in.

**Locally** there are no S3 notifications or SQS triggers, and `post-write` skips the publish. `make seed` embeds
interests and kinds, then posts, then derives kinds. One at a time:

```bash
make embed-interests                 # interests and kinds with no vector, or a changed text or model
make derive-kinds                    # every post's kinds, from its stored vector (after a kind's text changed)
make embed-posts                     # posts with no vector, another model's, or edited since
make embed-posts POST_ID=<uuid>      # one post
make embed-posts ALL=1               # every post that isn't deleted
make embed-posts DRY_RUN=1           # print the SQS/S3 events it would send
```

They build the worker on first use, and again when its code or the template changed (`make build-embeddings`).
`embed-posts` (`scripts/embed-posts.sh`) wraps posts in the same events AWS delivers, 50 per invocation (`BATCH_SIZE=`),
and exits non-zero if any fails. `make test-python` runs the worker's unit tests.

### Changing the model

The model is `sdadas/mmlw-roberta-base`, pinned to a revision in `post-embedding/Dockerfile`.
Vectors of different models are never compared: the feed builds the viewer's vector from the vectors of
`FeedConfig.EMBEDDING_MODEL` only, and gives a post of another model a neutral affinity of `0.5`, as for a post with no
vector.

1. Set the model in the `Dockerfile`, in `post-embedding/src/settings.py`, in `ppr/src/app.py` and in `FeedConfig.EMBEDDING_MODEL`
   (`test_model_name.py` checks they agree). Keep it 768-dimensional, or add a migration for the vector columns.
2. Re-embed interests, then posts.
3. Measure `AFFINITY_LOW` and `AFFINITY_HIGH` in `FeedConfig` again. They cover the cosines this model gives between
   the viewer's vector and posts once the average post and the average interest are taken out (`UserVector`), so they
   belong to the model. So does `NOT_FOR_ME_FROM`, the p95 cosine between two posts with the average post taken out,
   and the kinds' margin and Dla dzieci's threshold at the top of `post-embedding/src/kinds.py`.

A new model name is what marks a vector as old. A new revision, or a change to how text is encoded, isn't visible that
way: locally, clear `interest_embeddings` and `event_kind_embeddings` and run `make embed-interests`, then
`make embed-posts ALL=1`. There's no equivalent on AWS yet.

Locally: `make build-embeddings embed-interests embed-posts`. On AWS, `make deploy-embeddings` deploys the stack and sends
the actions. When the deploy can't invoke the worker, it warns, and someone allowed to invoke it sends the actions by hand, `embed-posts`
again while `left` is above 0 (replace `dev` for another environment):

```bash
for action in embed-interests derive-kinds embed-posts; do
    aws lambda invoke --function-name coolture-dev-post-embedding --cli-binary-format raw-in-base64-out \
        --cli-read-timeout 150 --payload "{\"action\":\"$action\"}" reply.json && cat reply.json && echo
done
```

## Interests and user vectors

The interests and kinds a user picks are in [`users-api.yaml`](../aws/lambdas/user/contracts/users-api.yaml). Kinds of
event are never rows of `interests`, and the interests stay as the survey's respondents picked them.

The feed builds the viewer's vector on every page, in `post-read` (`FeedRepository.findVectorParts`, then `UserVector`,
which says how and why). Nothing is stored, so a reaction or a new pick counts from the next session, and every page of a
session ranks with the same vector. The weights and the decay are in `FeedConfig`.

## Personalised PageRank

The thesis's graph signal: where a user's friends go, and events like those. `PprFunction` runs personalised
PageRank for every user over one graph of events and venues, the same for everyone:

| Edges | From |
|---|---|
| Event–event | Each event to its nearest events by content (`EVENT_NEIGHBOURS` in `ppr.py`), among events that haven't ended |
| Event–venue | `event_locations`, by lower-cased name and city |

A user's walk starts, and restarts, on their friends' events:

| Start | From |
|---|---|
| A friend's share | Friends: mutual follows, no block, who share their stances. By the square root of the friend's faded stances: fresh ones count more, and an active friend gets more say but less on each event |
| An event's share of that | The friend's interested and going, weighed and faded as the feed weighs and fades them. Not likes |

A user's own picks and stances start no walk: the feed's affinity holds them. No user is a node, so a walk reaches a
friend's interested and going, as the cards show them, and nothing past them: not their friends, not strangers. A block
ends the friendship. A user whose friends marked nothing gets no scores.

The walk's mass is ranked from 0 to 1 among every event that hasn't ended, the user's own, reacted and blocked posts
too: the feed leaves those out. Only each user's `SCORES_PER_USER` highest ranks above `NEUTRAL` get a row, and the
feed reads a post with no row as `NEUTRAL` (weight `ppr` in `FeedConfig`), so PageRank lifts events and pushes none
down. The feed reads the latest run finished `PPR_RUN_MARGIN_SECONDS` before the session's `asOf`, so a run landing
mid-scroll doesn't move its pages, and ignores a run older than `PPR_RUN_MAX_AGE_HOURS`, whose friendships may have
changed since, so locally PageRank's effect goes after a day unless `make ppr` runs again. The next run deletes runs
the feed no longer reads.

`load()` takes an `as_of` to replay an earlier moment, with the limits its docstring lists.

```bash
make ppr                           # one run locally; make seed ends with it
make test-db                       # its database tests, with the others, after make seed
```

On AWS it runs on a schedule once deployed with `PPR_SCHEDULE=true make deploy-embeddings`, and keeps that setting on
later deploys; it's off by default. To start a run by hand there, invoke it with `--invocation-type Event`: it runs one
at a time, so a direct invoke during a run is refused. `FEED_WEIGHTS=ppr=0 make deploy-backend` keeps the runs but
gives them no say in the feed, for shadow runs; later deploys keep the weights until `FEED_WEIGHTS=default`.
