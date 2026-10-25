#!/usr/bin/env bash
set -euo pipefail

ALL_STACKS=(base auth backend embeddings frontend)

usage() {
    cat <<EOF
Usage: DB_PASSWORD=<secret> $0 [dev|staging|prod] [stack...]

Builds (sam build) and deploys (sam deploy) the stacks in dependency order.
Stacks: ${ALL_STACKS[*]} (default: all)

Environment:
  DB_PASSWORD   Aurora master password, required for backend and embeddings
                (8-128 chars, no / ' " @ or spaces). Pass the same value on every deploy.
  CONFIRM=1     Ask before executing each changeset
  FRONTEND_DIR  Angular app to build and serve (default: ../coolture-frontend)
  CORS_ALLOWED_ORIGINS  Comma-separated origins allowed to call the API directly (default: http://localhost:4200)
  ALLOWED_VIEWER_IPS    Comma-separated IPv4 addresses/CIDRs allowed to open the CloudFront site (default: public)
  USER_EMBEDDING_SCHEDULE=true  Enable the user-embedding schedule (default: disabled)
  AWS_PROFILE / AWS_DEFAULT_REGION are honoured (region defaults to eu-central-1)
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage
    exit 0
fi

ENVIRONMENT="${1:-dev}"
shift || true
if [[ ! "$ENVIRONMENT" =~ ^(dev|staging|prod)$ ]]; then
    echo "Unknown environment '$ENVIRONMENT'." >&2
    usage >&2
    exit 1
fi

requested=("$@")
[[ ${#requested[@]} -eq 0 ]] && requested=("${ALL_STACKS[@]}")
for stack in "${requested[@]}"; do
    if [[ ! " ${ALL_STACKS[*]} " =~ " $stack " ]]; then
        echo "Unknown stack '$stack'." >&2
        usage >&2
        exit 1
    fi
done

wants() {
    [[ " ${requested[*]} " =~ " $1 " ]]
}

cd "$(dirname "$0")/.."
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-eu-central-1}"

for tool in sam aws; do
    command -v "$tool" > /dev/null || { echo "Missing '$tool' on PATH." >&2; exit 1; }
done

if wants backend || wants embeddings; then
    if [[ ! "${DB_PASSWORD:-}" =~ ^[A-Za-z0-9._~!#%^*+-]{8,128}$ ]]; then
        echo "DB_PASSWORD must be set: 8-128 chars from [A-Za-z0-9._~!#%^*+-]." >&2
        exit 1
    fi
fi

if wants backend; then
    if [[ -x ./mvnw ]]; then
        MVN=./mvnw
    elif command -v mvn > /dev/null; then
        MVN=mvn
    else
        echo "Missing Maven (./mvnw or mvn) needed to build the shared common module." >&2
        exit 1
    fi
fi

FRONTEND_DIR="${FRONTEND_DIR:-../coolture-frontend}"
if wants frontend; then
    command -v npx > /dev/null || { echo "Missing 'npx' (Node.js) needed to build the frontend." >&2; exit 1; }
    [[ -d "$FRONTEND_DIR/node_modules" ]] || {
        echo "No node_modules in $FRONTEND_DIR - run 'npm ci' there first." >&2
        exit 1
    }
    FRONTEND_DIR="$(cd "$FRONTEND_DIR" && pwd)"
fi

if wants embeddings; then
    docker info > /dev/null 2>&1 || { echo "Docker is required to build the post-embedding image." >&2; exit 1; }
fi

if wants auth || wants backend || wants embeddings; then
    if ! python3.13 -m pip --version > /dev/null 2>&1; then
        command -v python3.13 > /dev/null || { echo "python3.13 is required to build the Python Lambdas." >&2; exit 1; }
        build_venv=".aws-sam/python3.13"
        if [[ ! -x "$build_venv/bin/pip" ]]; then
            echo "==> Creating $build_venv so sam build finds python3.13 with pip"
            python3.13 -m venv "$build_venv" || {
                echo "Could not create a python3.13 venv - install python3.13-venv." >&2
                exit 1
            }
        fi
        export PATH="$PWD/$build_venv/bin:$PATH"
    fi
fi

account="$(aws sts get-caller-identity --query Account --output text)" || {
    echo "AWS credentials are not valid - run 'aws configure' or 'aws sso login' first." >&2
    exit 1
}
echo "Deploying [${requested[*]}] to '$ENVIRONMENT' in account $account ($AWS_DEFAULT_REGION)"

template_for() {
    case "$1" in
        base) echo aws/base.yaml ;;
        auth) echo aws/auth.yaml ;;
        backend) echo aws/backend.yaml ;;
        embeddings) echo embedding-workers/template.yaml ;;
        frontend) echo aws/frontend.yaml ;;
    esac
}

frontend_url() {
    local url
    url="$(aws cloudformation describe-stacks --stack-name "coolture-frontend-$ENVIRONMENT" \
        --query "Stacks[0].Outputs[?OutputKey=='WebsiteUrl'].OutputValue" --output text 2> /dev/null)" || true
    [[ "$url" == None ]] && url=""
    echo "$url"
}

overrides_for() {
    case "$1" in
        auth)
            echo "Environment=$ENVIRONMENT FrontendUrl=\"$(frontend_url)\""
            ;;
        backend)
            echo "Environment=$ENVIRONMENT BaseStackName=coolture-base-$ENVIRONMENT AuthStackName=coolture-auth-$ENVIRONMENT DbPassword=$DB_PASSWORD CorsAllowedOrigins=\"${CORS_ALLOWED_ORIGINS:-http://localhost:4200}\""
            ;;
        embeddings)
            echo "Environment=$ENVIRONMENT BaseStackName=coolture-base-$ENVIRONMENT BackendStackName=coolture-backend-$ENVIRONMENT DbPassword=$DB_PASSWORD UserEmbeddingScheduleEnabled=${USER_EMBEDDING_SCHEDULE:-false}"
            ;;
        frontend)
            echo "Environment=$ENVIRONMENT BackendStackName=coolture-backend-$ENVIRONMENT AllowedViewerIps=\"${ALLOWED_VIEWER_IPS:-}\""
            ;;
    esac
}

