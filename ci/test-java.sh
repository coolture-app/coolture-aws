#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

mvn -B -q install -f aws/lambdas/common/pom.xml

for pom in aws/lambdas/*/pom.xml; do
    [[ "$pom" == */common/* ]] && continue
    echo "==> $pom"
    mvn -B -q test -f "$pom"
done
