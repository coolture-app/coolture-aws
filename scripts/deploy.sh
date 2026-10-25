#!/usr/bin/env bash
set -euo pipefail

ALL_STACKS=(auth backend embeddings waf frontend)
WAF_REGION=us-east-1

usage() {
    cat <<EOF
Usage: $0 [dev|staging|prod] [stack...]

Builds and deploys the stacks in dependency order. Stacks: ${ALL_STACKS[*]} (default: all)
Optional env: CFN_ROLE_ARN, CORS_ALLOWED_ORIGINS (default: localhost for dev + the CloudFront url), USER_EMBEDDING_SCHEDULE=true,
  FRONTEND_DIR (default ../coolture-frontend)
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

ENVIRONMENT="${1:-dev}"
shift || true
if [[ ! "$ENVIRONMENT" =~ ^(dev|staging|prod)$ ]]; then
    usage >&2
    exit 1
fi

requested=("$@")
[[ ${#requested[@]} -eq 0 ]] && requested=("${ALL_STACKS[@]}")
for stack in "${requested[@]}"; do
    [[ " ${ALL_STACKS[*]} " =~ " $stack " ]] || { usage >&2; exit 1; }
done

wants() {
    [[ " ${requested[*]} " =~ " $1 " ]]
}

cd "$(dirname "$0")/.."
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-eu-north-1}"
FRONTEND_DIR="${FRONTEND_DIR:-../coolture-frontend}"

if ! python3.13 -m pip --version > /dev/null 2>&1; then
    [[ -x .aws-sam/python3.13/bin/pip ]] || python3.13 -m venv .aws-sam/python3.13
    export PATH="$PWD/.aws-sam/python3.13/bin:$PATH"
fi

NETWORK_STACK=mgmt-stack-networking
[[ "$ENVIRONMENT" == dev ]] || NETWORK_STACK="mgmt-stack-networking-$ENVIRONMENT"

template_for() {
    case "$1" in
        auth) echo aws/auth.yaml ;;
        backend) echo aws/backend.yaml ;;
        embeddings) echo embedding-workers/template.yaml ;;
        waf) echo aws/waf.yaml ;;
        frontend) echo aws/frontend.yaml ;;
    esac
}

region_for() {
    [[ "$1" == waf ]] && echo "$WAF_REGION" || echo "$AWS_DEFAULT_REGION"
}

stack_output() {
    aws cloudformation describe-stacks --stack-name "$1" --region "${3:-$AWS_DEFAULT_REGION}" \
        --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text
}

frontend_url() {
    local url
    url="$(stack_output "coolture-frontend-$ENVIRONMENT" WebsiteUrl 2> /dev/null)" || true
    [[ "$url" == None ]] && url=""
    echo "$url"
}

cors_origins() {
    local origins="" url
    url="$(frontend_url)"
    [[ "$ENVIRONMENT" == dev ]] && origins="http://localhost:4200"
    [[ -n "$url" ]] && origins="${origins:+$origins,}$url"
    echo "${CORS_ALLOWED_ORIGINS:-${origins:-https://no-browser-origin.invalid}}"
}

overrides_for() {
    case "$1" in
        auth)
            echo "Environment=$ENVIRONMENT FrontendUrl=\"$(frontend_url)\""
            ;;
        backend)
            echo "Environment=$ENVIRONMENT BaseStackName=$NETWORK_STACK AuthStackName=coolture-auth-$ENVIRONMENT CorsAllowedOrigins=\"$(cors_origins)\""
            ;;
        embeddings)
            echo "Environment=$ENVIRONMENT BaseStackName=$NETWORK_STACK BackendStackName=coolture-backend-$ENVIRONMENT UserEmbeddingScheduleEnabled=${USER_EMBEDDING_SCHEDULE:-false}"
            ;;
        frontend)
            local web_acl
            web_acl="$(stack_output "coolture-waf-$ENVIRONMENT" WebAclArn "$WAF_REGION" 2> /dev/null)" || web_acl=""
            [[ "$web_acl" == None ]] && web_acl=""
            [[ -z "$web_acl" ]] && echo "WARNING: no coolture-waf-$ENVIRONMENT stack in $WAF_REGION - frontend deploys without WAF" >&2
            echo "Environment=$ENVIRONMENT BackendStackName=coolture-backend-$ENVIRONMENT WebAclArn=\"$web_acl\""
            ;;
    esac
}

