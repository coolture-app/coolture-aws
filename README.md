# Coolture backend

The API, database, photo storage, recommender and event scraper of Coolture: Java Lambdas behind API Gateway, PostgreSQL
(Aurora on AWS), S3, and Python workers that embed posts and users for the feed. The web app is
[coolture-frontend](https://github.com/coolture-app/coolture-frontend).

## Run locally

The whole app on your machine, with about 120 real events from trojmiasto.pl, three demo users with their own tastes,
and a guest. It works on Windows (Git Bash or PowerShell), WSL, Linux and macOS.

### 1. Install the tools, once

| Tool | Windows | Linux, WSL, macOS |
|---|---|---|
| Docker | [Docker Desktop](https://docs.docker.com/desktop/) | Docker Engine with the compose plugin, or Docker Desktop |
| Git | `winget install Git.Git` (the Makefile runs its recipes in Git's bash) | package manager |
| make | `winget install ezwinports.make` | package manager (`build-essential` on Ubuntu) |
| [mise](https://mise.jdx.dev) | `winget install jdx.mise` | `curl https://mise.run` piped to `sh` |

Then, in this folder and in `../coolture-frontend`:

```bash
mise trust && mise install    # Java 21 (sets JAVA_HOME), Maven, Python, the SAM CLI, Node 24, Hurl
make doctor                   # checks the tools, Docker, ports and disk, and says what to fix
```

mise is optional: tools installed another way at the versions in `mise.toml` pass `make doctor` too. On WSL, keep the
repositories on WSL's own disk (`~/...`), not under `/mnt/c`, or builds are slow.

### 2. Start it

```bash
make geo-index                # once, first: the geocoder's index of Poland, about 40 minutes and 4 GB of memory
make dev                      # terminal 1: database, storage, migrations, build, then the API on :3000
make seed                     # terminal 2: realistic data. It waits for the API, so start it right away
cd ../coolture-frontend && npm install && npm start    # terminal 3: http://localhost:4200
```

`make seed` skips whatever is already done:

1. Scrapes a snapshot of trojmiasto.pl into the gitignored `scraper/snapshot/`, if there's none yet. About 12 minutes, once.
2. Loads it through the API as `coolture_bot`, with every date moved forward so all events are ahead. About 5 minutes.
3. Embeds interests and posts. The first run builds the embedding image (3.5 GB, about 10 minutes).
4. Lets the demo users react to posts close to their interests, then computes their vectors, so their feeds are personal.

The first run takes about 30 minutes. Later runs take a minute and add nothing. How the snapshot is picked and loaded
is in [scraper/README.md](scraper/README.md#snapshot-and-seed).

**The geocoder**, Photon with an index of Poland, serves the post form's place search and locates the scraper's
events. `make geo-index` builds it once, with 3 GB of disk, alone because of the memory. Then `make up` starts it on
`:2322` with the AWS instance's 1 GB, and `ng serve` sends the app's `/geo` to it, as CloudFront does on AWS. Without it
the form works with no suggestions, and the scraper stops and says so. `make geo-index` again refreshes the data.

### 3. Act as someone

The frontend's development build has a **DEV** pill at the bottom left:

| Who | What for |
|---|---|
| Gość (default) | A visitor with no account |
| Anna, Jan, Ola | Regular users with different interests: concerts and clubs; theatre and film; art, running and food |
| Bot | The author of the imported events |
| local_dev | The admin |

Outside the browser, the same users are a header away, see [Who you are](#who-you-are).

### Stop, restart, start over

| Command | Does |
|---|---|
| Ctrl+C in terminal 1 | Stops the API. If its containers stay behind, as happens on Windows, `make stop-lambdas` |
| `make local` | Rebuilds and restarts only the API, after a code change |
| `make stop` | Stops everything. Data stays |
| `make reset-db` | Deletes the database and the stored photos. Then `make up && make seed`, about 5 minutes. Keeps the geocoder's index |
| `make clean-all` | Removes all containers, data, the geocoder's index, images, builds and `.venv`. Keeps the snapshot |

Seeded events turn into past events over the following weeks. `make reset-db && make up && make seed` moves them ahead
again. Delete `scraper/snapshot/` first for fresh events.

### When something fails

Run `make doctor` first. Then:

| Symptom | Fix |
|---|---|
| `make up`: "... was changed after it was applied" | A migration was edited, or this branch has different ones: `make reset-db && make up` |
| `make up`: a container name "is already in use" | Containers of an older copy of the stack. `make doctor` names the command that stops them |
| `make build`: "invalid target release: 21" | Java isn't 21. `mise install`, or point `JAVA_HOME` at a JDK 21 |
| `make` from PowerShell: "'grep' is not recognized" | Git for Windows is missing |
| Photos or uploads fail with 403 | The API was started without the Garage keys. Use `make local`, not `sam local` by hand |
| `make seed` waits for the API | Start `make dev` or `make local`. It waits up to 15 minutes |

## Working with the API

Locally the API is on `http://localhost:3000`. On AWS the same routes are behind `/api`, which CloudFront strips.

### Who you are

On AWS, a Cognito token says who you are. Most routes need one, and the public ones (lists, posts, profiles) take it if
it's there. Locally there's no login, and headers say who you are instead. AWS ignores them.

| Headers | Locally you are |
|---|---|
| none | `local_dev`, an admin |
| `X-User-Id: <uuid>`, `X-User-Name: <username>` | That user, a regular one. `GET /auth/me` creates a user it doesn't know yet |
| the above and `X-User-Role: COOLTURE_ADMIN` | That user, as an admin |
| `X-Anonymous: true` | A guest |

The seed users' ids are in [`api/local.env`](api/local.env).

### Contracts, docs and examples

| | What | Where |
|---|---|---|
| **Contract** | Shapes, parameters and examples (OpenAPI), and the rules a schema can't say | `aws/lambdas/<area>/contracts/`. Today: [post-read](aws/lambdas/post-read/contracts/README.md), the feed, lists and map |
| **Docs** | Every contract in the browser, with a client to try requests | `make api-docs`, then http://localhost:8081. Add an `X-User-Id` header to act as someone |
| **Flows** | Runnable examples of how the API is used: who may do what, uploading a photo, a post's life | [`api/`](api/README.md). `make api-test` runs them all as a smoke test |

A contract changes first, in its own small pull request, and `ContractTest` checks that responses still match it. The
frontend generates its types from it with `npm run api-types`. Endpoints without a contract yet get one when they're next
changed.

## Repository

```
aws/
├── auth.yaml, backend.yaml,    # Stacks: Cognito; API Gateway, Lambdas, Aurora, media bucket, embedding queues;
│   geocoder.yaml,              #   the Photon instance; SSR Lambda, static files, CloudFront;
│   frontend.yaml, waf.yaml     #   the IP allow-list in front of CloudFront
└── lambdas/
    ├── common/                 # Shared Maven module in every Java Lambda: DB pool, DTOs, security, S3, paging
    ├── post-read/, post-write/ # Posts, the feed, the map, event locations. post-read holds the contract
    ├── comment-read/, comment-write/, media/, user/
    ├── authorizer/             # Python: lets a route take a Cognito token optionally
    ├── migrations/             # Python: applies migrations/ on AWS, and locally in make up
    └── ssr/                    # Server rendering of the frontend
api/                            # Request flows (Hurl) and the seed users' ids
db/                             # Local Postgres image (PostGIS, pgvector) and the local seed
garage/                         # Local S3 storage
geocoder/                       # Photon's image and photon.sh, run the same locally and on the AWS instance
migrations/                     # SQL migrations, including the recommender's rec_ tables
recommender/                    # Embedding workers and the recommender, see its README
scraper/                        # trojmiasto.pl scraper, snapshot and seed, see its README
scripts/                        # deploy.sh, doctor.sh, embed-posts.sh, geo-publish.sh
```

`make build` builds and installs `common` before the Lambdas, and rebuilds every Lambda when `common` changes.
`db/local-dev-seed.sql` runs only locally: migrations never hold users or demo data.

## Tests

| Command | Runs |
|---|---|
| `mvn test -f aws/lambdas/<module>/pom.xml` | A Java module's unit tests, after `make build-common`. `make build` runs them too |
| `make test-post-read-db` | `post-read`'s database tests, against `make up` |
| `make test-python` | The Python Lambdas: embedding workers, migrations, authorizer |
| `make test-scraper` | The scraper |
| `make rec-test` | The recommender worker, in its container |
| `make api-test` | The request flows in `api/`, against `make dev` and `make seed` |

## Deploying to AWS

Account-level stacks live in the separate management stack (`~/Projects/aws/management-stack`):

- `mgmt-stack-networking[-<env>]`: VPC, private subnets, route table and S3 gateway endpoint. Coolture imports it, so it
  must exist before `backend` and `embeddings`, and be deleted after them.
- `account`: IAM roles and groups. Deploy as `AWS_PROFILE=deployer CFN_ROLE_ARN=arn:aws:iam::<ACCOUNT_ID>:role/cloudformation-execution scripts/deploy.sh dev`.

Deploy in this order, because later stacks import earlier ones' exports, and delete in reverse:
`make deploy-auth` → `make deploy-backend` → `make deploy-embeddings` → `make deploy-geocoder` → `make deploy-waf` →
`make deploy-frontend`, or `make deploy-all`.

| Stack | Template | Imports from |
|---|---|---|
| `coolture-auth-<env>` | `aws/auth.yaml` | |
| `coolture-backend-<env>` | `aws/backend.yaml` | networking (VPC, subnets), auth (authorizers, Cognito) |
| `coolture-embeddings-<env>` | `recommender/embedding-workers/template.yaml` | networking, backend (queue ARN, DB endpoint, security group) |
| `coolture-geocoder-<env>` | `aws/geocoder.yaml` | networking (VPC, subnets) |
| `coolture-waf-<env>` (us-east-1) | `aws/waf.yaml` | |
| `coolture-frontend-<env>` | `aws/frontend.yaml` | backend (API domain), geocoder (VPC origin, if deployed), waf (web ACL ARN, passed by `deploy.sh`) |

**Only listed IP addresses can reach the site.** The WAF owns two IP sets, `coolture-<env>-allowed-ipv4` and `-ipv6`,
created empty. Add addresses in the AWS console under WAF & Shield → IP sets (region "Global (CloudFront)"), as CIDRs such
as `203.0.113.7/32`, before testing a fresh deploy. The addresses never live in code.

**The network has no internet access,** to keep the fixed cost at zero: private subnets only, no NAT and no interface
endpoints. VPC Lambdas reach Aurora and S3 (a free gateway endpoint) and nothing else, so anything needing the internet
or another AWS API runs outside the VPC. Embedding requests go through S3 for that reason, see
[recommender/README.md](recommender/README.md#embeddings).

### The geocoder

The post form's place search calls `/geo` on the site. CloudFront strips `/geo` and sends it through a
[VPC origin](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-vpc-origins.html) to
Photon on a `t4g.micro` in a private subnet, about $7 a month. The instance has no public address and only CloudFront
reaches it. It gets everything from the stack's bucket through the S3 gateway endpoint.

Before the first deploy, **attach an internet gateway to the VPC** in the networking stack (an `InternetGateway` and a
`VPCGatewayAttachment`). CloudFront requires one for a VPC origin, though no route uses it, so nothing in the VPC gains
internet access. It costs nothing. The instance also installs Java from Amazon Linux's package mirror, an S3 bucket, so
if the S3 gateway endpoint's policy is ever narrowed, it must still allow `al2023-repos-<region>-de612dc2` and the
geocoder's bucket.

Then:

```bash
make deploy-geocoder          # the instance, its bucket and the VPC origin. The VPC origin takes up to 15 minutes
make geo-index                # once, locally, if not done yet: the index of Poland
make geo-publish ENV=dev      # uploads Photon and the index, then reboots the instance, which loads them
make deploy-frontend          # adds /geo to CloudFront, now that the geocoder stack exists
```

To refresh the data, run `make geo-index` and `make geo-publish` again, every few months or before a release. The instance
loads a new index on reboot and keeps its ARN, which the VPC origin points at. To remove the geocoder, deploy the
frontend without it first: `NO_GEOCODER=1 make deploy-frontend`, then `make delete-geocoder`.
