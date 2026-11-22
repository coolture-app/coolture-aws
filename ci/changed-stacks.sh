#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ALL_STACKS="auth backend embeddings waf frontend"
FROM="${1:-}"
TO="${2:-HEAD}"

stacks_for() {
    case "$1" in
        scripts/deploy.sh) echo "$ALL_STACKS" ;;
        aws/auth.yaml | aws/lambdas/authorizer/*) echo auth ;;
        aws/frontend.yaml | aws/lambdas/ssr/*) echo frontend ;;
        aws/waf.yaml) echo waf ;;
        embedding-workers/*) echo embeddings ;;
        aws/backend.yaml | aws/lambdas/* | migrations/*) echo backend ;;
    esac
}

if [[ -z "$FROM" || "$FROM" =~ ^0+$ ]] || ! git cat-file -e "$FROM^{commit}" 2> /dev/null; then
    echo "$ALL_STACKS"
    exit 0
fi

git diff --name-only "$FROM" "$TO" | while read -r file; do
    stacks_for "$file"
done | tr ' ' '\n' | sed '/^$/d' | sort -u | xargs
