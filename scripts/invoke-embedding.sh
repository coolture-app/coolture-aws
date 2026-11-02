#!/usr/bin/env bash
set -euo pipefail

# sam local can't resolve the template's stack imports, which leaves the database address empty.
# Linux Docker has no host.docker.internal by default.
exec sam local invoke "$1" --template-file "${TEMPLATE:-.aws-sam/embeddings/template.yaml}" \
    --env-vars recommender/embedding-workers/local-env.json \
    --add-host host.docker.internal:host-gateway --skip-pull-image "${@:2}"
