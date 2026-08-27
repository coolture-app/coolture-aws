# Coolture AWS Infrastructure

NOTE: The docker image of the database was changed to match the image used in coolture-core (with postgis installed). The migration V1 file was changed in order to verify, if postgis is installed.

## Project Structure

```
aws/
├── backend.yaml            # API Gateway, Lambda functions, Aurora database
├── frontend.yaml           # SSR Lambda, S3 static hosting, CloudFront CDN
├── auth.yaml               # Cognito User Pool and JWT configuration
├── lambdas/
│   ├── common/             # Shared Maven module (DB pool, DTOs, security, pagination, validation etc.)
│   ├── post-write/         # Handles all post & event-location write operations (POST, PATCH, DELETE)
│   ├── post-read/          # Handles all post & event-location read operations (GET)
│   └── ssr/                # Angular SSR Lambda
│   ├── users-list/         # Users list lambda (ai generated demo)
│   ├── user-create/        # User creation lambda (ai generated demo)
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

Start local PostgreSQL:

```bash
make up
```

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

#### Using HTTP Clients

List users:

```bash
curl http://localhost:3000/users
```

Create user:

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

Pagination:

```bash
curl http://localhost:3000/users?limit=5&offset=10
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
