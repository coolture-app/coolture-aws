# Coolture AWS Infrastructure

NOTE: The docker image of the database was changed to match the image used in coolture-core (with postgis installed). The migration V1 file was changed in order to verify, if postgis is installed.

## Project Structure

```
aws/
├── base.yaml               # Network: VPC, 2 private subnets, route table, Lambda/DB security groups
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
├── template.yaml           # Imports network from base, queue ARN + DB endpoint from backend
├── post-embedding/         # Container image: SQS → all-mpnet-base-v2 → post_embeddings
└── user-embedding/         # Zip: every 30 min, weighted avg of post embeddings → user_embeddings
migrations/                 # PostgreSQL schema migrations
scripts/                    # Local helpers (embed-posts.sh)
samconfig.toml              # SAM deployment configuration
```

## Stacks & Deployment

Deploy order (later stacks import earlier stacks' exports): `make deploy-base` → `make deploy-auth` →
`make deploy-backend` → `make deploy-embeddings` → `make deploy-frontend` (or `make deploy-all`).
Delete in reverse order.

| Stack | Template | Imports from |
|---|---|---|
| `coolture-base-<env>` | `aws/base.yaml` | - |
| `coolture-auth-<env>` | `aws/auth.yaml` | - |
| `coolture-backend-<env>` | `aws/backend.yaml` | base (network), auth (Cognito client ID + issuer) |
| `coolture-embeddings-<env>` | `embedding-workers/template.yaml` | base (network), backend (queue ARN, DB endpoint/port) |
| `coolture-frontend-<env>` | `aws/frontend.yaml` | backend (API endpoint) |

`base.yaml` is built for minimum fixed cost: private subnets only, no Internet Gateway, no NAT, no interface
endpoints. VPC Lambdas can reach Aurora and S3 (free gateway endpoint, in `backend.yaml`) and nothing else.
Anything that needs the internet or another AWS API has to run outside the VPC.
Two subnets in two AZs because Aurora's DB subnet group requires it.

## Embedding Pipeline (recommendations)

Port of `coolture-core/{post,user}-embedding-worker` with SQS instead of RabbitMQ:

```
post-write (create, or update that changes title/description/tags)
   └─► PutObject s3://coolture-<env>-embedding-requests-<account>/posts/<postId>   (empty object)
          └─ S3 ObjectCreated notification ─► SQS coolture-<env>-post-embedding ─► PostEmbeddingFunction
                                                 └─ after 3 failed receives ─► ...-post-embedding-dlq (14 days)
PostEmbeddingFunction: SELECT title/description/tags FROM posts → all-mpnet-base-v2 → post_embeddings

EventBridge rate(30 minutes) ──► UserEmbeddingFunction ──► user_embeddings
```

- Split: queue, DLQ, requests bucket and the publisher live in `backend.yaml` / `post-write`; the two Python
  workers are the separate `embedding-workers/` stack and can be deployed, rebuilt or removed on their own.
- Why S3 in the middle: VPC Lambdas have no internet. Reaching SQS would need a paid interface endpoint
  (~$9/month) or NAT; the S3 **gateway** endpoint is free and S3 → SQS notifications are free.
  The gateway endpoint route is added to the private route table from the base stack.
- `post-write` writes after the DB commit; a failed write is only logged (same as the Spring publisher).
  With `Environment=local` the bucket env var is empty and publishing is skipped.
- The consumer reads the post from the DB at processing time, so duplicated/out-of-order S3 events still
  end with the latest content embedded. Deleted posts and `s3:TestEvent` are skipped. Objects expire after 1 day.
- `PostEmbeddingFunction` is a container image with CPU-only torch and the model baked in (needs Docker to build).
- `sam build` for `UserEmbeddingFunction` needs `python3.13` with `pip` on PATH (e.g. run it inside a 3.13 venv) or `--use-container`.

Locally, `sam local` emulates neither S3 notifications nor SQS triggers (and post-write skips the S3 write),
so embeddings are computed on demand against the docker Postgres (`make up`, `make migrate`, `make build-embeddings` first):

```bash
make embed-posts                     # posts with no embedding, or edited since it was computed
make embed-posts POST_ID=<uuid>      # one post
make embed-posts ALL=1               # every non-deleted post (e.g. after changing the model)
make embed-posts DRY_RUN=1           # print the generated SQS/S3 events, don't invoke
make embed-users                     # what the 30-min schedule does in AWS
```

`embed-posts` (`scripts/embed-posts.sh`) builds the same SQS-wrapped S3 events AWS delivers and runs
`sam local invoke PostEmbeddingFunction`, 50 posts per invocation (`BATCH_SIZE=`), and exits non-zero if any post fails.

Unit tests: `pip install -r embedding-workers/<worker>/requirements-dev.txt` then `make test-python`.

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
- **Local Dev Seed** (`db/local-dev-seed.sql`): Creates the `local_dev` user only in local Docker Postgres via `docker-compose.yml` and `make migrate`.

## Prerequisites

- AWS SAM CLI
- Docker
- Maven 3.x
- Java 21
- PostgreSQL client (optional, for running migrations)

## Local Development

### Setup

Start local PostgreSQL and local S3 (Garage):

```bash
make up
```

> `make up` automatically runs PostgreSQL (port `5430`), S3 Garage (port `3900`), and `s3-init` which provisions the `coolture-bucket` with CORS enabled for direct browser uploads.

Build Lambda functions:

```bash
make build
```

Run database migrations and local dev seed:

```bash
make migrate
```

### Running Local API

Start API Gateway locally on port 3000:

```bash
make local
```

The API will use these default connection parameters:

- Host: `host.docker.internal`
- Port: `5430`
- Database: `coolture`
- User: `coolture_admin`
- Password: `admin`

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

##### 2. Create User

```bash
curl -X POST http://localhost:3000/users \
  -H "Content-Type: application/json" \
  -d '{
    "username": "johndoe",
    "firstName": "John",
    "lastName": "Doe",
    "bio": "Software engineer"
  }'
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

> **Authentication**: On AWS Cloud, endpoints extract the user ID from the Cognito JWT token (`Authorization: Bearer <TOKEN>`). For **local development and testing**, you can pass `-H "X-User-Id: <USER_UUID>"` (e.g. the ID returned by `POST /users`).

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
| `AWS_REGION`               | `garage`                                                           | `eu-central-1` (or Lambda execution region)           |
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
