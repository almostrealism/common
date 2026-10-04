#!/usr/bin/env bash
set -euo pipefail

# ─── Install, start, stop, and inspect the macOS CI runner ───────────
#
# The macOS counterpart of tools/ci/rocm/fleet.sh, normally reached through
# the fleet entry point:
#
#   tools/bin/fleet macos install [--user worker] [--instance NAME]
#                                 [--env FILE] [--store-from USER@HOST]
#                                 [--no-monitor]
#   tools/bin/fleet macos start   [--instance NAME]
#   tools/bin/fleet macos stop    [--instance NAME] [--if-idle]
#   tools/bin/fleet macos restart [--instance NAME]
#   tools/bin/fleet macos status  [--instance NAME]
#   tools/bin/fleet macos logs    [--instance NAME] [-f]
#   tools/bin/fleet macos uninstall [--instance NAME]
#
# Run it as an administrator (an account with sudo), from a checkout that
# account owns, after filling in tools/ci/macos/.env. Not as root. The monitor
# is installed as the invoking account, which therefore must not be the
# runner account: the runner executes CI jobs, and the monitor's database
# credential must stay out of their reach.
#
# install does the whole setup for a new host, and re-running it is how an
# existing one is updated (after a git pull, or an edit to the env file):
#
#   1. Checks everything before changing anything: the env file, the runner
#      account, the tools the runner needs on the PATH the daemon will have,
#      the ownership register-daemon.sh insists on, and the monitor's
#      credential.
#   2. Stages runner.sh, cpu-watcher.sh and the env file (mode 600) into
#      the runner account's home, at ~<user>/ci-runner[-NAME], and renders
#      com.almostrealism.ci-runner.plist there.
#   3. Registers it as a LaunchDaemon in the system domain through
#      flowtree/runtime/agent/macos/register-daemon.sh, which replaces an
#      existing registration, and waits until GitHub lists the runner online.
#   4. Installs the fleet metrics collector with tools/fleet/launchd/install.sh,
#      as the invoking account (skip with --no-monitor).
#
# Options:
#   --user NAME        the account the runner, and every job, runs as
#                      (default: worker, or AR_CI_USER). install only; the
#                      other commands read it from the registered service.
#   --instance NAME    a second runner on the same host (e.g. deploy-agent):
#                      service com.almostrealism.ci-runner-NAME, env file
#                      NAME.env beside this script, stage directory
#                      ~<user>/ci-runner-NAME, default runner directory
#                      ~<user>/actions-runner-NAME. It needs its own
#                      RUNNER_NAME in that env file.
#   --env FILE         the env file to install (default: .env, or NAME.env
#                      with --instance, beside this script)
#   --store-from U@H   where install.sh copies the monitor's store credential
#                      from, when ~/fleet/store-url does not exist yet
#   --no-monitor       install the runner only
#   --if-idle          stop: do nothing if the runner is in the middle of a job
#   -f                 logs: follow
#
# stop removes the service from launchd, which lets runner.sh deregister the
# runner from GitHub; it stays stopped across reboots until start. The
# monitor is not stopped: it costs little, and a host with no runner is
# still worth measuring. uninstall removes the runner's LaunchDaemon and
# leaves the stage directory, the runner directory and the monitor in place.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
REGISTER_SCRIPT="${CHECKOUT}/flowtree/runtime/agent/macos/register-daemon.sh"
MONITOR_INSTALL="${CHECKOUT}/tools/fleet/launchd/install.sh"
TEMPLATE="${SCRIPT_DIR}/com.almostrealism.ci-runner.plist"
PLISTBUDDY="/usr/libexec/PlistBuddy"
DAEMONS_DIR="/Library/LaunchDaemons"
LABEL_BASE="com.almostrealism.ci-runner"
MONITOR_LABEL="com.almostrealism.fleet-collector"
ADMIN_GROUP="admin"
ONLINE_TIMEOUT_SECONDS=120

usage() { sed -n '4,63p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

COMMAND="${1:-}"
case "${COMMAND}" in
    ""|-h|--help|help) usage; exit 0 ;;
esac
shift

RUNNER_USER="${AR_CI_USER:-worker}"
INSTANCE=""
ENV_FILE=""
STORE_FROM=""
MONITOR=true
IF_IDLE=false
FOLLOW=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --user|--instance|--env|--store-from)
            if [ "$#" -lt 2 ]; then
                echo "ERROR: $1 needs a value." >&2
                exit 2
            fi
            case "$1" in
                --user) RUNNER_USER="$2" ;;
                --instance) INSTANCE="$2" ;;
                --env) ENV_FILE="$2" ;;
                --store-from) STORE_FROM="$2" ;;
            esac
            shift
            ;;
        --no-monitor) MONITOR=false ;;
        --if-idle) IF_IDLE=true ;;
        -f|--follow) FOLLOW=true ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "ERROR: unknown argument '$1'." >&2
            echo "  See: $0 --help" >&2
            exit 2
            ;;
    esac
    shift
