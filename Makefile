.DEFAULT_GOAL := help

# Auto-detect Docker socket on macOS if DOCKER_HOST is not set
ifneq ($(wildcard $(HOME)/.docker/run/docker.sock),)
export DOCKER_HOST ?= unix://$(HOME)/.docker/run/docker.sock
endif

export AWS_DEFAULT_REGION ?= eu-north-1

# Recipes are bash. On Windows make would fall back to cmd.exe when started from PowerShell, so it takes Git's bash,
# never the bash.exe in System32, which is WSL's.
ifeq ($(OS),Windows_NT)
GIT_BASH := $(subst /mingw64/libexec/git-core,/bin/bash.exe,$(shell git --exec-path))
ifneq ($(GIT_BASH),)
SHELL := $(GIT_BASH)
endif
endif

# Windows has no python3 command, on any shell or in any venv.
PYTHON ?= $(if $(filter Windows_NT,$(OS)),python,python3)
# One virtual environment for the scripts and the Python tests, made on first use.
VENV_PY := .venv/$(if $(filter Windows_NT,$(OS)),Scripts/python.exe,bin/python)
PYTHON_PROJECTS := recommender/post-embedding recommender/ppr aws/lambdas/migrations aws/lambdas/authorizer \
	aws/lambdas/geo-proxy scraper
PY_REQUIREMENTS := scraper/requirements-dev.txt aws/lambdas/migrations/requirements-dev.txt \
	aws/lambdas/authorizer/requirements-dev.txt recommender/post-embedding/requirements-dev.txt \
	recommender/ppr/requirements-dev.txt

# The local Garage keys, the defaults in docker-compose.yml. Without them every upload and photo URL gets a 403.
GARAGE_ACCESS_KEY_ID ?= GK9f23fc115cfabe3da0a5bcb5
GARAGE_ACCESS_KEY_SECRET ?= 8fa745da69f465aff2fafb09ab290c6358e6e58c0ec3d3fc174d27f300688111
API_PORT ?= 3000
WARM ?= EAGER
# The ng serve that calls the local API, which CORS allows.
FRONTEND_PORT ?= 4200
# make api-docs serves the contracts here, and its "try it" calls the local API from that origin.
API_DOCS_PORT ?= 8081
# host-gateway makes host.docker.internal work on Linux's Docker Engine too, not only on Docker Desktop.
SAM_LOCAL_ARGS := --template-file .aws-sam/build/template.yaml --skip-pull-image --add-host host.docker.internal:host-gateway \
	--parameter-overrides GarageAccessKeyId=$(GARAGE_ACCESS_KEY_ID) GarageAccessKeySecret=$(GARAGE_ACCESS_KEY_SECRET) \
	CorsAllowedOrigins=http://localhost:$(FRONTEND_PORT),http://localhost:$(API_DOCS_PORT)
# The containers sam local starts for the API and the embedding workers. Ctrl+C doesn't always remove them on Windows.
LAMBDA_IMAGES := public.ecr.aws/lambda/java:21-rapid-x86_64 public.ecr.aws/lambda/python:3.13-rapid-x86_64 \
	postembeddingfunction:rapid-x86_64
EMBEDDINGS_TEMPLATE := .aws-sam/embeddings/template.yaml
# The post-embedding worker's image, which sam build tags from DockerTag in recommender/template.yaml.
EMBEDDINGS_IMAGE := postembeddingfunction:python3.13-v1
SNAPSHOT_DIR := scraper/snapshot
COMPOSE_ALL := docker compose --profile geo
GEOCODER_INDEX := coolture-geocoder-index
STACK_ENV := $(or $(ENV),dev)

# A "##" after a target lists it here, and a "##@" line starts a section. Keep the pipe: from PowerShell, make runs a
# line without one straight from Windows, where awk isn't on the PATH.
help: ## Show this list
	@grep -E '^(##@ |[a-zA-Z_-]+:.*## )' $(MAKEFILE_LIST) | awk 'BEGIN { FS = ":.*## " } \
		/^##@ / { printf "\n%s\n", substr($$0, 5); next } { printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2 }'

##@ Local stack

dev: up build local ## Database, storage, build and API in one terminal; then make seed in another

