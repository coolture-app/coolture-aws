#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

PYTHON="${PYTHON:-$(command -v python3.13 || echo python)}"
PROJECTS=(
    aws/lambdas/authorizer
    aws/lambdas/migrations
    aws/lambdas/geo-proxy
    recommender/post-embedding
    recommender/ppr
    scraper
)
venvs="$(mktemp -d)"

for project in "${PROJECTS[@]}"; do
    echo "==> $project"
    venv="$venvs/$(basename "$project")"
    "$PYTHON" -m venv "$venv"
    python="$venv/bin/python"
    [[ -x "$python" ]] || python="$venv/Scripts/python.exe"
    "$python" -m pip install --quiet --disable-pip-version-check -r "$project/requirements-dev.txt"
    (cd "$project" && "$python" -m pytest -q tests)
done
