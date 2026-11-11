#!/usr/bin/env bash
set -euo pipefail

SAM_CLI_VERSION=1.144.0
MAVEN_VERSION=3.9.11
TOOLS_DIR="${RUNNER_TEMP:?run this on a GitHub Actions runner}"

add_to_path() {
    echo "$1" >> "${GITHUB_PATH:?}"
}

python3.13 -m pip install --quiet --disable-pip-version-check "aws-sam-cli==$SAM_CLI_VERSION"

if ! command -v aws > /dev/null; then
    curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-$(uname -m).zip" -o "$TOOLS_DIR/awscli.zip"
    unzip -q "$TOOLS_DIR/awscli.zip" -d "$TOOLS_DIR"
    "$TOOLS_DIR/aws/install" --install-dir "$TOOLS_DIR/aws-cli" --bin-dir "$TOOLS_DIR/bin"
    add_to_path "$TOOLS_DIR/bin"
fi

if ! command -v mvn > /dev/null; then
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" \
        | tar xz -C "$TOOLS_DIR"
    add_to_path "$TOOLS_DIR/apache-maven-$MAVEN_VERSION/bin"
fi