up: ## Start PostgreSQL (migrated and seeded) and Garage
	docker compose up -d --build
	@docker compose wait migrate > /dev/null 2>&1; \
	if [ "$$(docker compose ps -a --format '{{.ExitCode}}' migrate)" = 0 ]; then echo "PostgreSQL ready"; else \
		echo; echo "Migrations failed:"; docker compose logs --no-log-prefix migrate 2>&1 | tail -n 3; echo; \
		echo "If a migration was edited, or this branch has different migrations than the database,"; \
		echo "start over with: make reset-db && make up   (deletes local data; make seed fills it again)"; \
		exit 1; fi
	@if docker volume inspect $(GEOCODER_INDEX) > /dev/null 2>&1; then \
		docker compose --profile geo up -d --build photon && echo "Geocoder on http://localhost:2322"; \
	else echo "No geocoder index, so no place suggestions and no scraping. make geo-index builds it (about 40 minutes, once)"; fi

local: build stop-lambdas ## Start the local API on :3000 for ng serve on :4200 (API_PORT=, FRONTEND_PORT=; WARM=LAZY starts each function on first use; Ctrl+C stops it)
	@echo "API: http://localhost:$(API_PORT)"
	@echo "DB: host.docker.internal:5430"
	@sam local start-api $(SAM_LOCAL_ARGS) --port $(API_PORT) --warm-containers $(WARM)

stop: stop-lambdas ## Stop the API, the workers and all local services; data stays
	@$(COMPOSE_ALL) down

# Only this checkout's, the containers that mount its .aws-sam, so another clone's or worktree's API keeps running.
# A mount's path is as sam was given it, D:\..., D:/... or /d/... on Windows, so both sides lose the drive and their
# case. tr, because Git Bash's grep -iF aborts.
stop-lambdas: ## Remove the containers sam local left behind
	@here="$$(echo '$(CURDIR)' | tr A-Z a-z | sed -E 's#^([a-z]:|/[a-z])/#/#')"; \
	ids="$$(docker ps -aq $(foreach image,$(LAMBDA_IMAGES),--filter ancestor=$(image)))"; \
	[ -z "$$ids" ] || ids="$$(docker inspect --format '{{.Id}} {{range .Mounts}}{{.Source}} {{end}}' $$ids \
		| tr 'A-Z\134' 'a-z/' | sed -E 's# ([a-z]:|/[a-z])/# /#g' | grep -F " $$here/.aws-sam/" | cut -d ' ' -f 1)"; \
	if [ -n "$$ids" ]; then docker rm -f $$ids > /dev/null && echo "Removed $$(echo $$ids | wc -w) sam local containers"; fi

migrate: ## Apply new database migrations and the local seed
	docker compose run --rm migrate

reset-db: stop-lambdas ## Delete the local database and stored photos (then: make up && make seed)
	$(COMPOSE_ALL) down -v

logs: ## Tail PostgreSQL logs (SERVICE=photon for another service)
	$(COMPOSE_ALL) logs -f $(or $(SERVICE),postgres)

build-common: ## Build and install shared common module
	@echo "Building common module..."
	@mvn clean install -f aws/lambdas/common/pom.xml -q

build: build-common ## Build backend lambdas
	@# --cached keys on each function's own folder, so a change to common alone would reach none of them.
	@sum="$$(find aws/lambdas/common/src aws/lambdas/common/pom.xml -type f -exec sha1sum {} + | sort | sha1sum)"; \
	if [ "$$sum" != "$$(cat .aws-sam/common.sha1 2>/dev/null)" ]; then \
		echo "common changed, so every Lambda is rebuilt"; rm -rf .aws-sam/cache .aws-sam/build; \
	fi; \
	sam build --template-file aws/backend.yaml --parallel --cached && echo "$$sum" > .aws-sam/common.sha1

build-embeddings: ## Build the embedding worker and PageRank for local runs (x86_64; needs Docker and Python 3.13)
	@sam build --template-file recommender/template.yaml --build-dir .aws-sam/embeddings

geo-index: ## Build or refresh the geocoder's Poland index for make up and make geo-publish (about 40 minutes and 4 GB of memory)
	@docker volume create $(GEOCODER_INDEX) > /dev/null
	docker compose --profile geo build photon
	docker compose --profile geo run --rm photon-import
	docker compose --profile geo up -d --force-recreate photon
	@echo "Geocoder on http://localhost:2322"