done

# Both values reach file paths and a launchd label, so neither may carry a
# path separator or anything launchd's label check would refuse.
case "${RUNNER_USER}" in
    ""|root|*[!a-z0-9_.-]*|[!a-z_]*)
        echo "ERROR: --user '${RUNNER_USER}' must be a valid account name, and must not be root." >&2
        exit 1
        ;;
esac
case "${INSTANCE}" in
    *[!a-z0-9-]*|-*)
        echo "ERROR: --instance '${INSTANCE}' may use only lower-case letters, digits and '-'." >&2
        exit 1
        ;;
esac

SUFFIX="${INSTANCE:+-${INSTANCE}}"
LABEL="${LABEL_BASE}${SUFFIX}"
INSTALLED_PLIST="${DAEMONS_DIR}/${LABEL}.plist"
ENV_FILE="${ENV_FILE:-${SCRIPT_DIR}/${INSTANCE}.env}"
# Absolute, so the trust walk below covers every directory above it.
case "${ENV_FILE}" in
    /*) ;;
    *) ENV_FILE="${PWD}/${ENV_FILE}" ;;
esac

if [ "$(uname -s)" != "Darwin" ]; then
    echo "ERROR: this is the macOS runner; for the Linux GPU fleet use: tools/bin/fleet rocm ${COMMAND}" >&2
    exit 1
fi
if [ "$(id -u)" -eq 0 ]; then
    echo "ERROR: run this as an administrator, not as root; it calls sudo where it needs to." >&2
    exit 1
fi

# ---------- Helpers ----------

# State and pid of a system-domain service, as "state pid", or nothing when
# launchd does not have it. Reading the system domain needs no privilege.
service_state() {
    launchctl print "system/$1" 2>/dev/null \
        | awk -F' = ' '/^\tstate = /{s=$2} /^\tpid = /{p=$2} END{if (s != "") print s, p}'
}

plist_value() {
    "${PLISTBUDDY}" -c "Print :$2" "$1" 2>/dev/null || true
}

# Everything the other commands need is in the registered definition, so they
# work without the env file and without being told the account again.
load_installed() {
    if [ ! -f "${INSTALLED_PLIST}" ]; then
        echo "ERROR: ${LABEL} is not installed (no ${INSTALLED_PLIST})." >&2
        echo "  Install it with: tools/bin/fleet macos install${INSTANCE:+ --instance ${INSTANCE}}" >&2
        exit 1
    fi
    RUNNER_USER="$(plist_value "${INSTALLED_PLIST}" UserName)"
    STAGE_DIR="$(plist_value "${INSTALLED_PLIST}" WorkingDirectory)"
    RUNNER_DIR="$(plist_value "${INSTALLED_PLIST}" ProgramArguments:3)"
    RUNNER_HOME="$(plist_value "${INSTALLED_PLIST}" EnvironmentVariables:HOME)"
    LOG_FILE="$(plist_value "${INSTALLED_PLIST}" StandardOutPath)"
}

# Lines of `ps` for the runner agent's processes of one runner directory. The
# listener is up whenever the runner is registered; Runner.Worker exists only
# while a job runs, and is the only reliable sign of one from outside.
runner_processes() {
    ps -axo user=,pid=,command= | awk -v u="${RUNNER_USER}" -v p="${RUNNER_DIR}/bin/Runner.$1" \
        '$1 == u && index($0, p) {print}'
}

runner_busy() {
    [ -n "$(runner_processes Worker)" ]
}

# Echoes PATH when an account other than root or OWNER could change it — when it
# is a symlink, is group- or world-writable, or is owned by a third account —
# and nothing when only root and OWNER can. PRIV is "sudo" to reach a path under
# a service account's home the invoker cannot stat, or empty to stat as the
# invoker. This is the check register-daemon.sh makes on every component of the
# plist's path, applied here to the files fleet.sh trusts before it is reached.
untrusted_path() {
    local owner="$1" path="$2" priv="${3:-}"
    ${priv} find "${path}" -maxdepth 0 \
        \( -type l -o -perm -g+w -o -perm -o+w \
           -o \( ! -user "${owner}" -a ! -user root \) \) 2>/dev/null
}

# Echoes the first component of the absolute PATH, walking down from / to PATH
# itself, that CHECK (default untrusted_path) flags for OWNER, and nothing when
# none is. A file is only as safe as the directories above it: whoever can write
# one of them can rename the file away and put their own in its place. A
# component that does not exist yet ends the walk, since only the accounts that
# could write the last existing directory — already checked — can create it.
# PRIV is as for untrusted_path.
untrusted_ancestor() {
    local owner="$1" path="$2" priv="${3:-}" check="${4:-untrusted_path}" prefix="" component
    local -a parts
    IFS='/' read -r -a parts <<< "${path#/}"
    for component in "${parts[@]}"; do
        [ -n "${component}" ] || continue
        prefix="${prefix}/${component}"
        ${priv} test -e "${prefix}" -o -L "${prefix}" || return 0
        if [ -n "$("${check}" "${owner}" "${prefix}" "${priv}")" ]; then
            echo "${prefix}"
            return 0
        fi
    done
}

# The members of ADMIN_GROUP, one per word. Each can already become root through
# sudo, so a directory one of them owns is no weaker than one root owns.
admin_members() {
    dscl . -read "/Groups/${ADMIN_GROUP}" GroupMembership 2>/dev/null | sed 's/^GroupMembership://'
}

# Echoes PATH when an account other than root, OWNER or an administrator could
# change what it holds — it is world-writable, group-writable by a group other
# than ADMIN_GROUP, or owned by a third account — and nothing otherwise. Looser
# than untrusted_path on purpose: Homebrew's directories belong to the
# administrator who installed it and are writable by the admin group, and
# everyone in that group can already become root. A symlink is judged by the
# directory it points to (the link itself can only be replaced by whoever can
# write the directory holding it, which the walk checks first), so an entry under
# /tmp, a link to the sticky, world-writable /private/tmp, is reported even
# before it exists. A path the check cannot examine is reported, never passed.
# PRIV is as for untrusted_path.
untrusted_tool_dir() {
    local owner="$1" path="$2" priv="${3:-}" member out
    local -a owners=(! -user root ! -user "${owner}")
    for member in $(admin_members); do
        owners+=(! -user "${member}")
    done
    out="$(${priv} find -H "${path}" -maxdepth 0 \
        \( -perm -o+w -o \( -perm -g+w ! -group "${ADMIN_GROUP}" \) \
           -o \( "${owners[@]}" \) \) -print 2>/dev/null)" || out="${path}"
    [ -z "${out}" ] || echo "${out}"
}

# Echoes, one per line, each place on the colon-separated SEARCH path where an
# account other than root, OWNER or an administrator could put a program: the
# first untrusted directory on the way to an entry, or to what the entry
# resolves to, and every entry that is not absolute (an empty or relative entry
# is looked up from whatever directory a job happens to be in). Nothing when the
# whole path is safe. PRIV is as for untrusted_path.
untrusted_search_path() {
    local owner="$1" search="$2" priv="${3:-}" dir real bad
    local -a dirs
    IFS=':' read -r -a dirs <<< "${search}"
    for dir in "${dirs[@]}"; do
        case "${dir}" in
            /*) ;;
            *) echo "${dir:-(empty entry)}"; continue ;;
        esac
        bad="$(untrusted_ancestor "${owner}" "${dir}" "${priv}" untrusted_tool_dir)"
        if [ -z "${bad}" ]; then
            real="$(${priv} /bin/sh -c 'cd -P "$1" 2>/dev/null && pwd -P' _ "${dir}" || true)"
            [ -z "${real}" ] || bad="$(untrusted_ancestor "${owner}" "${real}" "${priv}" untrusted_tool_dir)"
        fi
        [ -z "${bad}" ] || echo "${bad}"
    done
}

# Writes stdin to DEST with MODE, as the runner account. The stage directory is
# the runner's, so it can plant a symlink anywhere in it; writing there as the
# runner rather than through sudo means a planted link can only redirect the
# write to somewhere the runner could already write.
stage_file() {
    sudo -u "${RUNNER_USER}" /bin/sh -c 'umask 077 && cat > "$1" && chmod "$2" "$1"' _ "$1" "$2"
}

# Reads the env file into ENV_* variables. It is sourced in a clean shell
# whose HOME is the runner account's, so a `~` or `$HOME` in it means the
# runner's home — what it means when runner.sh sources it under launchd —
# and not the administrator's.
read_env() {
    local values
    values="$(env -i HOME="${RUNNER_HOME}" PATH=/usr/bin:/bin /bin/bash -c '
        set -a
        . "$1" >/dev/null
        for v in GITHUB_PAT GITHUB_OWNER GITHUB_REPO RUNNER_SCOPE RUNNER_NAME RUNNER_DIR RUNNER_LABELS RUNNER_PATH; do
            eval "x=\${$v-}"
            printf "ENV_%s=%q\n" "$v" "$x"
        done' _ "$1")" || {
        echo "ERROR: could not read $1." >&2
        exit 1
    }
    eval "${values}"
}

# Sets API_BASE from the env file's scope, as runner.sh does.
resolve_api_base() {
    case "${ENV_RUNNER_SCOPE:-repo}" in
        repo) API_BASE="https://api.github.com/repos/${ENV_GITHUB_OWNER}/${ENV_GITHUB_REPO}" ;;
        org)  API_BASE="https://api.github.com/orgs/${ENV_GITHUB_OWNER}" ;;
        *)    API_BASE="" ;;
    esac
}

# GitHub's view of the runner: "online", "offline", or nothing when it is not
# registered (or the API cannot be asked).
github_status() {
    [ -n "${API_BASE:-}" ] && command -v jq >/dev/null 2>&1 && command -v curl >/dev/null 2>&1 || return 0
    local name
    name="$(jq -rn --arg n "${ENV_RUNNER_NAME}" '$n | @uri')"
    # The token goes through a curl config file on stdin, never on the command
    # line: `ps` shows every process's arguments to every account on the host —
    # the runner account among them, which runs untrusted CI jobs — the same
    # leak tools/fleet/credentials.py exists to prevent.
    printf 'header = "Authorization: token %s"\n' "${ENV_GITHUB_PAT}" \
        | curl -fsS --config - -H "Accept: application/vnd.github+json" \
        "${API_BASE}/actions/runners?per_page=100&name=${name}" 2>/dev/null \
        | jq -r --arg n "${ENV_RUNNER_NAME}" \
            '.runners[]? | select(.name == $n) | "\(.status) \(.busy) [\([.labels[].name] | join(","))]"' \
        | head -1 || true
}

# ---------- install ----------

cmd_install() {
    local errors=0

    echo "Preflight"

    # The env file is the operator's one piece of configuration.
    if [ ! -f "${ENV_FILE}" ]; then
        if [ "${ENV_FILE}" = "${SCRIPT_DIR}/.env" ] && [ -f "${SCRIPT_DIR}/.env.example" ]; then
            cp "${SCRIPT_DIR}/.env.example" "${ENV_FILE}"
            echo "  Created ${ENV_FILE} from the template. Fill it in and run this again."
        else
            echo "ERROR: ${ENV_FILE} not found. Start from ${SCRIPT_DIR}/.env.example." >&2
        fi
        exit 1
    fi

    if ! id "${RUNNER_USER}" >/dev/null 2>&1; then
        echo "ERROR: there is no account named '${RUNNER_USER}' on this host." >&2
        echo "  Create it in System Settings > Users & Groups, or pass --user." >&2
        exit 1
    fi
    # The monitor's database credential must not be readable by CI jobs, so
    # it cannot belong to the account the runner runs as.
    if [ "${MONITOR}" = true ] && [ "$(id -un)" = "${RUNNER_USER}" ]; then
        echo "ERROR: the runner runs as $(id -un), the account installing it, so the monitor cannot be installed" >&2
        echo "  from here: its credential would be readable by CI jobs. Pass --no-monitor, or run as another administrator." >&2
        exit 1
    fi
    RUNNER_HOME="$(dscl . -read "/Users/${RUNNER_USER}" NFSHomeDirectory 2>/dev/null | awk '{print $2}')"
    if [ -z "${RUNNER_HOME}" ] || [ ! -d "${RUNNER_HOME}" ]; then
        echo "ERROR: ${RUNNER_USER} has no home directory." >&2
        exit 1
    fi

    # read_env sources the env file with the invoking administrator's
    # privileges, so any account other than root or the administrator that can
    # change it — by owning it, by a group/world write bit, or by substituting a
    # symlink — is an arbitrary-code-execution vector. Refuse it here, the same
    # way register-daemon.sh refuses a plist others can change, before it is ever
    # sourced. A mode-0644 file owned by the runner account would pass a
    # write-bit-only check yet still be the runner's to edit, and a directory
    # above it that another account can write lets that account replace it.
    local admin_user env_bad
    admin_user="$(id -un)"
    env_bad="$(untrusted_ancestor "${admin_user}" "${ENV_FILE}")"
    if [ -n "${env_bad}" ]; then
        echo "ERROR: ${env_bad}, on the path to ${ENV_FILE}, is a symlink, is writable by" >&2
        echo "  others, or is owned by neither you (${admin_user}) nor root. The env file is" >&2
        echo "  sourced with your privileges, so anyone else who can change it, or the" >&2
        echo "  directories above it, could run commands as you." >&2
        echo "  Fix: sudo chown ${admin_user} ${env_bad} && chmod go-w ${env_bad}" >&2
        exit 1
    fi

    read_env "${ENV_FILE}"
    resolve_api_base
    # A named instance must carry its own RUNNER_NAME. The default name,
    # $(hostname)-macos, belongs to the default instance, and runner.sh registers
    # with config.sh --replace, so a second instance that fell back to it would
    # take over the first runner's GitHub registration instead of adding one.
    if [ -z "${ENV_RUNNER_NAME}" ] && [ -n "${INSTANCE}" ]; then
        echo "ERROR: instance '${INSTANCE}' needs its own RUNNER_NAME in ${ENV_FILE}." >&2
        echo "  Without it the runner would take the default name ($(hostname)-macos) and" >&2
        echo "  replace the default runner's GitHub registration. Set RUNNER_NAME and retry." >&2
        exit 1
    fi
    STAGE_DIR="${RUNNER_HOME}/ci-runner${SUFFIX}"
    RUNNER_DIR="${ENV_RUNNER_DIR:-${RUNNER_HOME}/actions-runner${SUFFIX}}"
    ENV_RUNNER_NAME="${ENV_RUNNER_NAME:-$(hostname)-macos}"
    RUNNER_PATH="${ENV_RUNNER_PATH:-${RUNNER_HOME}/.local/bin:/opt/homebrew/bin:/opt/homebrew/opt/openjdk@17/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin}"
    LOG_FILE="${STAGE_DIR}/runner.log"

    echo "  Service:      ${LABEL}, runs as ${RUNNER_USER}"
    echo "  Config:       ${ENV_FILE}"
    echo "  Runner name:  ${ENV_RUNNER_NAME}"
    echo "  Labels:       ${ENV_RUNNER_LABELS:-self-hosted,macos,ar-ci}"
    echo "  Runner dir:   ${RUNNER_DIR}"
    echo "  Stage dir:    ${STAGE_DIR}"

    local var value
    for var in GITHUB_PAT GITHUB_OWNER; do
        eval "value=\${ENV_${var}}"
        if [ -z "${value}" ] || [ "${value}" = "ghp_your_token_here" ]; then
            echo "  ✗ ${var} is not set in ${ENV_FILE}" >&2
            errors=$((errors + 1))
        fi
    done
    if [ -z "${API_BASE}" ]; then
        echo "  ✗ RUNNER_SCOPE must be 'repo' or 'org' (got '${ENV_RUNNER_SCOPE}')" >&2
        errors=$((errors + 1))
    elif [ "${ENV_RUNNER_SCOPE:-repo}" = "repo" ] && [ -z "${ENV_GITHUB_REPO}" ]; then
        echo "  ✗ GITHUB_REPO is required when RUNNER_SCOPE=repo" >&2
        errors=$((errors + 1))
    fi
    case "${RUNNER_DIR}" in
        /*) ;;
        *)
            echo "  ✗ RUNNER_DIR must be an absolute path (got '${RUNNER_DIR}')" >&2
            errors=$((errors + 1))
            ;;
    esac

    # One sudo prompt, up front, rather than partway through.
    sudo -v

    # The tools runner.sh and the jobs need, looked up the way the daemon
    # will look them up: as the runner account, with the daemon's PATH.
    local missing
    missing="$(sudo -u "${RUNNER_USER}" env -i HOME="${RUNNER_HOME}" PATH="${RUNNER_PATH}" /bin/bash -c '
        for c in java mvn curl jq git lsof; do command -v "$c" >/dev/null 2>&1 || printf "%s " "$c"; done
        major=$(java -version 2>&1 | sed -n "1s/.*\"\([0-9][0-9]*\).*/\1/p")
        [ -n "$major" ] && [ "$major" -ge 17 ] || printf "jdk17+ "' 2>/dev/null || true)"
    if [ -n "${missing}" ]; then
        echo "  ✗ not on ${RUNNER_USER}'s daemon PATH: ${missing}" >&2
        echo "      PATH=${RUNNER_PATH}" >&2
        echo "      Install with: brew install maven jq && brew install --cask temurin@17" >&2
        echo "      or set RUNNER_PATH in ${ENV_FILE} to where they are." >&2
        errors=$((errors + 1))
    else
        echo "  ✓ java, mvn, curl, jq, git, lsof found for ${RUNNER_USER}"
    fi

    # Those tools then run as the runner, from whichever RUNNER_PATH directory
    # has them first, so finding them is not enough: an entry such as /tmp/bin,
    # or one another account can write, would let that account put its own java
    # or mvn ahead of the real one between this check and the next job.
    local tool_bad
    # TODO(review): fails open if untrusted_search_path dies (e.g. bash 3.2 set -u on an empty parts array for a "/" entry); the <(...) exit status is never checked.
    while IFS= read -r tool_bad; do
        echo "  ✗ ${tool_bad}, on RUNNER_PATH, is not absolute, or can be written by an account other" >&2
        echo "      than root, ${RUNNER_USER} and the administrators; a program put there would run as ${RUNNER_USER}." >&2
        errors=$((errors + 1))
    done < <(untrusted_search_path "${RUNNER_USER}" "${RUNNER_PATH}" sudo)
    if ! xcodebuild -version >/dev/null 2>&1; then
        echo "  ! full Xcode is not selected; jobs that run xcodebuild will fail (see README, Prerequisites)"
    fi

    # register-daemon.sh accepts a plist only when nobody but root and its
    # owner can change it, which includes every directory above it.
    if [ -n "$(find "${RUNNER_HOME}" -maxdepth 0 -perm -g+w 2>/dev/null)$(find "${RUNNER_HOME}" -maxdepth 0 -perm -o+w 2>/dev/null)" ]; then
        echo "  ✗ ${RUNNER_HOME} is group- or world-writable; register-daemon.sh will refuse the plist" >&2
        echo "      Fix: sudo chmod go-w ${RUNNER_HOME}" >&2
        errors=$((errors + 1))
    fi

    # The runner directory must belong to the runner account (runner.sh
    # explains why at length); catching it here beats a retry loop in a log.
    if [ -d "${RUNNER_DIR}" ]; then
        local foreign
        foreign="$(sudo find "${RUNNER_DIR}" ! -user "${RUNNER_USER}" -print -quit 2>/dev/null || true)"
        if [ -n "${foreign}" ]; then
            echo "  ✗ ${RUNNER_DIR} holds files not owned by ${RUNNER_USER} (first: ${foreign})" >&2
            echo "      Fix: sudo chown -R ${RUNNER_USER} ${RUNNER_DIR}" >&2
            errors=$((errors + 1))
        fi
    fi

    # The daemon runs ${RUNNER_DIR}/run.sh as the runner. RUNNER_DIR may be set
    # to any absolute path, so — exactly as register-daemon.sh does for the plist
    # — every directory on the way to it must be writable by root and the runner
    # account alone: a writable or symlinked ancestor would let another account
    # swap RUNNER_DIR for one holding a hostile run.sh between this check and the
    # launch. sudo stats components under a home the administrator cannot enter.
    local bad
    bad="$(untrusted_ancestor "${RUNNER_USER}" "${RUNNER_DIR}" sudo)"
    if [ -n "${bad}" ]; then
        echo "  ✗ ${bad}, on the path to ${RUNNER_DIR}, is a symlink, is writable by others," >&2
        echo "      or is owned by neither root nor ${RUNNER_USER}; another account could swap" >&2
        echo "      ${RUNNER_DIR} for one holding a hostile run.sh that the daemon would run." >&2
        echo "      Keep ${RUNNER_DIR} under a path only root and ${RUNNER_USER} can write." >&2
        errors=$((errors + 1))
    fi

    # A runner started by hand from the same directory would fight the
    # daemon over one registration.
    if [ -z "$(service_state "${LABEL}")" ] && [ -n "$(runner_processes Listener)" ]; then
        echo "  ✗ a runner not managed by launchd is already running from ${RUNNER_DIR}:" >&2
        runner_processes Listener | sed 's/^/      /' >&2
        echo "      Stop it first (Ctrl+C in its terminal, or: pkill -INT -f 'runner\\.sh')." >&2
        errors=$((errors + 1))
    fi

    if [ ! -x "${REGISTER_SCRIPT}" ]; then
        echo "  ✗ ${REGISTER_SCRIPT} is missing; is this a complete checkout?" >&2
        errors=$((errors + 1))
    fi

    if [ "${MONITOR}" = true ]; then
        local store_url="${FLEET_HOME:-${HOME}/fleet}/store-url"
        if [ -s "${store_url}" ]; then
            echo "  ✓ monitor credential present (${store_url})"
        elif [ -n "${STORE_FROM}" ]; then
            echo "  ✓ monitor credential will be copied from ${STORE_FROM}"
        else
            echo "  ✗ the monitor needs ${store_url}; pass --store-from USER@HOST (a host that has it)," >&2
            echo "      or --no-monitor to install the runner alone" >&2
            errors=$((errors + 1))
        fi
    fi

    if [ "${errors}" -gt 0 ]; then
        echo "" >&2
        echo "Nothing was changed. Fix the ${errors} problem(s) above and run this again." >&2
        exit 1
    fi

    echo ""
    echo "Installing ${LABEL}"

    # ── Stage ──
    # Everything under STAGE_DIR is written as the runner, never through sudo:
    # see stage_file.
    sudo -u "${RUNNER_USER}" /bin/sh -c 'mkdir -p "$1/bin" && chmod 755 "$1" "$1/bin"' _ "${STAGE_DIR}"
    local script
    for script in runner.sh cpu-watcher.sh; do
        stage_file "${STAGE_DIR}/bin/${script}" 755 < "${SCRIPT_DIR}/${script}"
    done
    stage_file "${STAGE_DIR}/runner.env" 600 < "${ENV_FILE}"

    local rendered
    rendered="$(mktemp -t ci-runner-plist)"
    sed -e "s|@LABEL@|$(xml_value "${LABEL}")|g" \
        -e "s|@RUNNER_USER@|$(xml_value "${RUNNER_USER}")|g" \
        -e "s|@RUNNER_HOME@|$(xml_value "${RUNNER_HOME}")|g" \
        -e "s|@RUNNER_DIR@|$(xml_value "${RUNNER_DIR}")|g" \
        -e "s|@RUNNER_PATH@|$(xml_value "${RUNNER_PATH}")|g" \
        -e "s|@STAGE_DIR@|$(xml_value "${STAGE_DIR}")|g" \
        "${TEMPLATE}" > "${rendered}"
    plutil -lint -s "${rendered}"
    stage_file "${STAGE_DIR}/${LABEL}.plist" 644 < "${rendered}"
    rm -f "${rendered}"

    # ── Register ──
    # A runner stopped with `fleet macos stop` is disabled, which would make
    # the bootstrap inside register-daemon.sh fail; installing means running.
    sudo launchctl enable "system/${LABEL}"
    sudo "${REGISTER_SCRIPT}" "${LABEL}" "${STAGE_DIR}/${LABEL}.plist"
    wait_online

    # ── Monitor ──
    if [ "${MONITOR}" = true ]; then
        echo ""
        echo "Installing the fleet metrics collector"
        "${MONITOR_INSTALL}" ${STORE_FROM:+--store-from "${STORE_FROM}"}
    fi

    echo ""
    echo "Done. Check on it with: tools/bin/fleet macos status${INSTANCE:+ --instance ${INSTANCE}}"
}

