#!/usr/bin/env bash
# ─── Fetch (or recompute) the coverage reports select-target.py reads ──
#
# Produces two files in OUTPUT_DIR: coverage.xml (merged JaCoCo, Java) and
# python-coverage.xml (coverage.py Cobertura, Python), for
# select-target.py to rank.
#
# Java (reuse by default — open question 3 in the plan): the `analysis`
# job in analysis.yaml already merges every `coverage-*` artifact from the
# latest "Build and Test" run into one report and uploads it as the
# `merged-coverage-report` artifact (`.qodana/code-coverage/coverage.xml`).
# Re-running the whole suite here would cost hours for a report that
# already exists, so the default path downloads that one artifact from
# the most recent SUCCESSFUL run of WORKFLOW_FILE on BRANCH. Set FORCE=true
# to recompute it fresh instead (a full `mvn test` across every module,
# then the same jacococli merge analysis.yaml performs) — slow, but
# authoritative as of right now rather than "as of the last master run".
#
# Python: always computed fresh. The Python suites (tools/mcp/manager,
# tools/mcp/common, tools/tests) run in seconds, so there is no
# reuse-vs-recompute tradeoff worth making — coverage.py wraps the same
# `unittest discover` invocations the python-tests CI job uses, and test
# files are omitted from the report (coverage.py's `--source=<dir>` caveat
# documented in the plan's appendix: it instruments the test files
# themselves unless told not to).
#
# Usage:
#   fetch-latest-coverage.sh
#
# Required environment variables (only for the default Java reuse path):
#   GITHUB_REPOSITORY  - owner/repo
#   GITHUB_TOKEN       - token with actions:read on GITHUB_REPOSITORY
#
# Optional environment variables:
#   FORCE              - "true" recomputes Java coverage fresh instead of
#                        reusing the latest master artifact (default: false)
#   BRANCH             - branch to read the latest successful run from
#                        (default: master)
#   WORKFLOW_FILE      - workflow filename to query (default: analysis.yaml)
#   OUTPUT_DIR         - where to write coverage.xml / python-coverage.xml
#                        (default: current directory)
#
# Exit codes:
#   0 - both reports were produced (an empty Java report counts as
#       produced when recompute finds no coverage data)
#   1 - invalid arguments, or neither the reuse nor the recompute path
#       could produce a Java report

set -euo pipefail

FORCE="${FORCE:-false}"
BRANCH="${BRANCH:-master}"
WORKFLOW_FILE="${WORKFLOW_FILE:-analysis.yaml}"
OUTPUT_DIR="${OUTPUT_DIR:-.}"
JACOCO_VERSION="0.8.11"

mkdir -p "$OUTPUT_DIR"

API_BASE="https://api.github.com"

api_get() {
    curl -sS -f \
        -H "Authorization: Bearer ${GITHUB_TOKEN}" \
        -H "Accept: application/vnd.github+json" \
        "$@"
}

# ─── Java: reuse the latest merged report, or recompute fresh ─────────

fetch_merged_report() {
    if [ -z "${GITHUB_REPOSITORY:-}" ] || [ -z "${GITHUB_TOKEN:-}" ]; then
        echo "::warning::GITHUB_REPOSITORY/GITHUB_TOKEN unset — cannot reuse a prior report"
        return 1
    fi

    local runs_json run_id artifacts_json artifact_id download_url tmp_zip tmp_dir

    runs_json=$(api_get "${API_BASE}/repos/${GITHUB_REPOSITORY}/actions/workflows/${WORKFLOW_FILE}/runs?branch=${BRANCH}&status=success&per_page=1") \
        || { echo "::warning::Could not list workflow runs for ${WORKFLOW_FILE} on ${BRANCH}"; return 1; }

    run_id=$(echo "$runs_json" | jq -r '.workflow_runs[0].id // empty')
    if [ -z "$run_id" ]; then
        echo "::warning::No successful ${WORKFLOW_FILE} run found on ${BRANCH}"
        return 1
    fi

    artifacts_json=$(api_get "${API_BASE}/repos/${GITHUB_REPOSITORY}/actions/runs/${run_id}/artifacts?per_page=100") \
        || { echo "::warning::Could not list artifacts for run ${run_id}"; return 1; }

    artifact_id=$(echo "$artifacts_json" | jq -r '[.artifacts[] | select(.name == "merged-coverage-report")][0].id // empty')
    if [ -z "$artifact_id" ]; then
        echo "::warning::Run ${run_id} has no merged-coverage-report artifact (expired, or coverage was skipped)"
        return 1
    fi

    tmp_zip=$(mktemp)
    tmp_dir=$(mktemp -d)
    download_url="${API_BASE}/repos/${GITHUB_REPOSITORY}/actions/artifacts/${artifact_id}/zip"

    if ! api_get -L "$download_url" -o "$tmp_zip"; then
        echo "::warning::Could not download artifact ${artifact_id} from run ${run_id}"
        rm -f "$tmp_zip"; rm -rf "$tmp_dir"
        return 1
    fi

    unzip -q -o "$tmp_zip" -d "$tmp_dir"
    if [ ! -f "$tmp_dir/coverage.xml" ]; then
        echo "::warning::merged-coverage-report artifact did not contain coverage.xml"
        rm -f "$tmp_zip"; rm -rf "$tmp_dir"
        return 1
    fi

    cp "$tmp_dir/coverage.xml" "${OUTPUT_DIR}/coverage.xml"
    rm -f "$tmp_zip"; rm -rf "$tmp_dir"
    echo "::notice::Reused merged Java coverage report from run ${run_id} (${WORKFLOW_FILE} on ${BRANCH})"
    return 0
}

