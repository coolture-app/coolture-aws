#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENVIRONMENT="${ENVIRONMENT:-dev}"
FRONTEND_REPOSITORY="${FRONTEND_REPOSITORY:-https://github.com/coolture-app/coolture-frontend.git}"
FRONTEND_REF="${FRONTEND_REF:-dev}"

if [[ $# -eq 0 || " $* " == *" frontend "* ]]; then
    export FRONTEND_DIR="$PWD/.frontend"
    rm -rf "$FRONTEND_DIR"
    git clone --quiet --depth 1 --branch "$FRONTEND_REF" "$FRONTEND_REPOSITORY" "$FRONTEND_DIR"
    (cd "$FRONTEND_DIR" && HUSKY=0 npm ci --no-audit --no-fund)
fi

exec scripts/deploy.sh "$ENVIRONMENT" "$@"