stack_output() {
    aws cloudformation describe-stacks --stack-name "$1" \
        --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text
}

build_frontend() {
    local api_endpoint src dist
    api_endpoint="$(stack_output "coolture-backend-$ENVIRONMENT" ApiEndpoint)"
    api_endpoint="${api_endpoint%/}"
    if [[ -z "$api_endpoint" || "$api_endpoint" == None ]]; then
        echo "Backend stack coolture-backend-$ENVIRONMENT has no ApiEndpoint output - deploy backend first." >&2
        exit 1
    fi

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

sync_auth_frontend_url() {
    local stack="coolture-auth-$ENVIRONMENT" url current
    url="$(frontend_url)"
    current="$(aws cloudformation describe-stacks --stack-name "$stack" \
        --query "Stacks[0].Parameters[?ParameterKey=='FrontendUrl'].ParameterValue" --output text 2> /dev/null)" || return 0
    [[ "$current" == None ]] && current=""
    [[ -z "$url" || "$url" == "$current" ]] && return 0

    echo "==> [auth] adding $url to Cognito callback/logout URLs"
    aws cloudformation update-stack --stack-name "$stack" --use-previous-template \
        --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND \
        --parameters ParameterKey=Environment,UsePreviousValue=true \
                     ParameterKey=LocalAppUrls,UsePreviousValue=true \
                     ParameterKey=LogRetentionDays,UsePreviousValue=true \
                     "ParameterKey=FrontendUrl,ParameterValue=$url" > /dev/null
    aws cloudformation wait stack-update-complete --stack-name "$stack"
}

confirm_flag="--no-confirm-changeset"
[[ -n "${CONFIRM:-}" ]] && confirm_flag="--confirm-changeset"

deploy_stack() {
    local stack="$1"
    local config_env="$stack-$ENVIRONMENT"
    local template build_dir overrides
    template="$(template_for "$stack")"
    build_dir=".aws-sam/$config_env"
    overrides="$(overrides_for "$stack")"

    echo
    echo "==> [$stack] sam validate"
    sam validate --template-file "$template" --region "$AWS_DEFAULT_REGION"

    echo "==> [$stack] sam build"
    sam build --config-file "$PWD/samconfig.toml" --config-env "$config_env" \
        --template-file "$template" --build-dir "$build_dir" --parallel

    echo "==> [$stack] sam deploy"
    local deploy_args=(
        --config-file "$PWD/samconfig.toml"
        --config-env "$config_env"
        --stack-name "coolture-$stack-$ENVIRONMENT"
        --template-file "$build_dir/template.yaml"
        --region "$AWS_DEFAULT_REGION"
        --capabilities CAPABILITY_IAM
        --resolve-s3
        --no-fail-on-empty-changeset
        "$confirm_flag"
    )
    [[ "$stack" == embeddings ]] && deploy_args+=(--resolve-image-repos)
    [[ -n "$overrides" ]] && deploy_args+=(--parameter-overrides "$overrides")
    sam deploy "${deploy_args[@]}"
}

if wants backend; then
    echo "==> Installing shared common module"
    "$MVN" -q clean install -f aws/lambdas/common/pom.xml
fi

for stack in "${ALL_STACKS[@]}"; do
    wants "$stack" || continue
    [[ "$stack" == frontend ]] && build_frontend
    deploy_stack "$stack"
    [[ "$stack" == frontend ]] && publish_frontend_assets && sync_auth_frontend_url
done

echo
echo "Done. Stack outputs:"
for stack in "${ALL_STACKS[@]}"; do
    wants "$stack" || continue
    aws cloudformation describe-stacks --stack-name "coolture-$stack-$ENVIRONMENT" \
        --query 'Stacks[0].Outputs[].[OutputKey,OutputValue]' --output table || true
done
