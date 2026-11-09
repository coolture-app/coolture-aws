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
PY_REQUIREMENTS := scraper/requirements-dev.txt aws/lambdas/migrations/requirements-dev.txt \
	aws/lambdas/authorizer/requirements-dev.txt recommender/embedding-workers/post-embedding/requirements-dev.txt \
	recommender/embedding-workers/user-embedding/requirements-dev.txt

# The local Garage keys, the defaults in docker-compose.yml. Without them every upload and photo URL gets a 403.
GARAGE_ACCESS_KEY_ID ?= GK9f23fc115cfabe3da0a5bcb5
GARAGE_ACCESS_KEY_SECRET ?= 8fa745da69f465aff2fafb09ab290c6358e6e58c0ec3d3fc174d27f300688111
# host-gateway makes host.docker.internal work on Linux's Docker Engine too, not only on Docker Desktop.
SAM_LOCAL_ARGS := --template-file .aws-sam/build/template.yaml --skip-pull-image --add-host host.docker.internal:host-gateway \
	--parameter-overrides GarageAccessKeyId=$(GARAGE_ACCESS_KEY_ID) GarageAccessKeySecret=$(GARAGE_ACCESS_KEY_SECRET)
# The containers sam local starts for the API and the embedding workers. Ctrl+C doesn't always remove them on Windows.
LAMBDA_IMAGES := public.ecr.aws/lambda/java:21-rapid-x86_64 public.ecr.aws/lambda/python:3.13-rapid-x86_64 \
	postembeddingfunction:rapid-x86_64
EMBEDDINGS_TEMPLATE := .aws-sam/embeddings/template.yaml
# The post-embedding worker's image, which sam build tags from DockerTag in recommender/embedding-workers/template.yaml.
EMBEDDINGS_IMAGE := postembeddingfunction:python3.13-v1
SNAPSHOT_DIR := scraper/snapshot

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

COMPOSE_ALL := docker compose --profile rec --profile graph

up: ## Start PostgreSQL (migrated and seeded) and Garage
	docker compose up -d --build
	@docker compose wait migrate > /dev/null 2>&1; \
	if [ "$$(docker compose ps -a --format '{{.ExitCode}}' migrate)" = 0 ]; then echo "PostgreSQL ready"; else \
		echo; echo "Migrations failed:"; docker compose logs --no-log-prefix migrate 2>&1 | tail -n 3; echo; \
		echo "If a migration was edited, or this branch has different migrations than the database,"; \
		echo "start over with: make reset-db && make up   (deletes local data; make seed fills it again)"; \
		exit 1; fi

stop: stop-lambdas ## Stop the API, the workers and all local services; data stays
	@$(COMPOSE_ALL) down

down: stop ## Same as stop

stop-lambdas: ## Remove the containers sam local left behind
	@ids="$$(docker ps -aq $(foreach image,$(LAMBDA_IMAGES),--filter ancestor=$(image)))"; \
	if [ -n "$$ids" ]; then docker rm -f $$ids > /dev/null && echo "Removed $$(echo $$ids | wc -w) sam local containers"; fi

migrate: ## Apply new database migrations and the local seed
	docker compose run --rm migrate

reset-db: stop-lambdas ## Delete the local database and stored photos (then: make up && make seed)
	$(COMPOSE_ALL) down -v

logs: ## Tail PostgreSQL logs (SERVICE=worker for another service)
	$(COMPOSE_ALL) logs -f $(or $(SERVICE),postgres)

rec-up: ## Start the recommender (local SQS + worker) on top of `up`
	docker compose --profile rec up -d --build

rec-test: ## Run recommender tests in the worker container
	docker compose --profile rec run --rm worker python -m pytest -p no:cacheprovider -rs tests

