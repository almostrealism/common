#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

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
