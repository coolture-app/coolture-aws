#!/usr/bin/env bash
# make doctor: checks what the local stack needs and shows what's running. Changes nothing.
set -uo pipefail

cd "$(dirname "$0")/.."
# The Makefile passes these. The defaults are for running the script by hand.
EMBEDDINGS_IMAGE="${EMBEDDINGS_IMAGE:-postembeddingfunction:python3.13-v1}"
SNAPSHOT_DIR="${SNAPSHOT_DIR:-scraper/snapshot}"

problems=0
ok() { printf '  [ok] %s\n' "$*"; }
bad() { printf '  [!!] %s\n' "$*"; problems=$((problems + 1)); }
warn() { printf '  [--] %s\n' "$*"; }
has() { command -v "$1" > /dev/null 2>&1; }
major() { grep -oE '[0-9]+(\.[0-9]+)?' | head -n 1 | cut -d. -f1; }
minor() { grep -oE '[0-9]+\.[0-9]+' | head -n 1; }

case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*) platform=windows ;;
    Darwin) platform=mac ;;
    *) if grep -qi microsoft /proc/version 2> /dev/null; then platform=wsl; else platform=linux; fi ;;
esac
if has mise; then install_hint='mise install'; else install_hint='see "Run locally" in README.md'; fi

echo "Tools ($platform)"

if ! has docker; then
    bad "docker: not found. Install Docker Desktop (Windows, Mac) or Docker Engine (Linux)"
elif ! docker info > /dev/null 2>&1; then
    bad "docker: installed, but the daemon isn't answering. Start Docker Desktop, or: sudo systemctl start docker"
elif ! docker compose version > /dev/null 2>&1; then
    bad "docker compose v2: not found (docker-compose v1 isn't enough)"
else
    ok "docker $(docker version --format '{{.Server.Version}}' 2> /dev/null), $(docker compose version --short 2> /dev/null | sed 's/^/compose /')"
fi

java_cmd=java
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_home_unix="$JAVA_HOME"
    has cygpath && java_home_unix="$(cygpath -u "$JAVA_HOME")"
    if [[ -x "$java_home_unix/bin/java" || -x "$java_home_unix/bin/java.exe" ]]; then
        java_cmd="$java_home_unix/bin/java"
    else
        bad "JAVA_HOME=$JAVA_HOME has no bin/java, and Maven uses it. Point it at a JDK 21 ($install_hint sets it)"
    fi
fi
if java_version="$("$java_cmd" -version 2>&1 | head -n 1)" && [[ "$java_version" == *version* ]]; then
    if [[ "$(echo "$java_version" | major)" == 21 ]]; then ok "java 21 ($java_cmd)"; else bad "java: $java_version. The Lambdas need 21 ($install_hint)"; fi
else
    bad "java: not found. Install JDK 21 ($install_hint)"
fi

if has mvn; then ok "maven $(mvn -v 2> /dev/null | head -n 1 | minor)"; else bad "mvn: not found ($install_hint)"; fi

if has sam; then ok "sam $(sam --version 2> /dev/null | minor)"; else bad "sam: not found. Install the AWS SAM CLI ($install_hint)"; fi

python_cmd=python3
[[ "$platform" == windows ]] && python_cmd=python
if has "$python_cmd" && py_version="$("$python_cmd" -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2> /dev/null)"; then
    if "$python_cmd" -c 'import sys; sys.exit(sys.version_info < (3, 10))'; then
        if "$python_cmd" -c 'import venv, ensurepip' 2> /dev/null; then ok "python $py_version"; else bad "python $py_version has no venv module. On Debian or Ubuntu: sudo apt install python3-venv"; fi
    else
        bad "python $py_version: needs 3.10 or later ($install_hint)"
    fi
else
    bad "python: not found ($install_hint)"
fi

if has node; then
    node_version="$(node --version)"
    if [[ "$(echo "$node_version" | major)" -ge 24 ]]; then ok "node $node_version"; else warn "node $node_version: the frontend expects 24 ($install_hint)"; fi
else
    warn "node: not found. Only the frontend needs it ($install_hint)"
fi

if has hurl; then ok "hurl $(hurl --version 2> /dev/null | head -n 1 | minor)"; else warn "hurl: not found. Only make api-test needs it ($install_hint)"; fi

echo
echo "Machine"

