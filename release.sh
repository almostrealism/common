#!/usr/bin/env bash
#
# Releases this repository: tags master with the pom.xml version, pushes the
# tag, deploys with mvn deploy -DskipTests, then moves every pom to the next
# version. Exits 0 without changing anything when there is nothing new since
# the last release. The procedure and its options (--dry-run, --force,
# --next-version) are described in scripts/release-lib.sh.
#
# Repositories that build on this one call this script from their own
# release.sh before releasing themselves.

set -euo pipefail

RELEASE_REPO_DIR=$(cd "$(dirname "$0")" && pwd -P)
RELEASE_UPSTREAM=""
RELEASE_DEPENDENCIES=""

. "$RELEASE_REPO_DIR/scripts/release-lib.sh"
release_main "$@"
