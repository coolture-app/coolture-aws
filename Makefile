.DEFAULT_GOAL := help

# Auto-detect Docker socket on macOS if DOCKER_HOST is not set
ifneq ($(wildcard $(HOME)/.docker/run/docker.sock),)
export DOCKER_HOST ?= unix://$(HOME)/.docker/run/docker.sock
endif

export AWS_DEFAULT_REGION ?= eu-central-1

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

up: ## Start PostgreSQL
	docker compose up -d --build
	@sleep 3
	@docker compose exec postgres pg_isready -U coolture_admin > /dev/null && echo "PostgreSQL ready"

down: ## Stop PostgreSQL
	docker compose down

migrate: ## Run database migrations
	@for f in migrations/*.sql; do \
		echo "Applying $$f..."; \
		docker compose exec -T postgres psql -U coolture_admin -d coolture < "$$f"; \
	done
	@[ -f db/local-dev-seed.sql ] && echo "Applying db/local-dev-seed.sql..." && docker compose exec -T postgres psql -U coolture_admin -d coolture < db/local-dev-seed.sql || true

reset-db: ## Reset PostgreSQL database and volumes
	docker compose down -v

logs: ## Tail PostgreSQL logs
	docker compose logs -f postgres

clean: ## Clean build artifacts
	@rm -rf .aws-sam
	@for dir in aws/lambdas/*/; do \
		[ -f "$$dir/pom.xml" ] && (./mvnw clean -f "$$dir/pom.xml" -q 2>/dev/null) || true; \
	done

build-common: ## Build and install shared common module
	@echo "Building common module..."
	@./mvnw clean install -f aws/lambdas/common/pom.xml -q

build: build-common ## Build backend lambdas
	@sam build --template-file aws/backend.yaml --parallel --cached

test-python: ## Run Python Lambda unit tests (needs requirements-dev.txt installed)
	@for dir in embedding-workers/post-embedding embedding-workers/user-embedding; do \
		(cd $$dir && python3 -m pytest -q tests) || exit 1; \
	done

embed-posts: ## Embed posts missing/stale embeddings locally (ALL=1, POST_ID=<uuid>, DRY_RUN=1)
	@scripts/embed-posts.sh

embed-users: ## Recompute user embeddings locally (same as the 30-min schedule in AWS)
	@test -f .aws-sam/embeddings/template.yaml || { echo "Missing .aws-sam/embeddings/template.yaml - run 'make build-embeddings' first."; exit 1; }
	@echo '{}' | sam local invoke UserEmbeddingFunction --template-file .aws-sam/embeddings/template.yaml --event -

local: build ## Start local API on localhost:3000
	@echo "API: http://localhost:3000"
	@echo "DB: host.docker.internal:5430"
	@docker rm -f $$(docker ps -q --filter ancestor=public.ecr.aws/lambda/java:21-rapid-x86_64) 2>/dev/null || true
	@sam local start-api --template-file .aws-sam/build/template.yaml --warm-containers EAGER --skip-pull-image

validate: ## Validate SAM templates
	@sam validate --template-file aws/base.yaml
	@sam validate --template-file aws/backend.yaml
	@sam validate --template-file aws/frontend.yaml
	@sam validate --template-file aws/auth.yaml
	@sam validate --template-file embedding-workers/template.yaml

deploy-base: ## Deploy network stack to AWS (dev) - first, others import from it
	@scripts/deploy.sh dev base

deploy-backend: ## Deploy backend to AWS (dev, arm64) - needs base + auth deployed
	@scripts/deploy.sh dev backend

deploy-frontend: ## Build ../coolture-frontend and deploy SSR + static assets to AWS (dev) - needs backend deployed
	@scripts/deploy.sh dev frontend

deploy-auth: ## Deploy auth (Cognito + optional-auth authorizer) to AWS (dev)
	@scripts/deploy.sh dev auth

build-embeddings: ## Build embedding workers for local runs (x86_64; post-embedding image needs Docker)
	@sam build --template-file embedding-workers/template.yaml --build-dir .aws-sam/embeddings

deploy-embeddings: ## Deploy embedding workers to AWS (dev, arm64) - needs base + backend deployed
	@scripts/deploy.sh dev embeddings

deploy: ## Build + deploy stacks via scripts/deploy.sh (ENV=dev STACKS="base auth backend embeddings waf frontend")
	@scripts/deploy.sh $(or $(ENV),dev) $(STACKS)

deploy-waf: ## Deploy CloudFront WAF web ACL to us-east-1 (dev) - ~$7/month + $0.60 per 1M requests
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

delete-base: ## Delete network stack (delete backend first: it imports base outputs)
	@sam delete --stack-name coolture-base-dev --no-prompts

logs-api: ## Tail backend logs
	@sam logs --stack-name coolture-backend-dev --tail

dev: up build local ## Full dev setup

status: ## Show stack status
	@aws cloudformation describe-stacks --stack-name coolture-backend-dev --query 'Stacks[0].StackStatus' 2>/dev/null || echo "Not deployed"
