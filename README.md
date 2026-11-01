# Coolture AWS Infrastructure

NOTE: The docker image of the database was changed to match the image used in coolture-core (with postgis installed). The migration V1 file was changed in order to verify, if postgis is installed.

## Project Structure

```
aws/
├── auth.yaml               # Cognito User Pool and JWT configuration
├── backend.yaml            # API Gateway, Java Lambdas, Aurora, S3 media bucket, embedding queue + requests bucket
├── frontend.yaml           # SSR Lambda, S3 static hosting, CloudFront CDN
├── lambdas/
│   ├── common/             # Shared Maven module (DB pool, DTOs, security, S3 storage, pagination, validation etc.)
│   ├── media/              # Media upload lifecycle (init, complete, get, delete via presigned S3 URLs)
│   ├── post-write/         # Handles all post & event-location write operations (POST, PATCH, DELETE)
│   ├── post-read/          # Handles all post & event-location read operations (GET)
│   ├── comment-write/      # Handles comment write operations (POST, PATCH, DELETE)
│   ├── comment-read/       # Handles comment read operations (GET)
│   ├── user/               # User profiles, avatar, relations (follow/block)
│   └── ssr/                # Angular SSR Lambda
embedding-workers/          # Independent stack: Python embedding Lambdas (own template.yaml)
├── template.yaml           # Imports network from mgmt-stack-networking[-<env>], queue ARN + DB endpoint from backend
├── post-embedding/         # Container image: SQS → all-mpnet-base-v2 → post_embeddings
└── user-embedding/         # Zip: on schedule, blends interests and interactions → user_embeddings
migrations/                 # PostgreSQL schema migrations (app + recommender schema `rec`)
recommender/                # Recommender worker and AuraDB graph gateway (see recommender/README.md)
db/                         # Local Postgres image (PostGIS + pgvector) and local dev seed
scripts/                    # Local helpers (embed-posts.sh)
samconfig.toml              # SAM deployment configuration
```

## Prerequisites in the AWS account

The account-level stacks live in the separate management stack (`~/Projects/aws/management-stack`), not here:

- `mgmt-stack-networking[-<env>]`: VPC, private subnets, route table and S3 gateway endpoint. Coolture imports it through
  `BaseStackName=mgmt-stack-networking[-<env>]`, so it must exist before `backend` / `embeddings` and be deleted after them.
- `account`: IAM roles (`admin`, `deployer`, `developer`, `cloudformation-execution`) and groups. Deploy Coolture as an
  owner with `AWS_PROFILE=deployer CFN_ROLE_ARN=arn:aws:iam::<ACCOUNT_ID>:role/cloudformation-execution scripts/deploy.sh dev`.

## Stacks & Deployment