build_frontend() {
    local api_endpoint cognito_domain cognito_client_id src dist
    api_endpoint="$(stack_output "coolture-backend-$ENVIRONMENT" ApiEndpoint)"
    api_endpoint="${api_endpoint%/}"
    cognito_domain="$(stack_output "coolture-auth-$ENVIRONMENT" HostedUiUrl)"
    cognito_client_id="$(stack_output "coolture-auth-$ENVIRONMENT" UserPoolClientId)"
    if [[ -z "$api_endpoint" || "$api_endpoint" == None || -z "$cognito_domain" || "$cognito_domain" == None || -z "$cognito_client_id" || "$cognito_client_id" == None ]]; then
        echo "Backend or auth stack outputs are missing - deploy auth and backend first." >&2
        exit 1
    fi

    FRONTEND_DIR="$(cd "$FRONTEND_DIR" && pwd)"
    src=".aws-sam/frontend-src-$ENVIRONMENT"
    dist="aws/lambdas/ssr/dist"
    echo "==> [frontend] ng build from $FRONTEND_DIR (SSR API: $api_endpoint, browser API: /api)"
    rm -rf "$src"
    mkdir -p "$src"
    (cd "$FRONTEND_DIR" && tar cf - --exclude=node_modules --exclude=dist --exclude=.angular --exclude=.git \
        src public angular.json package.json tsconfig*.json) | tar xf - -C "$src"
    ln -s "$FRONTEND_DIR/node_modules" "$src/node_modules"
    cat > "$src/src/environments/environment.ts" <<ENV
export const environment = {
  production: true,
  apiUrlServer: '$api_endpoint',
  apiUrlBrowser: '/api',
  cognito: {
    domain: '$cognito_domain',
    clientId: '$cognito_client_id',
  },
};
ENV
    (cd "$src" && npx ng build --configuration production)

    rm -rf "$dist"
    mkdir -p "$dist"
    cp -r "$src/dist/frontend/server" "$src/dist/frontend/browser" "$dist/"
}

publish_frontend_assets() {
    local stack="coolture-frontend-$ENVIRONMENT" bucket distribution browser
    bucket="$(stack_output "$stack" StaticAssetsBucketName)"
    distribution="$(stack_output "$stack" CloudFrontDistributionId)"
    browser="aws/lambdas/ssr/dist/browser"

    echo "==> [frontend] uploading static assets to s3://$bucket"
    aws s3 sync "$browser" "s3://$bucket" --exclude '*' \
        --include '*.js' --include '*.css' --include 'media/*' \
        --cache-control 'public,max-age=31536000,immutable'
    aws s3 sync "$browser" "s3://$bucket" --exclude '*' \
        --include 'i18n/*' --include 'favicon.ico' \
        --cache-control 'public,max-age=300'

    echo "==> [frontend] invalidating CloudFront $distribution"
    aws cloudfront create-invalidation --distribution-id "$distribution" --paths '/*' \
        --query 'Invalidation.Id' --output text
}

deploy_stack() {
    local stack="$1" template build_dir overrides
    template="$(template_for "$stack")"
    build_dir=".aws-sam/$stack-$ENVIRONMENT"
    overrides="$(overrides_for "$stack")"

    echo
    echo "==> [$stack] sam build"
    sam build --template-file "$template" --build-dir "$build_dir" --parallel \
        ${overrides:+--parameter-overrides "$overrides"}

    echo "==> [$stack] sam deploy"
    local deploy_args=(
        --stack-name "coolture-$stack-$ENVIRONMENT"
        --template-file "$build_dir/template.yaml"
        --region "$(region_for "$stack")"
        --capabilities CAPABILITY_IAM
        --no-confirm-changeset
        --no-fail-on-empty-changeset
    )
    [[ -n "${CFN_ROLE_ARN:-}" ]] && deploy_args+=(--role-arn "$CFN_ROLE_ARN")
    [[ "$stack" != waf ]] && deploy_args+=(--resolve-s3)
    [[ "$stack" == embeddings ]] && deploy_args+=(--resolve-image-repos)
    [[ -n "$overrides" ]] && deploy_args+=(--parameter-overrides "$overrides")
    sam deploy "${deploy_args[@]}"
}

stack_param() {
    local value
    value="$(aws cloudformation describe-stacks --stack-name "coolture-$1-$ENVIRONMENT" \
        --query "Stacks[0].Parameters[?ParameterKey=='$2'].ParameterValue" --output text 2> /dev/null)" || return 1
    [[ "$value" == None ]] && value=""
    echo "$value"
}

sync_frontend_url() {
    local url current
    url="$(frontend_url)"
    [[ -n "$url" ]] || return 0

    current="$(stack_param auth FrontendUrl)" || current="$url"
    [[ "$current" == "$url" ]] || deploy_stack auth

    if [[ -z "${CORS_ALLOWED_ORIGINS:-}" ]]; then
        current="$(stack_param backend CorsAllowedOrigins)" || current="$url"
        [[ "$current" == *"$url"* ]] || deploy_stack backend
    fi
}

if wants backend; then
    mvn -q clean install -f aws/lambdas/common/pom.xml
fi

for stack in "${ALL_STACKS[@]}"; do
    wants "$stack" || continue
    [[ "$stack" == frontend ]] && build_frontend
    deploy_stack "$stack"
    if [[ "$stack" == frontend ]]; then
        publish_frontend_assets
        sync_frontend_url
    fi
done

echo
echo "Deployed [${requested[*]}] to '$ENVIRONMENT'."
if wants frontend; then
    echo "Frontend: $(frontend_url)"
fi