doctor: ## Check tools, Docker, ports and disk, and show what's running
	@EMBEDDINGS_IMAGE=$(EMBEDDINGS_IMAGE) SNAPSHOT_DIR=$(SNAPSHOT_DIR) scripts/doctor.sh

$(VENV_PY): $(PY_REQUIREMENTS)
	@[ -x "$(VENV_PY)" ] || { echo "Creating .venv ..."; $(PYTHON) -m venv .venv; }
	@$(VENV_PY) -m pip install -q --disable-pip-version-check $(foreach file,$(PY_REQUIREMENTS),-r $(file))
	@touch $(VENV_PY)

venv: $(VENV_PY) ## Create or update .venv, which the scripts and Python tests use

clean: ## Clean build artifacts
	@rm -rf .aws-sam
	@for dir in aws/lambdas/*/; do \
		[ -f "$$dir/pom.xml" ] && (mvn clean -f "$$dir/pom.xml" -q 2>/dev/null) || true; \
	done

clean-all: stop-lambdas clean ## Remove everything local: containers, data, the geocoder index, images (incl. the 3.5 GB one), builds, .venv. Keeps the snapshot
	$(COMPOSE_ALL) down -v --rmi all --remove-orphans
	@docker volume rm -f $(GEOCODER_INDEX) > /dev/null
	@docker rmi -f $(LAMBDA_IMAGES) $(EMBEDDINGS_IMAGE) > /dev/null 2>&1 || true
	@rm -rf .venv
	@echo "Clean. The snapshot in $(SNAPSHOT_DIR) stays; delete it by hand to scrape a new one."

##@ Data

seed: seed-events embed-interests embed-posts derive-kinds seed-reactions seed-friends ppr ## All realistic data: events, embeddings, kinds, reactions, friends, PageRank scores. Waits for the API
	@echo "Seeded. Run npm start in ../coolture-frontend and open http://localhost:4200"

.NOTPARALLEL: seed

seed-events: $(VENV_PY) $(SNAPSHOT_DIR)/complete
	@$(VENV_PY) scraper/seed.py events --snapshot $(SNAPSHOT_DIR) $(if $(SIZE),--size $(SIZE))

$(SNAPSHOT_DIR)/complete:
	@echo "No snapshot yet, so scraping one first (about 30 minutes, once)."
	@$(MAKE) --no-print-directory scrape-snapshot

seed-reactions: $(VENV_PY) ## Demo users react to posts close to their interests (after embed-interests and embed-posts); RESET=1 takes theirs back first
	@$(VENV_PY) scraper/seed.py reactions $(if $(RESET),--reset)

seed-friends: $(VENV_PY)
	@$(VENV_PY) scraper/seed.py friends

# Built on first use, when its image was removed, or when the template or either function's code changed.
RECOMMENDER_SOURCES := recommender/template.yaml recommender/post-embedding/Dockerfile \
	recommender/post-embedding/src recommender/ppr/src
require-embeddings-build:
	@if [ ! -f $(EMBEDDINGS_TEMPLATE) ] \
		|| [ -n "$$(find $(RECOMMENDER_SOURCES) -type f -newer $(EMBEDDINGS_TEMPLATE) -not -path '*/__pycache__*')" ] \
		|| ! docker image inspect $(EMBEDDINGS_IMAGE) > /dev/null 2>&1; then \
		echo "Building the embedding worker and PageRank. The first build downloads the model into a 3.5 GB image."; \
		$(MAKE) --no-print-directory build-embeddings; \
	fi

# sam local invoke exits 0 when the function raises, so the output decides. The payload can't hold a comma.
invoke-worker = out="$$(echo '$(2)' | sam local invoke $(1) --template-file $(EMBEDDINGS_TEMPLATE) --skip-pull-image --add-host host.docker.internal:host-gateway --event -)" && printf '%s\n' "$$out" && ! printf '%s\n' "$$out" | grep -q '"errorMessage"'

embed-interests: require-embeddings-build ## Embed interests and kinds with no vector, or a changed text or model, locally (then derive-kinds if a kind changed)
	@$(call invoke-worker,PostEmbeddingFunction,{"action":"embed-interests"})

embed-posts: $(if $(DRY_RUN),,require-embeddings-build) ## Embed posts missing/stale embeddings locally (ALL=1, POST_ID=<uuid>, DRY_RUN=1)
	@scripts/embed-posts.sh