recompute_java_report() {
    echo "::notice::Recomputing Java coverage fresh — this runs the full test suite and can take hours"

    mvn -o install -DskipTests -Dcheckstyle.skip=true

    # This runs every module's tests (jacoco-maven-plugin's prepare-agent +
    # report executions are already bound to the test phase in the root
    # pom), which is exactly the expensive step the reuse path exists to
    # avoid. failIfNoTests=false so modules with no tests do not fail the
    # reactor build.
    mvn -o test -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=true

    local exec_files_txt merged_exec
    exec_files_txt=$(mktemp)
    merged_exec=$(mktemp)
    find . -path '*/target/jacoco.exec' > "$exec_files_txt"

    if [ ! -s "$exec_files_txt" ]; then
        echo "::warning::No jacoco.exec files produced — writing an empty report"
        printf '<?xml version="1.0" encoding="UTF-8"?>\n<report name="merged"/>\n' > "${OUTPUT_DIR}/coverage.xml"
        rm -f "$exec_files_txt" "$merged_exec"
        return 0
    fi

    local jacoco_dir
    jacoco_dir=$(mktemp -d)
    curl -sL -o "${jacoco_dir}/jacoco-cli.zip" \
        "https://repo1.maven.org/maven2/org/jacoco/jacoco/${JACOCO_VERSION}/jacoco-${JACOCO_VERSION}.zip"
    unzip -q "${jacoco_dir}/jacoco-cli.zip" -d "${jacoco_dir}/unpacked"

    # Word-splitting is the point here: each of these expands to a list of
    # positional/--flag arguments (one per .exec file, one --classfiles per
    # target/classes dir, one --sourcefiles per src/main/java dir), mirroring
    # analysis.yaml's own "Merge Coverage Data" step.
    # shellcheck disable=SC2046
    java -jar "${jacoco_dir}/unpacked/lib/jacococli.jar" merge \
        $(tr '\n' ' ' < "$exec_files_txt") \
        --destfile "$merged_exec"

    # shellcheck disable=SC2046
    java -jar "${jacoco_dir}/unpacked/lib/jacococli.jar" report "$merged_exec" \
        $(find . -path '*/target/classes' -type d -exec echo --classfiles {} \;) \
        $(find . -path '*/src/main/java' -type d -exec echo --sourcefiles {} \;) \
        --xml "${OUTPUT_DIR}/coverage.xml"

    rm -f "$exec_files_txt" "$merged_exec"
    rm -rf "$jacoco_dir"
}

if [ "$FORCE" = "true" ]; then
    recompute_java_report
elif ! fetch_merged_report; then
    echo "::notice::Falling back to a fresh recompute (no reusable report was found)"
    recompute_java_report
fi

if [ ! -f "${OUTPUT_DIR}/coverage.xml" ]; then
    echo "::error::No Java coverage report was produced" >&2
    exit 1
fi

# ─── Python: always fresh (fast) ───────────────────────────────────────

if ! python3 -m coverage --version >/dev/null 2>&1; then
    echo "::notice::Installing coverage.py"
    pip3 install --quiet coverage
fi

PYTHON_DIRS=(tools/mcp/manager tools/mcp/common tools/tests)
FIRST=true
for dir in "${PYTHON_DIRS[@]}"; do
    [ -d "$dir" ] || continue
    if [ "$FIRST" = "true" ]; then
        python3 -m coverage run --source="$dir" -m unittest discover -s "$dir" -p 'test_*.py'
        FIRST=false
    else
        python3 -m coverage run -a --source="$dir" -m unittest discover -s "$dir" -p 'test_*.py'
    fi
done

# --omit is the caveat the plan's appendix documents: --source=<dir> alone
# instruments the test_*.py files too, which inflates the directory's
# reported coverage with lines that are never production code.
python3 -m coverage xml -o "${OUTPUT_DIR}/python-coverage.xml" --omit='*/test_*.py'

echo "Coverage reports written to ${OUTPUT_DIR}/coverage.xml and ${OUTPUT_DIR}/python-coverage.xml"