Deploy order (later stacks import earlier stacks' exports), after `mgmt-stack-networking[-<env>]` exists: `make deploy-auth` →
`make deploy-backend` → `make deploy-embeddings` → `make deploy-waf` → `make deploy-frontend` (or `make deploy-all`).
Delete in reverse order.

| Stack | Template | Imports from |
|---|---|---|
| `coolture-auth-<env>` | `aws/auth.yaml` | - |
| `coolture-backend-<env>` | `aws/backend.yaml` | mgmt-stack-networking[-<env>] (VPC, subnets), auth (authorizers, Cognito) |
| `coolture-embeddings-<env>` | `embedding-workers/template.yaml` | mgmt-stack-networking[-<env>], backend (queue ARN, DB endpoint/port, security group) |
| `coolture-waf-<env>` (us-east-1) | `aws/waf.yaml` | - |
| `coolture-frontend-<env>` | `aws/frontend.yaml` | backend (API domain), waf (web ACL ARN, passed by deploy.sh) |

### Restricting the site to specific IPs

The CloudFront WAF (`aws/waf.yaml`) owns two IP sets, `coolture-<env>-allowed-ipv4` and `-ipv6`, created empty. The
addresses never live in code: manage them in the AWS console under WAF & Shield → IP sets (region "Global
(CloudFront)"), entering CIDRs such as `203.0.113.7/32`. The restriction is always on: only listed addresses can open
the site or call `/api`, and right after the first deploy the sets are empty, so add your address before testing.

The network stack is built for minimum fixed cost: private subnets only, no Internet Gateway, no NAT, no interface
endpoints. VPC Lambdas can reach Aurora and S3 (free gateway endpoint) and nothing else.
Anything that needs the internet or another AWS API has to run outside the VPC.

## Embedding Pipeline (recommendations)

Port of `coolture-core/{post,user}-embedding-worker` with SQS instead of RabbitMQ:

```
post-write (create, or update that changes title/description/tags)
   └─► PutObject s3://coolture-<env>-embedding-requests-<account>/posts/<postId>   (empty object)
          └─ S3 ObjectCreated notification ─► SQS coolture-<env>-post-embedding ─► PostEmbeddingFunction
                                                 └─ after 3 failed receives ─► ...-post-embedding-dlq (14 days)
PostEmbeddingFunction: SELECT title/description/tags FROM posts → all-mpnet-base-v2 → post_embeddings

user (PUT /users/{userId}/interests)
   └─► PutObject s3://coolture-<env>-embedding-requests-<account>/users/<userId>   (empty object)
          └─ S3 ObjectCreated notification ─► SQS coolture-<env>-user-embedding ─► UserEmbeddingFunction (that user)
                                                 └─ after 3 failed receives ─► ...-user-embedding-dlq (14 days)
EventBridge schedule ──► UserEmbeddingFunction (every user) ──► user_embeddings
```

Invoking `PostEmbeddingFunction` with `{"action":"embed-interests"}` embeds interests with no vector, or whose
`embedding_text` or model changed, into `interest_embeddings`.
`scripts/deploy.sh` does this after deploying the embeddings stack. Locally use `make embed-interests`.

The schedule is every 30 minutes, 08:00-15:59 Warsaw time, and stays off unless you deploy with `USER_EMBEDDING_SCHEDULE=true`.

- Split: queues, DLQs and requests bucket live in `backend.yaml`, the publisher in `common`; the two Python
  workers are the separate `embedding-workers/` stack and can be deployed, rebuilt or removed on their own.
- Why S3 in the middle: VPC Lambdas have no internet. Reaching SQS would need a paid interface endpoint
  (~$9/month) or NAT; the S3 **gateway** endpoint is free and S3 → SQS notifications are free.
  The gateway endpoint and its route on the private route table belong to the base stack.
- `post-write` and `user` write after the DB commit; a failed write is only logged (same as the Spring publisher).
- S3 delivers notifications typically within seconds, sometimes a minute or longer.
  With `Environment=local` the bucket env var is empty and publishing is skipped.
- The consumer reads the post from the DB at processing time, so duplicated/out-of-order S3 events still
  end with the latest content embedded. Deleted posts and `s3:TestEvent` are skipped. Objects expire after 1 day.
- `PostEmbeddingFunction` is a container image with CPU-only torch and the model baked in (needs Docker to build).
- `sam build` for `UserEmbeddingFunction` needs `python3.13` with `pip` on PATH (e.g. run it inside a 3.13 venv) or `--use-container`.

Locally, `sam local` emulates neither S3 notifications nor SQS triggers (and post-write skips the S3 write),
so embeddings are computed on demand against the docker Postgres (`make up` and `make build-embeddings` first):

```bash
make embed-posts                     # posts with no embedding, or edited since it was computed
make embed-posts POST_ID=<uuid>      # one post
make embed-posts ALL=1               # every non-deleted post (e.g. after changing the model)
make embed-posts DRY_RUN=1           # print the generated SQS/S3 events, don't invoke
make embed-users                     # what the schedule does in AWS
```

`embed-posts` (`scripts/embed-posts.sh`) builds the same SQS-wrapped S3 events AWS delivers and runs
`sam local invoke PostEmbeddingFunction`, 50 posts per invocation (`BATCH_SIZE=`), and exits non-zero if any post fails.

Unit tests: `pip install -r embedding-workers/<worker>/requirements-dev.txt` then `make test-python`.

## Interest onboarding

`GET /dicts/interests` returns the catalogue from migration V10 (`interest_categories` group `interests`), and `GET`/`PUT /users/{userId}/interests` read and replace the caller's selection.
`UserEmbeddingFunction` alone computes the user's vector. `PUT` asks it to refresh that user right away, and the schedule refreshes everyone.
Each picked interest counts with weight 1, and its category with `INTEREST_WEIGHT_CATEGORY`; a category's vector is the average of its interests' vectors.
The interests then share the vector with the user's interactions, less as the user engages more.
Locally there are no S3 notifications: run `make embed-users` after picking interests.
`/auth/me` returns `interestsSelected`. The frontend shows a user without a selection a blocking overlay on top of the page, and `/interests` is for later edits.

| Setting | Default | Where | Meaning |
|---|---|---|---|
| `INTERESTS_MIN_SELECTIONS`, `INTERESTS_MAX_SELECTIONS` | 3, 6 | `aws/backend.yaml` | selection limits, also sent to the frontend |
| `INTEREST_WEIGHT_CATEGORY` | 0.25 | `embedding-workers/template.yaml` | weight of a pick's category against the pick itself |
| `INTEREST_WEIGHT_MINIMUM` | 0.2 | `embedding-workers/template.yaml` | lowest share of interests in the user's vector |
| `INTEREST_DECAY_SCALE` | 10 | `embedding-workers/template.yaml` | engagement at which the share falls to about 37% |
| `ENGAGEMENT_WINDOW_DAYS` | 30 | `embedding-workers/template.yaml` | days of interactions that count as engagement |

To use another decay formula, change `interest_share` in `embedding-workers/user-embedding/src/app.py`.

## Shared Common Module (`com.coolture:common:1.0.0`)

The `common` module (`aws/lambdas/common/`) is a shared Maven library that eliminates code duplication across all Lambdas by providing central domain DTOs, database connection pooling (HikariCP), JWT security helpers, and pagination utilities. It is not a standalone Lambda; instead, it is packaged directly into each Lambda's shaded uber-jar at build time via Maven dependency:

```xml
<dependency>
    <groupId>com.coolture</groupId>
    <artifactId>common</artifactId>
    <version>1.0.0</version>
</dependency>
```

You can build and install it locally using `make build-common` (note that `make build` automatically builds `common` first before building all backend Lambdas).

## Authentication & Authorization

The architecture uses a dual-mode authorization model: strictly enforced Cognito JWT in AWS, and frictionless header-based mocking locally.

### 1. Cloud (AWS) – Amazon Cognito
- **API Gateway Enforcement**: `aws/backend.yaml` configures `CognitoAuthorizer` as the `DefaultAuthorizer` on `HttpApi` (backed by the Cognito User Pool defined in `aws/auth.yaml`).
- **Protected Routes**: Require `Authorization: Bearer <jwt_token>`. API Gateway validates signatures, issuers, and expiry before invoking the Lambda.
- **Public Routes**: Explicitly set to `Auth: { Authorizer: NONE }` (e.g., `GET /posts`, `GET /users`, `GET /media/{id}`, `GET /actuator/health`).
- **Identity Extraction**: Lambdas use [`SecurityContext.from(event)`](aws/lambdas/common/src/main/java/com/coolture/common/security/SecurityContext.java) to parse user ID (`sub`) and claims from `event.requestContext.authorizer.jwt.claims`.

### 2. Local Development – Frictionless Mocking
When `ENVIRONMENT=local` (set automatically in `make local`):
- **Zero-Config Auth**: If no auth headers are provided, requests automatically resolve to the default mock user `local_dev` (`00000000-0000-0000-0000-000000000001`). You can curl protected endpoints without tokens.
- **Switch Users**: Pass `-H "X-User-Id: <uuid>"` to simulate requests from any specific user ID.
- **Anonymous Access**: Pass `-H "X-Anonymous: true"` to test guest mode.
- *(Security: In AWS environments, `X-User-Id` and `X-Anonymous` headers are strictly ignored and fallback is disabled).*

### 3. Database Isolation (Local vs. AWS)
- **Production Migrations** (`migrations/*.sql`): Strictly schema DDL (tables, indexes, extensions). No mock users are present, ensuring clean AWS deployments.
- **Local Dev Seed** (`db/local-dev-seed.sql`): Creates the `local_dev` user and a small demo dataset (3 users, 6 posts, follows, likes) only in local Docker Postgres, applied by the `migrate` compose service after the migrations.

## Prerequisites

- AWS SAM CLI
- Docker
- Maven 3.x
- Java 21
- Python 3.13 (`sam build` of the Python functions)

## Local Development

### Setup

Start local PostgreSQL and local S3 (Garage):

```bash
make up
```

> `make up` runs PostgreSQL (port `5430`), S3 Garage (port `3900`), `s3-init` which provisions the `coolture-bucket` with CORS enabled for direct browser uploads, and `migrate`, which applies `migrations/` with the same runner as on AWS (`aws/lambdas/migrations`) and then `db/local-dev-seed.sql`. It returns once the database is migrated and seeded.

After adding a migration, apply it without restarting anything (already applied files are skipped):

```bash
make migrate
```

Start the recommender (local SQS + worker) and run its tests, see [recommender/README.md](recommender/README.md):

```bash
make rec-up
make rec-test
```

### Running Local API

Build the Lambdas and start API Gateway locally on port 3000:

```bash
make local
```

`make dev` does `make up` and `make local` in one go.

The API will use these default connection parameters:

- Host: `host.docker.internal`
- Port: `5430`
- Database: `coolture`
- User: `coolture_admin`
- Password: `admin`

### Running the frontend against the local API

In `../coolture-frontend`, `npm install` and `npx ng serve` serve the app on `http://localhost:4200`. Its development environment points at `http://localhost:3000`, where `make local` serves the routes without the `/api` prefix that CloudFront strips on AWS. There is no local login: pages that need no login work, and authenticated calls are easiest with curl and `X-User-Id` (see Authentication & Authorization).

### Testing the API

#### Using SAM CLI

Invoke functions directly:

```bash
sam local invoke UserFunction --template-file .aws-sam/build/template.yaml

sam local invoke PostReadFunction --template-file .aws-sam/build/template.yaml
```

Generate test events:

```bash
sam local generate-event apigateway http-api-proxy > events/test-event.json
```

#### User Operations (`user`)

##### 1. Search Users (with cursor pagination and avatars)

```bash
curl "http://localhost:3000/users?q=john&limit=20" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001"
```

##### 2. Create User (provisioned on first `GET /auth/me`)

Users are never created directly. The first authenticated `GET /auth/me` creates the profile from the token claims
(`sub`, username, `given_name`, `family_name`). Locally the claims come from the `X-User-*` headers:

```bash
curl http://localhost:3000/auth/me \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000002" \
  -H "X-User-Name: johndoe"
```

##### 3. Get User Profile by ID

```bash
curl "http://localhost:3000/users/00000000-0000-0000-0000-000000000001" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000002"
```

##### 4. Get User Profile by Username

```bash
curl "http://localhost:3000/users/by-username/johndoe"
```

##### 5. Update Profile (PATCH - Caller must be owner)

```bash
curl -X PATCH "http://localhost:3000/users/00000000-0000-0000-0000-000000000001" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001" \
  -d '{
    "firstName": "Johnny",
    "bio": "Updated bio"
  }'
```

##### 6. Set Profile Image (PUT - Caller must own both media files)

```bash
curl -X PUT "http://localhost:3000/users/00000000-0000-0000-0000-000000000001/profile-image" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001" \
  -d '{
    "fullMediaId": "11111111-1111-1111-1111-111111111111",
    "thumbnailMediaId": "22222222-2222-2222-2222-222222222222"
  }'
```

##### 7. Remove Profile Image (DELETE)

```bash
curl -X DELETE "http://localhost:3000/users/00000000-0000-0000-0000-000000000001/profile-image" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001"
```

---

### Posts - Read Operations (`post-read`)

#### 1. Get Feed (with filters & cursor pagination)

Basic feed:

```bash
curl "http://localhost:3000/posts"
```

Feed with filters (text query, tags, sorting, radius):

```bash
curl "http://localhost:3000/posts?q=festiwal&tags=muzyka,koncert&type=OFFLINE&sortBy=POPULAR&limit=10"
```

Feed with geo-radius filter (latitude, longitude, radius in km):

```bash
curl "http://localhost:3000/posts?latitude=52.2297&longitude=21.0122&radiusKm=25.0"
```

Feed with cursor pagination:

```bash
curl "http://localhost:3000/posts?cursor=eyJpZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMCIsImNyZWF0ZWRBdCI6IjIwMjYtMDgtMjRUMTI6MDA6MDBaIn0&limit=20"
```

#### 2. Get Post Details

```bash
curl "http://localhost:3000/posts/11111111-1111-1111-1111-111111111111"
```

#### 3. Get Map Marks (Bounding Box)

```bash
curl "http://localhost:3000/posts/map?leftUpper.latitude=52.3&leftUpper.longitude=20.9&rightBottom.latitude=52.1&rightBottom.longitude=21.2"
```

#### 4. Get Recommendations (Requires Cognito JWT)

```bash
curl "http://localhost:3000/posts/recommendations?limit=10" \
  -H "Authorization: Bearer <COGNITO_JWT_TOKEN>"
```

#### 5. List Event Locations

```bash
curl "http://localhost:3000/event-locations?limit=20"
```

---

### Posts - Write Operations (`post-write`)

> **Authentication**: On AWS Cloud, endpoints extract the user ID from the Cognito JWT token (`Authorization: Bearer <TOKEN>`). For **local development and testing**, you can pass `-H "X-User-Id: <USER_UUID>"` (e.g. the ID you provisioned via `GET /auth/me`).

#### 1. Create Post (ONLINE)

```bash
curl -X POST "http://localhost:3000/posts" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001" \
  -d '{
    "title": "Warsztaty programowania online",
    "description": "Otwarte warsztaty z technologii chmurowych i AWS Serverless.",
    "eventUrl": "https://meet.google.com/abc-defg-hij",
    "startsAt": "2026-09-01T18:00:00Z",
    "endsAt": "2026-09-01T20:00:00Z",
    "tags": ["it", "aws", "serverless"],
    "type": "ONLINE",
    "visibility": "PUBLIC"
  }'
```

#### 2. Create Post (OFFLINE with Location & Media)

```bash
curl -X POST "http://localhost:3000/posts" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <COGNITO_JWT_TOKEN>" \
  -d '{
    "title": "Wystawa sztuki nowoczesnej",
    "description": "Wernisaż lokalnych artystów połączony z degustacją kawy.",
    "startsAt": "2026-09-15T19:00:00Z",
    "endsAt": "2026-09-15T22:00:00Z",
    "tags": ["sztuka", "wernisaz", "kultura"],
    "type": "OFFLINE",
    "visibility": "PUBLIC",
    "location": {
      "countryCode": "POL",
      "venueName": "Galeria Sztuki",
      "buildingNum": "12A",
      "street": "ul. Marszałkowska",
      "postalCode": "00-001",
      "city": "Warszawa",
      "coordinates": {
        "latitude": 52.2297,
        "longitude": 21.0122
      }
    },
    "mediaIds": ["22222222-2222-2222-2222-222222222222"],
    "coverMediaId": "22222222-2222-2222-2222-222222222222"
  }'
```

#### 3. Update Post (PATCH)

```bash
curl -X PATCH "http://localhost:3000/posts/11111111-1111-1111-1111-111111111111" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <COGNITO_JWT_TOKEN>" \
  -d '{
    "title": "Zaktualizowany tytuł wystawy",
    "description": "Nowy zaktualizowany opis wydarzenia.",
    "tags": ["sztuka", "premiera"]
  }'
```

#### 4. Delete Post (DELETE - Soft Delete)

```bash
curl -X DELETE "http://localhost:3000/posts/11111111-1111-1111-1111-111111111111" \
  -H "Authorization: Bearer <COGNITO_JWT_TOKEN>"
```

---

### Event Locations - Direct Management (`post-write`)

#### 1. Create Standalone Event Location

```bash
curl -X POST "http://localhost:3000/event-locations" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <COGNITO_JWT_TOKEN>" \
  -d '{
    "countryCode": "POL",
    "venueName": "Klub Muzyczny Stodoła",
    "buildingNum": "10",
    "street": "ul. Batorego",
    "postalCode": "02-591",
    "city": "Warszawa",
    "coordinates": {
      "latitude": 52.2156,
      "longitude": 21.0089
    }
  }'
```

#### 2. Update Event Location

```bash
curl -X PATCH "http://localhost:3000/event-locations/33333333-3333-3333-3333-333333333333" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <COGNITO_JWT_TOKEN>" \
  -d '{
    "venueName": "Nowa Nazwa Klubu",
    "street": "ul. Stefana Batorego 10"
  }'
```

#### 3. Delete Event Location

```bash
curl -X DELETE "http://localhost:3000/event-locations/33333333-3333-3333-3333-333333333333" \
  -H "Authorization: Bearer <COGNITO_JWT_TOKEN>"
```

---

### Media Upload & S3 Storage (`media`)

The media module provides a high-performance presigned URL upload flow:

1. **Client** requests upload initiation via `POST /media/uploads/init`.
2. **Backend** validates metadata, records `PENDING` state in PostgreSQL, and generates an AWS S3 presigned `PUT` URL (15-min expiry) with locked `Content-Type`.
3. **Client** uploads file binary directly to S3/Garage using `PUT <uploadUrl>` with required headers.
4. **Client** notifies backend via `POST /media/uploads/{mediaId}/complete` to transition status to `UPLOADED`.
5. **Client** requests media via `GET /media/{mediaId}` to obtain a time-limited presigned `GET` URL (1-hour expiry).

#### Switching Storage: Local Garage vs AWS S3

Switching between local testing and actual AWS S3 is fully controlled by environment variables:

| Environment Variable       | Local Testing (Default)                                            | AWS Cloud (Dev/Staging/Prod)                          |
| -------------------------- | ------------------------------------------------------------------ | ----------------------------------------------------- |
| `MEDIA_BUCKET_NAME`        | `coolture-bucket` (or set by `s3-init`)                            | `coolture-${Environment}-media` (from `backend.yaml`) |
| `S3_ENDPOINT_URL`          | `http://localhost:3900`                                            | _Unset_ (SDK automatically targets native AWS S3)     |
| `S3_PUBLIC_ENDPOINT_URL`   | `http://localhost:3900` (browser-reachable URL)                    | _Unset_ (defaults to AWS S3)                          |
| `AWS_REGION`               | `garage`                                                           | `eu-north-1` (or Lambda execution region)           |
| `GARAGE_ACCESS_KEY_ID`     | `GK9f23fc115cfabe3da0a5bcb5`                                       | _Unset_ (Lambda IAM execution role is used)           |
| `GARAGE_ACCESS_KEY_SECRET` | `8fa745da69f465aff2fafb09ab290c6358e6e58c0ec3d3fc174d27f300688111` | _Unset_ (Lambda IAM execution role is used)           |

- **Local S3 Garage**: Run `make up`. Garage runs on port `3900`, web UI on port `3920`. `s3-init` creates the bucket and configures CORS.
- **Native AWS S3**: Deploy with `make deploy-backend`. `backend.yaml` provisions `MediaBucket` (`coolture-${Environment}-media`) with CORS and attaches `S3CrudPolicy` to `MediaFunction`.

#### Media Endpoints (curl examples)

##### 1. Initiate Upload

```bash
curl -X POST "http://localhost:3000/media/uploads/init" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001" \
  -d '{
    "purpose": "EVENT_COVER",
    "mimeType": "image/jpeg",
    "sizeBytes": 204800,
    "fileName": "poster.jpg"
  }'
```

Response (`201 Created`):

```json
{
  "mediaId": "d3b07384-d113-4f90-a5a6-98124d9c4e23",
  "objectKey": "EVENT_COVER/00000000-0000-0000-0000-000000000001/a34ef124-....jpg",
  "uploadUrl": "http://localhost:3900/coolture-bucket/EVENT_COVER/...?X-Amz-Signature=...",
  "httpMethod": "PUT",
  "expiresAt": "2026-10-13T10:45:00Z",
  "requiredHeaders": {
    "Content-Type": "image/jpeg"
  }
}
```

##### 2. Upload Binary directly to S3 / Garage

```bash
curl -X PUT "<uploadUrl>" \
  -H "Content-Type: image/jpeg" \
  --data-binary "@/path/to/local/poster.jpg"
```

##### 3. Complete Upload

```bash
curl -X POST "http://localhost:3000/media/uploads/d3b07384-d113-4f90-a5a6-98124d9c4e23/complete" \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001" \
  -d '{}'
```

Response (`200 OK`):

```json
{
  "id": "d3b07384-d113-4f90-a5a6-98124d9c4e23",
  "purpose": "EVENT_COVER",
  "mimeType": "image/jpeg",
  "sizeBytes": 204800,
  "status": "UPLOADED",
  "url": "http://localhost:3900/coolture-bucket/EVENT_COVER/...?X-Amz-Signature=...",
  "createdAt": "2026-10-13T10:30:00Z"
}
```

##### 4. Get Media by ID

```bash
curl "http://localhost:3000/media/d3b07384-d113-4f90-a5a6-98124d9c4e23"
```

##### 5. Delete Media (Owned by caller)

```bash
curl -X DELETE "http://localhost:3000/media/d3b07384-d113-4f90-a5a6-98124d9c4e23" \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001"
```

Response (`204 No Content`). Removes object from S3 and marks record `DELETED`.
