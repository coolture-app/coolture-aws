# Coolture AWS Infrastructure


## Project Structure

```
aws/
├── backend.yaml            # API Gateway, Lambda functions, Aurora database
├── frontend.yaml           # SSR Lambda, S3 static hosting, CloudFront CDN
├── auth.yaml               # Cognito User Pool and JWT configuration
├── lambdas/                # Example lambda functions (ai generated)
│   ├── users-list/
│   ├── user-create/
│   └── ssr/                # Angular SSR Lambda
├── migrations/             # PostgreSQL schema migrations
└── samconfig.toml          # SAM deployment configuration
```

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