derive-kinds: require-embeddings-build ## Derive every post's kinds from its stored vector locally (after embed-interests)
	@$(call invoke-worker,PostEmbeddingFunction,{"action":"derive-kinds"})

ppr: require-embeddings-build ## Score every user's events with personalised PageRank locally (after embed-posts)
	@$(call invoke-worker,PprFunction,{})

# Finding out at the first event would leave a run half done.
require-geocoder:
	@curl -fs -m 5 -o /dev/null "$${SCRAPER_GEOCODER_URL:-http://localhost:2322}/status" || { \
		echo "The scraper locates events with the geocoder, which isn't answering at $${SCRAPER_GEOCODER_URL:-http://localhost:2322}."; \
		echo "make geo-index builds it once (about 40 minutes), make up starts it."; exit 1; }

scrape: $(VENV_PY) require-geocoder ## Import trojmiasto.pl events live through the local API, needs `make local` (ARGS="--limit 20")
	@SCRAPER_USER_ID=$${SCRAPER_USER_ID:-00000000-0000-0000-0000-00000000b07a} $(VENV_PY) scraper/scrape.py $(ARGS)

scrape-snapshot: $(VENV_PY) require-geocoder ## Save every listed event, with photos, to scraper/snapshot (~30 min once, resumable)
	@$(VENV_PY) scraper/scrape.py --snapshot $(SNAPSHOT_DIR)

##@ Tests

test-python: $(VENV_PY) ## Run the unit tests of every Python project: recommender, migrations, authorizer, geocoder proxy, scraper
	@for dir in $(PYTHON_PROJECTS); do \
		echo "$$dir"; (cd $$dir && "$(CURDIR)/$(VENV_PY)" -m pytest -q -p no:cacheprovider tests) || exit 1; \
	done

test-db: build-common $(VENV_PY) ## Run the database tests of posts, comments, users and PageRank against `make up`
	@DB_TEST=true DB_HOST=localhost mvn test -f aws/lambdas/post-read/pom.xml -Dtest=PostReadDbTest
	@DB_TEST=true DB_HOST=localhost AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_REGION=eu-central-1 mvn test -f aws/lambdas/post-write/pom.xml -Dtest=PostWriteDbTest
	@DB_TEST=true DB_HOST=localhost mvn test -f aws/lambdas/comment-read/pom.xml -Dtest=CommentReadDbTest
	@DB_TEST=true DB_HOST=localhost mvn test -f aws/lambdas/comment-write/pom.xml -Dtest=CommentWriteDbTest
	@DB_TEST=true DB_HOST=localhost mvn test -f aws/lambdas/user/pom.xml -Dtest=UserDbTest
	@cd recommender/ppr && DB_TEST=true "$(CURDIR)/$(VENV_PY)" -m pytest -q -p no:cacheprovider tests/test_app_db.py
	@cd recommender/post-embedding && DB_TEST=true "$(CURDIR)/$(VENV_PY)" -m pytest -q -p no:cacheprovider tests/test_app_db.py

api-test: ## Run the request flows in api/ against the local stack (after make dev and make seed)
	@command -v hurl > /dev/null || { echo "hurl not found: mise install, or see https://hurl.dev"; exit 1; }
	@hurl --test --jobs 1 --variables-file api/local.env api/*.hurl

# Every aws/lambdas/<area>/contracts folder shows up under its area's name.
api-docs: ## Browse the API contracts and try requests on http://localhost:8081 (Ctrl+C stops)
	@echo "API docs: http://localhost:$(API_DOCS_PORT)"
	@MSYS_NO_PATHCONV=1 docker run --rm -p $(API_DOCS_PORT):8080 \
		$(foreach dir,$(wildcard aws/lambdas/*/contracts),-v "$(CURDIR)/$(dir):/docs/$(word 3,$(subst /, ,$(dir))):ro") \
		scalarapi/api-reference:0.6.17

