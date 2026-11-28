# shellcheck shell=bash
#
# Steps shared by the native host installers (../macos/install.sh and
# ../linux/install.sh). Sourced, never executed: each installer owns its
# service manager (launchd, systemd) and calls these for the parts that do
# not depend on one — reading the operator's env file, probing Maven, the
# build, and the atomic build/stage/swap of the runtime JARs.
#
# The functions read the caller's globals rather than taking arguments,
# because every installer already defines them under the same names:
#   SCRIPT_DIR    the installer's own directory (for agent.env.example)
#   MODULE_DIR    flowtree/runtime
#   PROJECT_ROOT  the repository root
#   AGENT_HOME    the install directory
#   ENV_FILE      the operator's env file
#   BUILD         true unless --no-build was given

# ── Env file ───────────────────────────────────────────────────────
#
# Checked before the build so a misconfigured host fails immediately. The
# env file is the operator's, kept outside the checkout because it holds a
# credential; no installer ever creates or edits it.

# Read only the keys the checks need. The file is sourced in a subshell so
# nothing in it leaks into the installer's environment — the service gets
# it from its service manager via FLOWTREE_AGENT_ENV, not from here.
read_env_key() {
    # shellcheck source=/dev/null
    ( set +u; set -a; . "${ENV_FILE}"; set +a; printf '%s' "${!1:-}" )
}

# Fails unless the env file exists and names a controller and a credential.
# Sets ROOT_HOST and ROOT_PORT for the caller.
# shellcheck disable=SC2034 # ROOT_HOST and ROOT_PORT are the caller's
require_agent_env() {
    if [ ! -f "${ENV_FILE}" ]; then
        echo "ERROR: agent env file not found at ${ENV_FILE}" >&2
        echo "  Create it from ${SCRIPT_DIR}/agent.env.example, or point" >&2
        echo "  FLOWTREE_AGENT_ENV at an existing one." >&2
        exit 1
    fi

    ROOT_HOST="$(read_env_key FLOWTREE_ROOT_HOST)"
    ROOT_PORT="$(read_env_key FLOWTREE_ROOT_PORT)"
    ROOT_PORT="${ROOT_PORT:-7766}"

    if [ -z "${ROOT_HOST}" ]; then
        echo "ERROR: FLOWTREE_ROOT_HOST is not set in ${ENV_FILE}." >&2
        exit 1
    fi
    if [ -z "$(read_env_key CLAUDE_CODE_OAUTH_TOKEN)" ] && [ -z "$(read_env_key ANTHROPIC_API_KEY)" ]; then
        echo "ERROR: neither CLAUDE_CODE_OAUTH_TOKEN nor ANTHROPIC_API_KEY is set in ${ENV_FILE}." >&2
        echo "  Generate a token with: claude setup-token" >&2
        exit 1
    fi
}

# ── Maven ──────────────────────────────────────────────────────────
#
# Jobs isolate their installed artifacts with Maven's split local repository
# (MavenInstallSpace), which Maven older than 3.9 silently ignores. Sets
# MVN_SPLIT_SUPPORTED to true or false from the mvn on PATH; the caller
# decides whether an unsupported Maven is a warning or a failure.
# shellcheck disable=SC2034 # MVN_SPLIT_SUPPORTED is the caller's
probe_mvn_split_repository() {
    local version major minor
    MVN_SPLIT_SUPPORTED=true
    version="$(mvn --version 2>/dev/null | sed -n 's/^Apache Maven \([0-9]*\)\.\([0-9]*\).*/\1 \2/p')"
    read -r major minor <<< "${version}"
    if [ -z "${version}" ] || [ "${major}" -lt 3 ] \
            || { [ "${major}" -eq 3 ] && [ "${minor}" -lt 9 ]; }; then
        MVN_SPLIT_SUPPORTED=false
    fi
}

