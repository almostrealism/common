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
# already exists, so the default path downloads the most recent unexpired
# `merged-coverage-report` artifact for BRANCH. Set FORCE=true to recompute
# it fresh instead (a full `mvn test` across every module, then the same
# jacococli merge analysis.yaml performs) — slow, but authoritative as of
# right now rather than "as of the last master run".
#
# The artifact is looked up directly by name via the repo-wide artifacts
# endpoint, not by first finding a workflow run whose OVERALL conclusion is
# "success". The `analysis` job that uploads this artifact can — and
# routinely does — succeed even when an unrelated job elsewhere in the same
# `analysis.yaml` run is flaky (e.g. a GPU/CL test lane), which makes the
# run's aggregate conclusion "failure" while the artifact this script needs
# was still produced. Gating on whole-run success instead of the artifact's
# own presence meant this path almost never found a recent (unexpired)
# artifact in practice, and every round fell through to the offline
# recompute below instead.
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
#   BRANCH             - branch whose most recent merged-coverage-report
#                        artifact is reused (default: master)
#   OUTPUT_DIR         - where to write coverage.xml / python-coverage.xml
#                        (default: current directory)
#   ARTIFACT_PAGE_LIMIT - how many 100-artifact pages of the repo-wide
#                        merged-coverage-report listing to search for a
#                        BRANCH artifact before giving up (default: 10)
#   ALLOW_RECOMPUTE    - "false" forbids the Java recompute path entirely
#                        (default: true). A caller whose host cannot run
#                        the full Maven suite meaningfully — a GitHub-hosted
#                        runner, which lacks the native hardware setup the
#                        real coverage lanes use — sets this so a missing
#                        artifact (or FORCE=true) fails fast with a clear
#                        error instead of spending hours producing a report
#                        that does not match the CI lanes.
#
# Exit codes:
#   0 - both reports were produced (an empty Java report counts as
#       produced when recompute finds no coverage data)
#   1 - invalid arguments, neither the reuse nor the recompute path
#       could produce a Java report, or a recompute was needed while
#       ALLOW_RECOMPUTE=false

set -euo pipefail

