#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

SECRETS_DIR="/Users/Shared/flowtree/secrets"

# Ensure the shared secret exists for ar-manager HMAC token generation.
# Both the controller and ar-manager containers mount this file read-only.
if [ ! -f "$SECRETS_DIR/shared-secret" ]; then
  echo "Generating ar-manager shared secret..."
  mkdir -p "$SECRETS_DIR"
  openssl rand -base64 32 > "$SECRETS_DIR/shared-secret"
  chmod 600 "$SECRETS_DIR/shared-secret"
  echo "Shared secret written to $SECRETS_DIR/shared-secret"
fi

# Pre-build the flowtree module (only needed for controller container)
if [ $# -eq 0 ] || echo "$@" | grep -qwE "controller|ar-manager"; then
  echo "Building flowtree module..."
  mvn package -pl flowtree -am -DskipTests
  mvn dependency:copy-dependencies -pl flowtree -DoutputDirectory=target/dependency
fi

# Rebuild and restart containers
if [ $# -eq 0 ]; then
  echo "Rebuilding all containers..."
  docker compose -f tools/docker-compose.yml up -d --build
else
  echo "Rebuilding: $*"
  docker compose -f tools/docker-compose.yml up -d --build "$@"
fi