# ── Build ──────────────────────────────────────────────────────────
#
# Identical to the build rebuild.sh performs for the container images, and
# for the same reasons: `install` so upstream modules land in ~/.m2 before
# copy-dependencies reads them, and an absolute outputDirectory because the
# plugin resolves a relative one against the module, not the reactor.
build_runtime() {
    if [ "${BUILD}" = true ]; then
        echo "Building flowtree module..."
        (
            cd "${PROJECT_ROOT}" || exit 1
            mvn install -pl flowtree/runtime -am -DskipTests
            mvn dependency:copy-dependencies -pl flowtree/runtime \
                -DoutputDirectory="${PROJECT_ROOT}/flowtree/runtime/target/dependency"
        )
    fi
}

# ── Stage and swap ─────────────────────────────────────────────────
#
# A new lib directory is assembled in full and moved into place in one
# step, so the service never sees a half-copied classpath, and the JARs a
# running JVM has open are moved aside rather than truncated under it.
# Creates AGENT_HOME/bin, conf and logs; the caller installs the launcher
# and properties afterwards.
stage_runtime() {
    local runtime_jar="" candidate staging provenance_clean
    # The first runtime JAR in name order that is not a sources, javadoc or
    # tests attachment.
    for candidate in "${MODULE_DIR}"/target/ar-flowtree-runtime-*.jar; do
        case "${candidate}" in
            *-sources.jar|*-javadoc.jar|*-tests.jar) continue ;;
        esac
        if [ -f "${candidate}" ]; then
            runtime_jar="${candidate}"
            break
        fi
    done
    if [ -z "${runtime_jar}" ] || [ ! -d "${MODULE_DIR}/target/dependency" ]; then
        echo "ERROR: no built runtime JAR under ${MODULE_DIR}/target — run without --no-build." >&2
        exit 1
    fi

    mkdir -p "${AGENT_HOME}/bin" "${AGENT_HOME}/conf" "${AGENT_HOME}/logs"

    staging="${AGENT_HOME}/lib.staging"
    rm -rf "${staging}"
    mkdir -p "${staging}"
    cp "${runtime_jar}" "${staging}/"
    cp "${MODULE_DIR}"/target/dependency/*.jar "${staging}/"

    # ── Build provenance ───────────────────────────────────────────
    #
    # Written into the staging directory so it is swapped into place by the
    # same mv as the JARs it describes. Written afterwards, it could survive
    # a failed swap and name a build that is not the one installed.
    #
    # What it proves and what it does not: it records the commit this
    # checkout was on and whether the checkout was clean when the JARs were
    # built. It does NOT prove the JARs were compiled from that commit — only
    # a reproducible build proves that, and this is not one. It is enough to
    # catch the realistic failure, a deploy from a stale or dirty tree, and
    # it must not be read as more than that.
    #
    # `built` records whether this run compiled anything. Under --no-build
    # the JARs may predate the checked-out commit entirely, so a consumer
    # that treats the SHA as describing the binary has to know to distrust it.
    #
    # Living in lib/ alongside the JARs is what buys the atomic swap, and it
    # is inert there: the launchers pass `-cp "${AGENT_HOME}/lib/*"` to the
    # JVM, whose classpath wildcard expands to .jar files only.
    provenance_clean=true
    if [ -n "$(git -C "${PROJECT_ROOT}" status --porcelain 2>/dev/null)" ]; then
        provenance_clean=false
    fi
    {
        echo "sha=$(git -C "${PROJECT_ROOT}" rev-parse HEAD 2>/dev/null || echo unknown)"
        echo "branch=$(git -C "${PROJECT_ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
        echo "clean=${provenance_clean}"
        echo "built=${BUILD}"
        echo "installed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "installed_by=$(id -un)"
        echo "host=$(hostname)"
    } > "${staging}/BUILD_PROVENANCE"

    rm -rf "${AGENT_HOME}/lib.previous"
    if [ -d "${AGENT_HOME}/lib" ]; then
        mv "${AGENT_HOME}/lib" "${AGENT_HOME}/lib.previous"
    fi
    mv "${staging}" "${AGENT_HOME}/lib"
}