validate: ## Validate every SAM template
	@for template in aws/*.yaml recommender/template.yaml; do sam validate --template-file $$template || exit 1; done

##@ AWS

deploy: ## Build + deploy stacks via scripts/deploy.sh (ENV=dev STACKS="auth backend embeddings waf frontend")
	@scripts/deploy.sh $(STACK_ENV) $(STACKS)

deploy-all: ## Deploy all stacks (dev)
	@scripts/deploy.sh $(STACK_ENV)

deploy-auth: ## Deploy auth (Cognito + optional-auth authorizer) to AWS (dev)
	@scripts/deploy.sh $(STACK_ENV) auth

deploy-backend: ## Deploy backend to AWS (dev, arm64; FEED_WEIGHTS=ppr=0 sets the feed's weights) - needs mgmt-stack-networking (management stack) + auth deployed
	@scripts/deploy.sh $(STACK_ENV) backend

deploy-embeddings: ## Deploy the embedding worker and PageRank to AWS (dev, arm64; PPR_SCHEDULE=true turns PageRank's schedule on) - needs mgmt-stack-networking + backend deployed
	@scripts/deploy.sh $(STACK_ENV) embeddings

deploy-geocoder: ## Deploy the geocoder instance and its CloudFront VPC origin (dev) - ~$7/month, needs an internet gateway in the VPC
	@scripts/deploy.sh $(STACK_ENV) geocoder

deploy-scraper: ## Deploy the scheduled scraper (dev; SCRAPER_SCHEDULE=true to run it, SCRAPER_END_DATE to stop it) - needs auth, backend, geocoder
	@scripts/deploy.sh $(STACK_ENV) scraper

deploy-waf: ## Deploy CloudFront WAF web ACL + allowed-IP sets to us-east-1 (dev) - ~$8/month + $0.60 per 1M requests
	@scripts/deploy.sh $(STACK_ENV) waf

deploy-frontend: ## Build ../coolture-frontend and deploy SSR + static assets to AWS (dev) - needs backend deployed
	@scripts/deploy.sh $(STACK_ENV) frontend

geo-publish: ## Upload the geocoder and its index to AWS and restart the instance (ENV=dev), after make geo-index and deploy-geocoder
	@scripts/geo-publish.sh $(STACK_ENV)

scrape-aws: ## Start one run of the scheduled scraper on AWS now, without waiting for the schedule (ENV=dev)
	@aws lambda invoke --function-name coolture-$(STACK_ENV)-scraper --invocation-type Event --cli-binary-format raw-in-base64-out \
		--payload '{}' /dev/null > /dev/null && echo "Started. Its log: aws logs tail /aws/lambda/coolture-$(STACK_ENV)-scraper --follow"

# The WAF's stack is in us-east-1, the others in the default region.
status: ## Show every stack's status on AWS (ENV=dev)
	@for region in $(AWS_DEFAULT_REGION) us-east-1; do \
		aws cloudformation describe-stacks --region $$region --output text --query \
			"Stacks[?starts_with(StackName, 'coolture-') && ends_with(StackName, '-$(STACK_ENV)')].[StackName, StackStatus]" || exit 1; \
	done

# Each function by name: given only the stack, sam logs looks up the HTTP API's stage and fails. The AWS CLI on Windows
# ends its output with \r.
logs-api: ## Tail the backend's logs on AWS (ENV=dev)
	@sam logs --stack-name coolture-backend-$(STACK_ENV) --tail $$(aws cloudformation describe-stack-resources \
		--stack-name coolture-backend-$(STACK_ENV) --output text \
		--query "StackResources[?ResourceType=='AWS::Lambda::Function'].LogicalResourceId" | tr -d '\r' | xargs printf -- '--name %s ')

delete-embeddings: ## Delete the embedding worker and PageRank stack
	@sam delete --stack-name coolture-embeddings-$(STACK_ENV) --no-prompts

delete-backend: ## Delete backend stack (delete embeddings first: it imports backend outputs)
	@sam delete --stack-name coolture-backend-$(STACK_ENV) --no-prompts

delete-scraper: ## Delete the scheduled scraper's stack (its ledger bucket must be emptied first)
	@sam delete --stack-name coolture-scraper-$(STACK_ENV) --no-prompts

delete-geocoder: ## Delete geocoder stack (first delete-scraper, and NO_GEOCODER=1 make deploy-frontend: both import it)
	@sam delete --stack-name coolture-geocoder-$(STACK_ENV) --no-prompts

delete-frontend: ## Delete frontend stack
	@sam delete --stack-name coolture-frontend-$(STACK_ENV) --no-prompts

delete-auth: ## Delete auth stack
	@sam delete --stack-name coolture-auth-$(STACK_ENV) --no-prompts