# Escapes a value for an XML text node, then for the sed replacement.
xml_value() {
    printf '%s' "$1" \
        | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' \
        | sed -e 's/[\\&|]/\\&/g'
}

# Waits for GitHub to list the runner online, so a successful install means a
# runner that can take jobs, not merely a process launchd started.
wait_online() {
    if ! command -v jq >/dev/null 2>&1 || ! command -v curl >/dev/null 2>&1; then
        echo "  (jq and curl are not both on your PATH, so not checking GitHub; see the log: ${LOG_FILE})"
        return 0
    fi
    echo "Waiting for ${ENV_RUNNER_NAME} to come online in GitHub..."
    local waited=0 status
    while [ "${waited}" -lt "${ONLINE_TIMEOUT_SECONDS}" ]; do
        status="$(github_status)"
        case "${status}" in
            online*)
                echo "  ✓ ${ENV_RUNNER_NAME}: ${status}"
                return 0
                ;;
        esac
        sleep 5
        waited=$((waited + 5))
    done
    echo "ERROR: ${ENV_RUNNER_NAME} is not online in GitHub after ${ONLINE_TIMEOUT_SECONDS}s. Last lines of ${LOG_FILE}:" >&2
    # As the runner, whose file it is, so a log the runner swapped for a
    # symlink cannot make root read something else onto this terminal.
    sudo -u "${RUNNER_USER}" tail -n 30 "${LOG_FILE}" >&2 || true
    exit 1
}

