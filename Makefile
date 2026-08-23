.DEFAULT_GOAL := help

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

up: ## Start PostgreSQL
	docker compose up -d
	@sleep 3
	@docker compose exec postgres pg_isready -U coolture_admin > /dev/null && echo "PostgreSQL ready"

down: ## Stop PostgreSQL
	docker compose down

logs: ## Tail PostgreSQL logs
	docker compose logs -f postgres

clean: ## Clean build artifacts
	@rm -rf .aws-sam
	@for dir in aws/lambdas/*/; do \
		[ -f "$$dir/pom.xml" ] && (cd "$$dir" && mvn clean -q 2>/dev/null) || true; \
	done

build: ## Build backend lambdas
	@sam build --template-file aws/backend.yaml --parallel --cached

local: build ## Start local API on localhost:3000
	@echo "API: http://localhost:3000"
	@echo "DB: host.docker.internal:5430"
	@sam local start-api --template-file .aws-sam/build/template.yaml --warm-containers EAGER

validate: ## Validate SAM templates
	@sam validate --template-file aws/backend.yaml
	@sam validate --template-file aws/frontend.yaml
	@sam validate --template-file aws/auth.yaml

deploy-backend: build validate ## Deploy backend to AWS (dev)
	@sam deploy --config-env backend-dev

deploy-frontend: validate ## Deploy frontend to AWS (dev)
	@sam build --template-file aws/frontend.yaml
	@sam deploy --config-env frontend-dev

deploy-auth: validate ## Deploy auth to AWS (dev)
	@sam deploy --config-env auth-dev --template-file aws/auth.yaml

deploy-all: deploy-backend deploy-auth deploy-frontend ## Deploy all stacks (dev)

delete-backend: ## Delete backend stack
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
