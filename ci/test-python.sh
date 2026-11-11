#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

PYTHON="${PYTHON:-python3.13}"
PROJECTS=(
    aws/lambdas/authorizer
    aws/lambdas/migrations
    embedding-workers/post-embedding
    embedding-workers/user-embedding
)
venvs="$(mktemp -d)"

for project in "${PROJECTS[@]}"; do
    echo "==> $project"
    venv="$venvs/$(basename "$project")"
    "$PYTHON" -m venv "$venv"
    "$venv/bin/pip" install --quiet --disable-pip-version-check -r "$project/requirements-dev.txt"
    (cd "$project" && "$venv/bin/python" -m pytest -q tests)
done