# ---------- start / stop ----------

cmd_start() {
    load_installed
    if [ -n "$(service_state "${LABEL}")" ]; then
        echo "${LABEL} is already running."
    else
        sudo launchctl enable "system/${LABEL}"
        sudo launchctl bootstrap system "${INSTALLED_PLIST}"
        echo "Started ${LABEL}."
    fi
    if [ -f "${DAEMONS_DIR}/${MONITOR_LABEL}.plist" ] && [ -z "$(service_state "${MONITOR_LABEL}")" ]; then
        sudo launchctl bootstrap system "${DAEMONS_DIR}/${MONITOR_LABEL}.plist"
        echo "Started ${MONITOR_LABEL}."
    fi
}

cmd_stop() {
    load_installed
    if [ -z "$(service_state "${LABEL}")" ]; then
        echo "${LABEL} is not running."
        return 0
    fi
    if runner_busy; then
        if [ "${IF_IDLE}" = true ]; then
            echo "Not stopping: ${LABEL} is running a job."
            return 1
        fi
        echo "NOTE: ${LABEL} is running a job; it will be cancelled. Runners are"
        echo "  ephemeral, so stopping once it finishes costs nothing:"
        echo "    tools/bin/fleet macos stop${INSTANCE:+ --instance ${INSTANCE}} --if-idle"
    fi
    # bootout sends runner.sh SIGTERM; its trap stops the agent and
    # deregisters the runner from GitHub before it exits. disable keeps
    # launchd from loading the plist again at the next boot.
    echo "Stopping ${LABEL} (deregistering from GitHub)..."
    sudo launchctl disable "system/${LABEL}"
    sudo launchctl bootout "system/${LABEL}" || true
    if [ -n "$(service_state "${LABEL}")" ]; then
        echo "WARNING: ${LABEL} is still listed by launchd." >&2
        return 1
    fi
    echo "Stopped. It stays stopped across reboots until: tools/bin/fleet macos start${INSTANCE:+ --instance ${INSTANCE}}"
}

