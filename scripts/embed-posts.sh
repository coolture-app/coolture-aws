#!/usr/bin/env bash
set -euo pipefail

TEMPLATE="${TEMPLATE:-.aws-sam/embeddings/template.yaml}"
BATCH_SIZE="${BATCH_SIZE:-50}"

psql_query() {
    docker compose exec -T postgres psql -U coolture_admin -d coolture -At -v ON_ERROR_STOP=1 -c "$1"
}

if [[ -n "${POST_ID:-}" ]]; then
    post_ids=("$POST_ID")
else
    stale_filter="AND (pe.post_id IS NULL OR p.last_modified_at > pe.updated_at)"
    [[ -n "${ALL:-}" ]] && stale_filter=""
    mapfile -t post_ids < <(psql_query "
        SELECT p.id
        FROM posts p
        LEFT JOIN post_embeddings pe ON pe.post_id = p.id
        WHERE p.status <> 'DELETED' AND p.deleted_at IS NULL $stale_filter
        ORDER BY p.created_at")
fi

if [[ ${#post_ids[@]} -eq 0 ]]; then
    echo "No posts need embedding."
    exit 0
fi

if [[ -z "${DRY_RUN:-}" && ! -f "$TEMPLATE" ]]; then
    echo "Missing $TEMPLATE - run 'make build-embeddings' first." >&2
    exit 1
fi

build_event() {
    local records=() id
    for id in "$@"; do
        records+=("{\"messageId\":\"local-$id\",\"eventSource\":\"aws:sqs\",\"body\":\"{\\\"Records\\\":[{\\\"eventSource\\\":\\\"aws:s3\\\",\\\"eventName\\\":\\\"ObjectCreated:Put\\\",\\\"s3\\\":{\\\"object\\\":{\\\"key\\\":\\\"posts/$id\\\"}}}]}\"}")
    done
    local IFS=,
    echo "{\"Records\":[${records[*]}]}"
}

echo "Embedding ${#post_ids[@]} post(s) in batches of $BATCH_SIZE..."
event_file="$(mktemp)"
trap 'rm -f "$event_file"' EXIT
failed=0

for ((i = 0; i < ${#post_ids[@]}; i += BATCH_SIZE)); do
    build_event "${post_ids[@]:i:BATCH_SIZE}" > "$event_file"

    if [[ -n "${DRY_RUN:-}" ]]; then
        cat "$event_file"
        continue
    fi

    result="$(sam local invoke PostEmbeddingFunction --template-file "$TEMPLATE" --event "$event_file")"
    echo "$result"
    if grep -q '"itemIdentifier"' <<< "$result"; then
        failed=1
    fi
done

if [[ $failed -ne 0 ]]; then
    echo "Some posts failed to embed (see itemIdentifier above)." >&2
    exit 1
fi