if [[ "$platform" == wsl && "$PWD" == /mnt/* ]]; then
    warn "the repository is on a Windows drive ($PWD). Docker and builds are much faster from WSL's own disk, for example ~/coolture"
fi

free_gb="$(df -Pk . 2> /dev/null | awk 'NR == 2 { printf "%d", $4 / 1048576 }')"
if [[ -n "$free_gb" && "$free_gb" -lt 15 ]]; then
    warn "${free_gb} GB free here. The first run needs about 10 GB (the embedding image alone is 3.5 GB)"
elif [[ -n "$free_gb" ]]; then
    ok "${free_gb} GB free"
fi

clash=''
if has docker && docker info > /dev/null 2>&1; then
    for name in coolture-postgres coolture-s3garage; do
        project="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$name" 2> /dev/null)"
        if [[ -n "$project" && "$project" != coolture-aws && "$clash" != "$project" ]]; then
            clash="$project"
            bad "containers of an older compose project '$project' hold this stack's names and ports, so make up would clash. Stop them (data stays): docker compose -p $project down"
        fi
    done
fi

# Both addresses: ng serve listens on IPv6 only, Docker and sam local on IPv4.
port_open() { (exec 3<> "/dev/tcp/127.0.0.1/$1") 2> /dev/null || (exec 3<> "/dev/tcp/::1/$1") 2> /dev/null; }
running() { docker compose ps --status running --services 2> /dev/null | grep -qx "$1"; }
if [[ -z "$clash" ]]; then
    for entry in 5430:postgres:PostgreSQL 3900:s3garage:Garage 2322:photon:Photon; do
        IFS=: read -r port service label <<< "$entry"
        if port_open "$port" && ! running "$service"; then
            bad "port $port ($label) is taken by something else. Stop it, or another copy of this stack"
        fi
    done
fi

echo
echo "Now"

if has docker && docker info > /dev/null 2>&1; then
    services="$(docker compose ps --status running --services 2> /dev/null | xargs)"
    if [[ -n "$services" ]]; then ok "running: $services"; else warn "nothing running. Start with: make dev"; fi
fi

if port_open 3000; then ok "API on http://localhost:3000"; else warn "API not running (make local, or make dev)"; fi
if port_open 4200; then ok "frontend on http://localhost:4200"; else warn "frontend not running (npm start in ../coolture-frontend)"; fi
if ! has docker || ! docker volume inspect coolture-geocoder-index > /dev/null 2>&1; then
    warn "no geocoder index, so the post form has no place suggestions. make geo-index builds it (about 40 minutes, once)"
elif port_open 2322; then
    ok "geocoder on http://localhost:2322"
else
    warn "geocoder not running (make up starts it)"
fi

if [[ -f "$SNAPSHOT_DIR/posts.jsonl" ]]; then
    events="$(grep -c . "$SNAPSHOT_DIR/posts.jsonl")"
    if [[ -f "$SNAPSHOT_DIR/complete" ]]; then ok "snapshot: $events events"; else warn "snapshot: $events events, unfinished (make scrape-snapshot goes on)"; fi
else
    warn "no snapshot yet. make seed makes one first (about 12 minutes, once)"
fi

if running postgres; then
    counts="$(docker compose exec -T postgres psql -U coolture_admin -d coolture -At -F ' ' -c "
        SELECT count(*) FILTER (WHERE p.starts_at > now()), count(pe.post_id)
        FROM posts p LEFT JOIN post_embeddings pe ON pe.post_id = p.id
        WHERE p.deleted_at IS NULL" 2> /dev/null)"
    if [[ -n "$counts" ]]; then
        read -r upcoming embedded <<< "$counts"
        if [[ "$upcoming" -gt 0 ]]; then ok "database: $upcoming upcoming posts, $embedded embedded"; else warn "database: no upcoming posts. Run make seed"; fi
    fi
fi

if [[ -f .aws-sam/embeddings/template.yaml ]] && docker image inspect "$EMBEDDINGS_IMAGE" > /dev/null 2>&1; then
    ok "embedding workers built"
else
    warn "embedding workers not built yet (make seed builds them, 3.5 GB)"
fi

echo
if [[ "$problems" -gt 0 ]]; then
    echo "$problems problem(s) to fix first."
    exit 1
fi
echo "Ready. Run make dev, then make seed in another terminal."
