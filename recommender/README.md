# Recommender

The Python side of the feed: Lambdas that embed posts and interests and turn them into user vectors. Everything here
reads the app's tables in `public` directly. Its own tables are prefixed `rec_`, only the recommender writes them, and
they're created by ordinary migrations in [`../migrations/`](../migrations/). How the feed uses the vectors is in
`post-read`'s [contract](../aws/lambdas/post-read/contracts/README.md).

| Path | What it does |
|---|---|
| `embedding-workers/post-embedding/` | Container image: embeds a post's title, description and tags, and interests, with `mmlw-roberta-base` |
| `embedding-workers/user-embedding/` | Blends a user's interests and interactions into their vector |
| `embedding-workers/template.yaml` | Their own stack, `coolture-embeddings-<env>`, deployed, rebuilt or removed on its own |

All commands below run from the repository root.

## Embeddings

```
post-write (create, or a change to title, description or tags)
   └─► PutObject s3://coolture-<env>-embedding-requests-<account>/posts/<postId>   (empty object)
          └─ S3 notification ─► SQS coolture-<env>-post-embedding ─► PostEmbeddingFunction ─► post_embeddings
                                   └─ after 3 failed receives ─► ...-post-embedding-dlq (14 days)

user (PUT /users/{userId}/interests)
   └─► PutObject s3://.../users/<userId> ─► SQS coolture-<env>-user-embedding ─► UserEmbeddingFunction (that user)
EventBridge schedule ──► UserEmbeddingFunction (every user) ──► user_embeddings
```

- **Why S3 in the middle:** VPC Lambdas have no internet. Reaching SQS would need a paid interface endpoint (~$9 a month)
  or NAT, while the S3 gateway endpoint and S3 → SQS notifications are free.
- The queues, DLQs and requests bucket are in `aws/backend.yaml`, and the publisher is in `common`. `post-write` and `user`
  publish after the database commit. A failed publish is only logged.
- Notifications usually arrive within seconds, sometimes a minute or more. The worker reads the post from the database
  when it runs, so duplicated or late events still embed the latest text. Deleted posts and `s3:TestEvent` are skipped.
  Request objects expire after a day.
- `{"action":"embed-interests"}` to `PostEmbeddingFunction` embeds every interest with no vector, or whose
  `embedding_text` or model changed. `scripts/deploy.sh` sends it after deploying this stack.
- The user schedule runs every 30 minutes, 08:00-15:59 Warsaw time, and is off unless you deploy with
  `USER_EMBEDDING_SCHEDULE=true`.
- Building `PostEmbeddingFunction` needs Docker: it's a container image with CPU-only torch and the model baked in.
  `UserEmbeddingFunction` needs `python3.13` with `pip` on the PATH, or `--use-container`.

**Locally** there are no S3 notifications or SQS triggers, and `post-write` skips the publish. `make seed` runs the
workers in order. One at a time:

```bash
make embed-interests                 # interests with no vector, or a changed text or model
make embed-posts                     # posts with no vector, another model's, or edited since
make embed-posts POST_ID=<uuid>      # one post
make embed-posts ALL=1               # every post that isn't deleted
make embed-posts DRY_RUN=1           # print the SQS/S3 events it would send
make embed-users                     # every user, as the schedule does on AWS. Also after picking interests
```

They build the workers on first use (`make build-embeddings`; run it again after changing a worker). `embed-posts`
(`scripts/embed-posts.sh`) wraps posts in the same events AWS delivers, 50 per invocation (`BATCH_SIZE=`), and exits
non-zero if any fails. `make test-python` runs the workers' unit tests; `DB_TEST=true make test-python` also runs
`user-embedding`'s queries against `make up` and leaves the database as it was.

### Changing the model

The model is `sdadas/mmlw-roberta-base`, pinned to a revision in `embedding-workers/post-embedding/Dockerfile`.
Vectors of different models are never compared: `UserEmbeddingFunction` reads only the current model's vectors, and the
feed gives a post and a user on different models a neutral affinity of `0.5`, as for a post with no vector.

1. Set the model in the `Dockerfile` and in both workers' `settings.py`. Keep it 768-dimensional, or add a migration for
   the vector columns.
2. Re-embed in this order, because user vectors are built from the other two: interests, posts, users.
3. Measure `AFFINITY_LOW` and `AFFINITY_HIGH` in `FeedConfig` again. They cover the cosines this model gives between
   users and events, with the midpoint at the median, so they belong to the model. Interests with a prefix, or users who
   like more posts, shift them too.

A new model name is what marks a vector as old. A new revision, or a change to how text is encoded, isn't visible that
way: locally, clear `interest_embeddings` and run `make embed-posts ALL=1`. There's no equivalent on AWS yet.

Locally: `make build-embeddings embed-interests embed-posts embed-users`. On AWS (untested: none of this has run on AWS
yet; replace `dev` for another environment):

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

# 3. Users.
aws lambda invoke --function-name coolture-dev-user-embedding --cli-binary-format raw-in-base64-out \
    --cli-read-timeout 310 --payload '{}' /dev/stdout
```

The listing goes through the public API, because Aurora is reachable only from inside the VPC, so it sees public posts
only. A friends-only or private post keeps its old vector until it's next edited: it stays neutral in the feed, and likes
of it don't count towards user vectors.

## Interests and user vectors

`GET /dicts/interests` returns the catalogue from migration V10 (`interest_categories` group `interests`), and `GET` and
`PUT /users/{userId}/interests` read and replace the caller's picks. `PUT` asks `UserEmbeddingFunction` to refresh that
user at once; locally, run `make embed-users`. `/auth/me` returns `interestsSelected`, and the frontend blocks the page
with the interest picker until a user has some.

Each pick counts with weight 1, and its category with `INTEREST_WEIGHT_CATEGORY`. A category's vector is the average of
its interests' vectors. Interests then share the user's vector with their interactions, less as the user engages more.
To use another decay formula, change `interest_share` in `embedding-workers/user-embedding/src/app.py`.

| Setting | Default | Where | Meaning |
|---|---|---|---|
| `INTERESTS_MIN_SELECTIONS`, `INTERESTS_MAX_SELECTIONS` | 3, 6 | `aws/backend.yaml` | Selection limits, also sent to the frontend |
| `INTEREST_WEIGHT_CATEGORY` | 0.25 | `embedding-workers/template.yaml` | A pick's category against the pick itself |
| `INTEREST_WEIGHT_MINIMUM` | 0.2 | `embedding-workers/template.yaml` | Lowest share of interests in the vector |
| `INTEREST_DECAY_SCALE` | 10 | `embedding-workers/template.yaml` | Engagement at which the share falls to about 37% |
| `ENGAGEMENT_WINDOW_DAYS` | 30 | `embedding-workers/template.yaml` | Days of interactions that count as engagement |