FORCE="${FORCE:-false}"
ALLOW_RECOMPUTE="${ALLOW_RECOMPUTE:-true}"
BRANCH="${BRANCH:-master}"
OUTPUT_DIR="${OUTPUT_DIR:-.}"
ARTIFACT_PAGE_LIMIT="${ARTIFACT_PAGE_LIMIT:-10}"
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

    local artifacts_json artifact_id run_id download_url tmp_zip tmp_dir selected

    # List artifacts by name directly (repo-wide), rather than finding a
    # workflow run by overall status first: an unrelated flaky job elsewhere
    # in the same analysis.yaml run must not hide an artifact that the
    # analysis job itself already produced successfully.
    # The endpoint has no server-side branch filter, and analysis.yaml's
    # `analysis` job uploads this same artifact name on every pull_request
    # run too, not just master pushes, so busy PR traffic can push the
    # newest BRANCH artifact past the first page. Artifacts are listed
    # newest first; page through them (per_page=100 is the API maximum)
    # until one for BRANCH turns up, the listing runs out, or
    # ARTIFACT_PAGE_LIMIT pages have been read. A miss matters: a caller
    # with ALLOW_RECOMPUTE=false has no fallback.
    local page=0 page_count
    selected=""
    while [ -z "$selected" ] && [ "$page" -lt "$ARTIFACT_PAGE_LIMIT" ]; do
        page=$((page + 1))
        artifacts_json=$(api_get "${API_BASE}/repos/${GITHUB_REPOSITORY}/actions/artifacts?name=merged-coverage-report&per_page=100&page=${page}") \
            || { echo "::warning::Could not list merged-coverage-report artifacts (page ${page})"; return 1; }

        selected=$(echo "$artifacts_json" | jq -r --arg branch "$BRANCH" '
            [.artifacts[] | select(.expired == false and .workflow_run.head_branch == $branch)]
            | sort_by(.created_at) | reverse | .[0]
            | if . == null then "" else (.id|tostring) + " " + (.workflow_run.id|tostring) end
        ')
        page_count=$(echo "$artifacts_json" | jq -r '.artifacts | length')
        if [ "$page_count" -lt 100 ]; then
            break
        fi
    done
    if [ -z "$selected" ]; then
        echo "::warning::No unexpired merged-coverage-report artifact found for branch ${BRANCH} in the ${page} most recent page(s) of artifacts"
        return 1
    fi
    artifact_id=${selected%% *}
    run_id=${selected##* }

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
    echo "::notice::Reused merged Java coverage report from artifact ${artifact_id} (run ${run_id} on ${BRANCH})"
    return 0
}

recompute_java_report() {
    if [ "$ALLOW_RECOMPUTE" = "false" ]; then
        echo "::error::Java coverage would have to be recomputed (FORCE=${FORCE}), but ALLOW_RECOMPUTE=false on this host. Run \"Build and Test\" on ${BRANCH} to publish a fresh merged-coverage-report artifact, then re-run this job." >&2
        exit 1
    fi

    echo "::notice::Recomputing Java coverage fresh — this runs the full test suite and can take hours"

    # Online, matching every other mvn invocation in analysis.yaml: a
    # self-hosted runner host that has never built this reactor before has
    # no local cache to run offline against (build extensions such as
    # kr.motd.maven:os-maven-plugin are resolved before any module's POM is
    # even read, so offline mode fails immediately on a cold host rather
    # than degrading gracefully).
    mvn install -DskipTests -Dcheckstyle.skip=true

    # This runs every module's tests (jacoco-maven-plugin's prepare-agent +
    # report executions are already bound to the test phase in the root
    # pom), which is exactly the expensive step the reuse path exists to
    # avoid. failIfNoTests=false so modules with no tests do not fail the
    # reactor build.
    mvn test -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.test.failure.ignore=true

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
    if [ "$ALLOW_RECOMPUTE" != "false" ]; then
        echo "::notice::Falling back to a fresh recompute (no reusable report was found)"
    fi
    recompute_java_report
fi

if [ ! -f "${OUTPUT_DIR}/coverage.xml" ]; then
    echo "::error::No Java coverage report was produced" >&2
    exit 1
fi

# ─── Python: always fresh (fast) ───────────────────────────────────────
#
# tools/mcp/manager/server.py imports mcp.server.fastmcp at module load
# (discovered when PYTHON_DIRS below runs unittest discover over
# tools/mcp/manager), and the `mcp` package requires Python >=3.10
# (tools/mcp/requirements.txt). A bare `python3` on a self-hosted macOS
# runner can resolve to the OS-bundled Python 3.9, which cannot install
# `mcp` at all. select-python-env.sh selects the newest interpreter on
# PATH that actually satisfies the requirement (verified via
# sys.version_info, not just its name), and provisions a venv for it in a
# cache directory outside the checkout — a plain `pip3 install` is not an
# option because a Homebrew-installed Python marks its site-packages
# externally-managed and refuses a global install outright. The cached
# venv is invalidated (recreated) whenever the interpreter,
# tools/mcp/requirements.txt, or the extra package list below changes;
# without that, a venv created once by an old interpreter would be reused
# forever, and pip's `Requires-Python` filtering would silently make every
# release of a dependency look unavailable ("from versions: none").
# Mirrors analysis.yaml's python-tests "Install dependencies" step so the
# same package set covers PYTHON_DIRS below (tools/mcp/manager needs
# `mcp`; tools/tests needs `pyyaml`).
#
# The coverage-qa workflow job pins its interpreter with setup-python on a
# GitHub-hosted runner, but this script still runs on self-hosted macOS
# hosts (and by hand). There, a Homebrew-
# installed Python 3.10+ can exist on disk without being on this process's
# PATH: launchd starts services (the CI runner among them) with almost no
# PATH, and Homebrew's bin directory is only restored when a service
# definition adds it back explicitly (tools/ci/macos/README.md documents
# this same gap for the flowtree agent daemon and the deploy-agent runner).
# Append the conventional Homebrew bin directories for both CPU
# architectures so select-python-env.sh's PATH search can still find a
# versioned `pythonX.Y` (Homebrew's python@3.1x formulas are keg-only but
# still symlink their versioned binary into these directories) even when
# the runner's own PATH omits them. A no-op on any host without Homebrew
# at these locations, and it only widens the search — select-python-env.sh
# still picks the newest interpreter that actually satisfies the minimum
# version, wherever it is found.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ "$(uname -s)" = "Darwin" ]; then
    PATH="${PATH}:/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/local/sbin"
fi
PYTHON=$(REQUIREMENTS_FILE=tools/mcp/requirements.txt bash "${SCRIPT_DIR}/select-python-env.sh" pyyaml coverage)

PYTHON_DIRS=(tools/mcp/manager tools/mcp/common tools/tests)
FIRST=true
for dir in "${PYTHON_DIRS[@]}"; do
    [ -d "$dir" ] || continue
    if [ "$FIRST" = "true" ]; then
        "$PYTHON" -m coverage run --source="$dir" -m unittest discover -s "$dir" -p 'test_*.py'
        FIRST=false
    else
        "$PYTHON" -m coverage run -a --source="$dir" -m unittest discover -s "$dir" -p 'test_*.py'
    fi
done

# --omit is the caveat the plan's appendix documents: --source=<dir> alone
# instruments the test_*.py files too, which inflates the directory's
# reported coverage with lines that are never production code.
"$PYTHON" -m coverage xml -o "${OUTPUT_DIR}/python-coverage.xml" --omit='*/test_*.py'

echo "Coverage reports written to ${OUTPUT_DIR}/coverage.xml and ${OUTPUT_DIR}/python-coverage.xml"