cmd_uninstall() {
    cmd_stop
    sudo rm -f "${INSTALLED_PLIST}"
    echo "Removed ${INSTALLED_PLIST}. Left in place: ${STAGE_DIR}, ${RUNNER_DIR}, and the monitor."
}

# ---------- status / logs ----------

# Describes service $1's launchd state. $2 is the command that would start it,
# shown when it is installed but stopped; a named instance needs its --instance
# selector in that command, so the caller passes the right one rather than this
# printing a bare `start` that would act on the default instance.
describe_service() {
    local state pid
    read -r state pid <<EOF2
$(service_state "$1")
EOF2
    if [ -n "${state}" ]; then
        echo "  launchd:  ${state}${pid:+, pid ${pid}}"
    elif [ -f "${DAEMONS_DIR}/$1.plist" ]; then
        echo "  launchd:  stopped (installed; start with: $2)"
    else
        echo "  launchd:  not installed"
    fi
}

cmd_status() {
    load_installed
    echo "Runner ${LABEL} (runs as ${RUNNER_USER})"
    describe_service "${LABEL}" "tools/bin/fleet macos start${INSTANCE:+ --instance ${INSTANCE}}"
    if [ -n "$(runner_processes Listener)" ]; then
        if runner_busy; then
            echo "  activity: running a job"
        else
            echo "  activity: idle, waiting for a job"
        fi
    fi
    # status sources the env file with the invoker's privileges just as
    # install does, so it holds the file to the same standard.
    local env_bad
    env_bad="$(untrusted_ancestor "$(id -un)" "${ENV_FILE}")"
    if [ -n "${env_bad}" ]; then
        echo "  GitHub:   not checked; ${env_bad} can be changed by another account, so ${ENV_FILE} is not read"
    elif [ -r "${ENV_FILE}" ]; then
        read_env "${ENV_FILE}"
        resolve_api_base
        ENV_RUNNER_NAME="${ENV_RUNNER_NAME:-$(hostname)-macos}"
        local status
        status="$(github_status)"
        echo "  GitHub:   ${ENV_RUNNER_NAME}: ${status:-not registered}"
    fi
    echo "  log:      ${LOG_FILE}"
    echo ""
    echo "Monitor ${MONITOR_LABEL}"
    # The monitor is a single host-wide service, not one per instance, so its
    # start command carries no --instance selector.
    describe_service "${MONITOR_LABEL}" "tools/bin/fleet macos start"
}

cmd_logs() {
    load_installed
    # The log lives in the runner-owned stage directory, whose home may not be
    # traversable by the administrator and where the runner could put a symlink
    # in the log's place. Read it as the runner, the same trust boundary
    # wait_online uses, rather than following that link as the administrator.
    if [ "${FOLLOW}" = true ]; then
        exec sudo -u "${RUNNER_USER}" tail -n 50 -f "${LOG_FILE}"
    fi
    sudo -u "${RUNNER_USER}" tail -n 50 "${LOG_FILE}"
}

# ---------- Dispatch ----------

case "${COMMAND}" in
    install)   cmd_install ;;
    uninstall) cmd_uninstall ;;
    start)     cmd_start ;;
    stop)      cmd_stop ;;
    restart)   cmd_stop; echo; cmd_start ;;
    status)    cmd_status ;;
    logs)      cmd_logs ;;
    *) echo "Unknown command: ${COMMAND}" >&2; echo >&2; usage >&2; exit 2 ;;
esac
