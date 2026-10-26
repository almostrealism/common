#!/usr/bin/env bash
# ─── Resolve the releases and documents goal decomposition works from ─
#
# Goal decomposition turns a repository's goals and the plan for its next
# release into tracker tasks. Everything it needs to know about *which*
# release is read from the repository at the commit being reconciled, so
# the agent never has to work it out:
#
#   - the next version, from the version file (a Maven pom.xml is read as
#     its project version; any other file as a plain version string);
#   - the release plan directory for that version, <plans dir>/<version>/,
#     whose PLAN.md is the scope of the release;
#   - the framework release the repository builds against, from a property
#     in its root pom.xml.
#
# When the release plan directory does not exist there is no approved scope
# for the release, and in_scope=false tells the caller to stop: goal
# decomposition never invents a scope from the goals alone.
#
# Run from the root of the repository being reconciled.
#
# Usage:
#   resolve-release-scope.sh
#
# Required environment variables:
#   TRACKER_PROJECT  - tracker project of this repository (e.g. Common)
#
# Optional environment variables:
#   VERSION_FILE                - file holding the next version (default pom.xml)
#   GOALS_DOCUMENT              - long-term goals document (default docs/GOALS.md)
#   PLANS_DIRECTORY             - directory holding one release-plan directory
#                                 per version (default docs/plans)
#   FRAMEWORK_PROJECT           - tracker project of the framework (default Common)
#   FRAMEWORK_VERSION_PROPERTY  - root pom.xml property pinning the framework
#                                 version (default common.version). Not read
#                                 when FRAMEWORK_PROJECT is TRACKER_PROJECT:
#                                 the framework's own release is its version.
#
# Outputs (to stdout, and to $GITHUB_OUTPUT when set):
#   version=<next version>
#   release=<TRACKER_PROJECT> <version>
#   plan_directory=<PLANS_DIRECTORY>/<version>
#   release_plan=<PLANS_DIRECTORY>/<version>/PLAN.md
#   goals_document=<GOALS_DOCUMENT>, or empty when the file does not exist
#   framework_release=<FRAMEWORK_PROJECT> <framework version>
#   in_scope=true|false
#
# Exit codes:
#   0 - resolved (check in_scope=)
#   1 - invalid arguments, or a version that cannot be read

set -euo pipefail

for var in TRACKER_PROJECT; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: ${var} is not set." >&2
        exit 1
    fi
done

VERSION_FILE="${VERSION_FILE:-pom.xml}"
GOALS_DOCUMENT="${GOALS_DOCUMENT:-docs/GOALS.md}"
PLANS_DIRECTORY="${PLANS_DIRECTORY:-docs/plans}"
PLANS_DIRECTORY="${PLANS_DIRECTORY%/}"
FRAMEWORK_PROJECT="${FRAMEWORK_PROJECT:-Common}"
FRAMEWORK_VERSION_PROPERTY="${FRAMEWORK_VERSION_PROPERTY:-common.version}"

emit() {
    echo "$1=$2"
    if [ -n "${GITHUB_OUTPUT:-}" ]; then
        echo "$1=$2" >> "$GITHUB_OUTPUT"
    fi
}

# Prints the text of one element of a Maven POM: the project version when
# the second argument is empty, otherwise the named property. Prints nothing
# when the element is absent.
pom_value() {
    python3 - "$1" "$2" <<'PY'
import sys
import xml.etree.ElementTree as ET

ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ET.parse(sys.argv[1]).getroot()
path = "m:version" if not sys.argv[2] else "m:properties/m:" + sys.argv[2]
node = root.find(path, ns)
if node is not None and node.text:
    print(node.text.strip())
PY
}

# A version names a directory and a tracker release, so it must be one plain
# path component.
require_version() {
    if ! [[ "$2" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
        echo "::error::Could not read a usable $1 version (got '${2}')." >&2
        exit 1
    fi
}

if [ ! -f "$VERSION_FILE" ]; then
    echo "::error::Version file ${VERSION_FILE} does not exist." >&2
    exit 1
fi

case "$VERSION_FILE" in
    *pom.xml) VERSION=$(pom_value "$VERSION_FILE" "") ;;
    *)        VERSION=$(cat "$VERSION_FILE") ;;
esac
# Surrounding whitespace (a trailing newline) is trimmed. Anything inside the
# version is kept, so require_version rejects it rather than a mangled
# version being used.
VERSION="${VERSION#"${VERSION%%[![:space:]]*}"}"
VERSION="${VERSION%"${VERSION##*[![:space:]]}"}"
require_version "next" "$VERSION"

if [ "$FRAMEWORK_PROJECT" = "$TRACKER_PROJECT" ]; then
    FRAMEWORK_VERSION="$VERSION"
else
    if [ ! -f pom.xml ]; then
        echo "::error::No root pom.xml to read ${FRAMEWORK_VERSION_PROPERTY} from." >&2
        exit 1
    fi
    FRAMEWORK_VERSION=$(pom_value pom.xml "$FRAMEWORK_VERSION_PROPERTY")
    require_version "framework (${FRAMEWORK_VERSION_PROPERTY})" "$FRAMEWORK_VERSION"
fi

PLAN_DIRECTORY="${PLANS_DIRECTORY}/${VERSION}"
GOALS_PRESENT=""
if [ -f "$GOALS_DOCUMENT" ]; then
    GOALS_PRESENT="$GOALS_DOCUMENT"
fi

emit version "$VERSION"
emit release "${TRACKER_PROJECT} ${VERSION}"
emit plan_directory "$PLAN_DIRECTORY"
emit release_plan "${PLAN_DIRECTORY}/PLAN.md"
emit goals_document "$GOALS_PRESENT"
emit framework_release "${FRAMEWORK_PROJECT} ${FRAMEWORK_VERSION}"

if [ -f "${PLAN_DIRECTORY}/PLAN.md" ]; then
    echo "::notice::Reconciling ${TRACKER_PROJECT} ${VERSION} against ${PLAN_DIRECTORY}/PLAN.md."
    emit in_scope true
else
    echo "::notice::No release plan at ${PLAN_DIRECTORY}/PLAN.md — nothing to reconcile for ${TRACKER_PROJECT} ${VERSION}."
    emit in_scope false
fi
