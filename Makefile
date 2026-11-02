.DEFAULT_GOAL := help

# Auto-detect Docker socket on macOS if DOCKER_HOST is not set
ifneq ($(wildcard $(HOME)/.docker/run/docker.sock),)
export DOCKER_HOST ?= unix://$(HOME)/.docker/run/docker.sock
endif

export AWS_DEFAULT_REGION ?= eu-north-1

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

COMPOSE_ALL := docker compose --profile rec --profile graph

up: ## Start PostgreSQL (migrated and seeded) and Garage
	docker compose up -d --build
	@docker compose wait migrate > /dev/null 2>&1; test "$$(docker compose ps -a --format '{{.ExitCode}}' migrate)" = 0 && echo "PostgreSQL ready"

down: ## Stop all local services
	$(COMPOSE_ALL) down

migrate: ## Apply new database migrations and the local seed
	docker compose run --rm migrate

reset-db: ## Reset PostgreSQL database and volumes
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

build-common: ## Build and install shared common module
	@echo "Building common module..."
	@mvn clean install -f aws/lambdas/common/pom.xml -q

build: build-common ## Build backend lambdas
	@sam build --template-file aws/backend.yaml --parallel --cached

test-python: ## Run Python Lambda unit tests (needs requirements-dev.txt installed)
	@for dir in embedding-workers/post-embedding embedding-workers/user-embedding; do \
		(cd $$dir && python3 -m pytest -q tests) || exit 1; \
	done

embed-posts: ## Embed posts missing/stale embeddings locally (ALL=1, POST_ID=<uuid>, DRY_RUN=1)
	@scripts/embed-posts.sh

require-embeddings-build:
	@test -f .aws-sam/embeddings/template.yaml || { echo "Missing .aws-sam/embeddings/template.yaml - run 'make build-embeddings' first."; exit 1; }

embed-interests: require-embeddings-build ## Embed interests missing vectors locally
	@echo '{"action":"embed-interests"}' | sam local invoke PostEmbeddingFunction --template-file .aws-sam/embeddings/template.yaml --event -

embed-users: require-embeddings-build ## Recompute user embeddings locally (same as the schedule in AWS)
	@echo '{}' | sam local invoke UserEmbeddingFunction --template-file .aws-sam/embeddings/template.yaml --event -

local: build ## Start local API on localhost:3000
	@echo "API: http://localhost:3000"
	@echo "DB: host.docker.internal:5430"
	@docker rm -f $$(docker ps -q --filter ancestor=public.ecr.aws/lambda/java:21-rapid-x86_64) 2>/dev/null || true
	@sam local start-api --template-file .aws-sam/build/template.yaml --warm-containers EAGER --skip-pull-image

validate: ## Validate SAM templates
	@sam validate --template-file aws/backend.yaml
	@sam validate --template-file aws/frontend.yaml
	@sam validate --template-file aws/auth.yaml
	@sam validate --template-file embedding-workers/template.yaml

deploy-backend: ## Deploy backend to AWS (dev, arm64) - needs mgmt-stack-networking (management stack) + auth deployed
	@scripts/deploy.sh dev backend

deploy-frontend: ## Build ../coolture-frontend and deploy SSR + static assets to AWS (dev) - needs backend deployed
	@scripts/deploy.sh dev frontend

deploy-auth: ## Deploy auth (Cognito + optional-auth authorizer) to AWS (dev)
	@scripts/deploy.sh dev auth

build-embeddings: ## Build embedding workers for local runs (x86_64; post-embedding image needs Docker)
	@sam build --template-file embedding-workers/template.yaml --build-dir .aws-sam/embeddings

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

dev: up build local ## Full dev setup

status: ## Show stack status
	@aws cloudformation describe-stacks --stack-name coolture-backend-dev --query 'Stacks[0].StackStatus' 2>/dev/null || echo "Not deployed"