clean: ## Clean build artifacts
	@rm -rf .aws-sam
	@for dir in aws/lambdas/*/; do \
		[ -f "$$dir/pom.xml" ] && (mvn clean -f "$$dir/pom.xml" -q 2>/dev/null) || true; \
	done

clean-all: stop-lambdas clean ## Remove everything local: containers, data, images (incl. the 3.5 GB one), builds, .venv. Keeps the snapshot
	$(COMPOSE_ALL) down -v --rmi all --remove-orphans
	@docker rmi -f $(LAMBDA_IMAGES) $(EMBEDDINGS_IMAGE) > /dev/null 2>&1 || true
	@rm -rf .venv
	@echo "Clean. The snapshot in $(SNAPSHOT_DIR) stays; delete it by hand to scrape a new one."

doctor: ## Check tools, Docker, ports and disk, and show what's running
	@EMBEDDINGS_IMAGE=$(EMBEDDINGS_IMAGE) SNAPSHOT_DIR=$(SNAPSHOT_DIR) scripts/doctor.sh

$(VENV_PY): $(PY_REQUIREMENTS)
	@[ -x "$(VENV_PY)" ] || { echo "Creating .venv ..."; $(PYTHON) -m venv .venv; }
	@$(VENV_PY) -m pip install -q --disable-pip-version-check $(foreach file,$(PY_REQUIREMENTS),-r $(file))
	@touch $(VENV_PY)

venv: $(VENV_PY) ## Create or update .venv, which the scripts and Python tests use

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

test-python: $(VENV_PY) ## Run the Python Lambdas' unit tests: embedding workers, migrations, authorizer
	@for dir in recommender/embedding-workers/post-embedding recommender/embedding-workers/user-embedding \
			aws/lambdas/migrations aws/lambdas/authorizer; do \
		echo "$$dir"; (cd $$dir && "$(CURDIR)/$(VENV_PY)" -m pytest -q -p no:cacheprovider tests) || exit 1; \
	done

test-scraper: $(VENV_PY) ## Run the scraper's unit tests
	@cd scraper && "$(CURDIR)/$(VENV_PY)" -m pytest -q -p no:cacheprovider tests

scrape: $(VENV_PY) ## Import trojmiasto.pl events live through the local API, needs `make local` (ARGS="--limit 20")
	@SCRAPER_USER_ID=$${SCRAPER_USER_ID:-00000000-0000-0000-0000-00000000b07a} $(VENV_PY) scraper/scrape.py $(ARGS)

scrape-snapshot: $(VENV_PY) ## Save about 120 events of every category, with photos, to scraper/snapshot (~12 min once; ALL=1 for all)
	@$(VENV_PY) scraper/scrape.py --snapshot $(SNAPSHOT_DIR) $(if $(ALL),--all,$(if $(SIZE),--size $(SIZE)))

$(SNAPSHOT_DIR)/complete:
	@echo "No snapshot yet, so scraping one first (about 12 minutes, once)."
	@$(MAKE) --no-print-directory scrape-snapshot

seed-events: $(VENV_PY) $(SNAPSHOT_DIR)/complete ## Load the snapshot through the local API as the bot, dates moved to come
	@$(VENV_PY) scraper/seed.py events --snapshot $(SNAPSHOT_DIR)

seed-reactions: $(VENV_PY) ## Demo users react to posts close to their interests (after embed-interests and embed-posts)
	@$(VENV_PY) scraper/seed.py reactions

seed: seed-events embed-interests embed-posts seed-reactions embed-users ## All realistic data: events, embeddings, reactions. Waits for the API
	@echo "Seeded. Run npm start in ../coolture-frontend and open http://localhost:4200"

.NOTPARALLEL: seed

test-post-read-db: build-common ## Run the post-read database tests against `make up`
	@DB_TEST=true DB_HOST=localhost mvn test -f aws/lambdas/post-read/pom.xml -Dtest=PostReadDbTest

embed-posts: $(if $(DRY_RUN),,require-embeddings-build) ## Embed posts missing/stale embeddings locally (ALL=1, POST_ID=<uuid>, DRY_RUN=1)
	@scripts/embed-posts.sh

# Built on first use, or when its image was removed. After changing a worker's code, run make build-embeddings again.
require-embeddings-build:
	@if [ ! -f $(EMBEDDINGS_TEMPLATE) ] || ! docker image inspect $(EMBEDDINGS_IMAGE) > /dev/null 2>&1; then \
		echo "Building the embedding workers. The first build downloads the model into a 3.5 GB image."; \
		$(MAKE) --no-print-directory build-embeddings; \
	fi

embed-interests: require-embeddings-build ## Embed interests missing vectors locally
	@out="$$(echo '{"action":"embed-interests"}' | sam local invoke PostEmbeddingFunction --template-file .aws-sam/embeddings/template.yaml --add-host host.docker.internal:host-gateway --event -)" && printf '%s\n' "$$out" && ! printf '%s\n' "$$out" | grep -q '"errorMessage"'

embed-users: require-embeddings-build ## Recompute user embeddings locally (same as the schedule in AWS)
	@out="$$(echo '{}' | sam local invoke UserEmbeddingFunction --template-file .aws-sam/embeddings/template.yaml --add-host host.docker.internal:host-gateway --event -)" && printf '%s\n' "$$out" && ! printf '%s\n' "$$out" | grep -q '"errorMessage"'

local: build stop-lambdas ## Start local API on localhost:3000 (Ctrl+C stops it; make stop if containers stay)
	@echo "API: http://localhost:3000"
	@echo "DB: host.docker.internal:5430"
	@sam local start-api $(SAM_LOCAL_ARGS) --warm-containers EAGER

validate: ## Validate SAM templates
	@sam validate --template-file aws/backend.yaml
	@sam validate --template-file aws/frontend.yaml
	@sam validate --template-file aws/auth.yaml
	@sam validate --template-file recommender/embedding-workers/template.yaml

deploy-backend: ## Deploy backend to AWS (dev, arm64) - needs mgmt-stack-networking (management stack) + auth deployed
	@scripts/deploy.sh dev backend

deploy-frontend: ## Build ../coolture-frontend and deploy SSR + static assets to AWS (dev) - needs backend deployed
	@scripts/deploy.sh dev frontend

deploy-auth: ## Deploy auth (Cognito + optional-auth authorizer) to AWS (dev)
	@scripts/deploy.sh dev auth

build-embeddings: ## Build embedding workers for local runs (x86_64; post-embedding image needs Docker)
	@sam build --template-file recommender/embedding-workers/template.yaml --build-dir .aws-sam/embeddings

deploy-embeddings: ## Deploy embedding workers to AWS (dev, arm64) - needs mgmt-stack-networking + backend deployed
	@scripts/deploy.sh dev embeddings

deploy: ## Build + deploy stacks via scripts/deploy.sh (ENV=dev STACKS="auth backend embeddings waf frontend")
	@scripts/deploy.sh $(or $(ENV),dev) $(STACKS)

deploy-waf: ## Deploy CloudFront WAF web ACL + allowed-IP sets to us-east-1 (dev) - ~$8/month + $0.60 per 1M requests
	@scripts/deploy.sh dev waf

deploy-all: ## Deploy all stacks (dev)
	@scripts/deploy.sh dev

delete-embeddings: ## Delete embedding workers stack
	@sam delete --stack-name coolture-embeddings-dev --no-prompts

delete-backend: ## Delete backend stack (delete embeddings first: it imports backend outputs)
	@sam delete --stack-name coolture-backend-dev --no-prompts

delete-frontend: ## Delete frontend stack
	@sam delete --stack-name coolture-frontend-dev --no-prompts

delete-auth: ## Delete auth stack
	@sam delete --stack-name coolture-auth-dev --no-prompts

logs-api: ## Tail backend logs
	@sam logs --stack-name coolture-backend-dev --tail

dev: up build local ## Database, storage, build and API in one terminal; then make seed in another

status: ## Show stack status
	@aws cloudformation describe-stacks --stack-name coolture-backend-dev --query 'Stacks[0].StackStatus' 2>/dev/null || echo "Not deployed"
