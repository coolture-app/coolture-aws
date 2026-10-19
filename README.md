# Coolture AWS Infrastructure

NOTE: The docker image of the database was changed to match the image used in coolture-core (with postgis installed). The migration V1 file was changed in order to verify, if postgis is installed.

## Project Structure

```
aws/
├── backend.yaml            # API Gateway, Lambda functions, Aurora database, S3 media bucket
├── frontend.yaml           # SSR Lambda, S3 static hosting, CloudFront CDN
├── auth.yaml               # Cognito User Pool and JWT configuration
├── lambdas/
│   ├── common/             # Shared Maven module (DB pool, DTOs, security, S3 storage, pagination, validation etc.)
│   ├── media/              # Media upload lifecycle (init, complete, get, delete via presigned S3 URLs)
│   ├── post-write/         # Handles all post & event-location write operations (POST, PATCH, DELETE)
│   ├── post-read/          # Handles all post & event-location read operations (GET)
│   ├── comment-write/      # Handles comment write operations (POST, PATCH, DELETE)
│   ├── comment-read/       # Handles comment read operations (GET)
│   ├── user/               # User profiles, avatar, relations (follow/block)
│   ├── ssr/                # Angular SSR Lambda
├── migrations/             # PostgreSQL schema migrations
└── samconfig.toml          # SAM deployment configuration
```

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

Run database migrations:

```bash
docker compose exec -T postgres psql -U coolture_admin -d coolture < migrations/V1__initial_schema.sql
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
sam local invoke UsersListFunction --template-file .aws-sam/build/template.yaml

sam local invoke UserCreateFunction \
  --template-file .aws-sam/build/template.yaml \
  --event events/create-user.json
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
