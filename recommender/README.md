# Recommender

The Python side of the feed: a Lambda that embeds posts and interests, which the feed turns into the viewer's vector
itself (see [Interests and user vectors](#interests-and-user-vectors)), and one that scores events with a graph walk
(see [Graph signal](#graph-signal)). Everything here reads the app's tables in `public`
directly. Its own tables are prefixed `rec_`, only the recommender writes them, and they're created by ordinary migrations
in [`../migrations/`](../migrations/). How the feed uses the vectors is in `post-read`'s
[contract](../aws/lambdas/post-read/contracts/README.md).

| Path | What it does |
|---|---|
| `embedding-workers/post-embedding/` | Container image: embeds a post's title, description and tags, and interests, with `mmlw-roberta-base` |
| `graph-signal/` | Zip function: personalised PageRank for every user, into `rec_graph_scores` |
| `embedding-workers/template.yaml` | Both functions' stack, `coolture-embeddings-<env>`, deployed, rebuilt or removed on its own |

All commands below run from the repository root.

## Embeddings

```
post-write (create, or a change to title, description or tags)
   └─► PutObject s3://coolture-<env>-embedding-requests-<account>/posts/<postId>   (empty object)
          └─ S3 notification ─► SQS coolture-<env>-post-embedding ─► PostEmbeddingFunction ─► post_embeddings
                                   └─ after 3 failed receives ─► ...-post-embedding-dlq (14 days)
```

- **Why S3 in the middle:** VPC Lambdas have no internet. Reaching SQS would need a paid interface endpoint (~$9 a month)
  or NAT, while the S3 gateway endpoint and S3 → SQS notifications are free.
- The queue, DLQ and requests bucket are in `aws/backend.yaml`, and the publisher is in `common`. `post-write` publishes
  after the database commit. A failed publish is only logged.
- Notifications usually arrive within seconds, sometimes a minute or more. The worker reads the post from the database
  when it runs, so duplicated or late events still embed the latest text. Deleted posts and `s3:TestEvent` are skipped.
  Request objects expire after a day.
- `{"action":"embed-interests"}` to `PostEmbeddingFunction` embeds every interest with no vector, or whose
  `embedding_text` or model changed. `scripts/deploy.sh` sends it after deploying this stack.
- Building `PostEmbeddingFunction` needs Docker: it's a container image with CPU-only torch and the model baked in.

**Locally** there are no S3 notifications or SQS triggers, and `post-write` skips the publish. `make seed` embeds
interests, then posts. One at a time:

```bash
make embed-interests                 # interests with no vector, or a changed text or model
make embed-posts                     # posts with no vector, another model's, or edited since
make embed-posts POST_ID=<uuid>      # one post
make embed-posts ALL=1               # every post that isn't deleted
make embed-posts DRY_RUN=1           # print the SQS/S3 events it would send
```

They build the worker on first use (`make build-embeddings`; run it again after changing it). `embed-posts`
(`scripts/embed-posts.sh`) wraps posts in the same events AWS delivers, 50 per invocation (`BATCH_SIZE=`), and exits
non-zero if any fails. `make test-python` runs the worker's unit tests.

### Changing the model

The model is `sdadas/mmlw-roberta-base`, pinned to a revision in `embedding-workers/post-embedding/Dockerfile`.
Vectors of different models are never compared: the feed builds the viewer's vector from the vectors of
`FeedConfig.EMBEDDING_MODEL` only, and gives a post of another model a neutral affinity of `0.5`, as for a post with no
vector.

1. Set the model in the `Dockerfile`, in `post-embedding/src/settings.py` and in `FeedConfig.EMBEDDING_MODEL`
   (`test_model_name.py` checks they agree). Keep it 768-dimensional, or add a migration for the vector columns.
2. Re-embed interests, then posts.
3. Measure `AFFINITY_LOW` and `AFFINITY_HIGH` in `FeedConfig` again. They cover the cosines this model gives between
   the viewer's vector and posts once the average post and the average interest are taken out (`UserVector`), so they
   belong to the model.

A new model name is what marks a vector as old. A new revision, or a change to how text is encoded, isn't visible that
way: locally, clear `interest_embeddings` and run `make embed-posts ALL=1`. There's no equivalent on AWS yet.

Locally: `make build-embeddings embed-interests embed-posts`. On AWS (untested: none of this has run on AWS yet; replace
`dev` for another environment):

```bash
# 1. Interests. The deploy does this.
make deploy-embeddings

# 2. Posts: an empty request object per post, through the normal S3 → SQS → PostEmbeddingFunction path.
API="$(aws cloudformation describe-stacks --stack-name coolture-backend-dev \
    --query "Stacks[0].Outputs[?OutputKey=='ApiEndpoint'].OutputValue" --output text)"
API="${API%/}"
BUCKET="coolture-dev-embedding-requests-$(aws sts get-caller-identity --query Account --output text)"
cursor=""
while :; do
    page="$(curl -fsS -G "$API/posts" -d includePast=true -d limit=100 ${cursor:+--data-urlencode "cursor=$cursor"})"
    for id in $(jq -r '.items[].id' <<< "$page"); do
        aws s3api put-object --bucket "$BUCKET" --key "posts/$id" > /dev/null
    done
    cursor="$(jq -r '.page.nextCursor // empty' <<< "$page")"
    [[ -n "$cursor" ]] || break
done

# Wait until both counts are 0. Anything on coolture-dev-post-embedding-dlq failed three times.
aws sqs get-queue-attributes --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --queue-url "$(aws sqs get-queue-url --queue-name coolture-dev-post-embedding --query QueueUrl --output text)"
```

The listing goes through the public API, because Aurora is reachable only from inside the VPC, so it sees public posts
only. A friends-only or private post keeps its old vector until it's next edited: it stays neutral in the feed, and likes
of it don't count towards the viewer's vector.

## Interests and user vectors

`GET /dicts/interests` returns the catalogue from migration V9 (`interest_categories` group `interests`), and `GET` and
`PUT /users/{userId}/interests` read and replace the caller's picks. `/auth/me` returns `interestsSelected`, and the
frontend blocks the page with the interest picker until a user has some.

The feed builds the viewer's vector on every page, in `post-read` (`FeedRepository.findVectorParts`, then `UserVector`):
the posts they liked, are interested in or going to, up to the session's `asOf`, blended with their picks, each pick with
a share of its category's average. Interests give way as the viewer engages more, down to a floor. The stances lose the
average post and the interests the average interest before they're blended, and each post loses the average post before
it's compared, so what every text shares doesn't decide the order (`UserVector` says why). Nothing is stored, so
a reaction or a new pick counts from the next session, and every page of a session ranks with the same vector. The
weights and the decay are in `FeedConfig`.

| Setting | Default | Where | Meaning |
|---|---|---|---|
| `INTERESTS_MIN_SELECTIONS`, `INTERESTS_MAX_SELECTIONS` | 3, 6 | `aws/backend.yaml` | Selection limits, also sent to the frontend |

## Graph signal

`GraphSignalFunction` runs personalised PageRank from every user over one graph:

| Edges | From |
|---|---|
| Event–venue | `event_locations`, by lower-cased name and city |
| Event–interest | Each event's 2 nearest interests, both sides centred as in the feed |
| User–interest | `user_interests` |
| User–event | Likes, interested and going, faded as the feed fades them |
| Event–event | Each event's 5 nearest among events that haven't ended |
| User → user | Follows, minus blocks either way |

Each user's candidates (events not ended, not theirs, not reacted to) get the walk's mass as a rank from 0 to 1. A run is
one row of `rec_graph_runs` with its rows in `rec_graph_scores`, marked finished once they're committed. The feed reads
the latest run finished a minute before the session's `asOf`, so a run landing mid-scroll doesn't move its pages, and
gives a post with no score `0.5`. Its weight is `graph` in `FeedConfig`. Runs older than one finished a day ago are
deleted, and a session older than that ranks with `0.5` for every post.

```bash
make graph-signal                    # one run locally; make seed ends with it
DB_TEST=true .venv/bin/python -m pytest recommender/graph-signal/tests   # with its database tests, after make seed
```

On AWS it runs on a schedule, off by default: `GraphScheduleEnabled=true` and `GraphSchedule` (default `rate(6 hours)`)
are parameters of the stack. Between runs a new user or post is neutral.
