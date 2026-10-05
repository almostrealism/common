#!/bin/bash
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

# The interpreter is /bin/bash by absolute path, never looked up on PATH, and
# PATH itself is anchored to the system directories below before any command
# runs: this script resolves sudo, dscl, find, cp, mktemp, launchctl, plutil
# and the rest by name, and the administrator's inherited PATH is otherwise
# unscreened, so a directory another account could write, placed earlier on it,
# could shadow one of those commands and run in the administrator's context
# before any trust check. The system directories are prepended rather than
# substituted so a python3 that lives outside them is still found; the host
# python3 is the one program this script looks up rather than runs from a fixed
# location, and it is screened with untrusted_program wherever it resolves.
PATH="/usr/bin:/bin:/usr/sbin:/sbin:${PATH}"
export PATH

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
REGISTER_SCRIPT="${CHECKOUT}/flowtree/runtime/agent/macos/register-daemon.sh"
MONITOR_INSTALL="${CHECKOUT}/tools/fleet/launchd/install.sh"
MONITOR_RENDER="${CHECKOUT}/tools/fleet/launchd/render.sh"
# render.sh reads these two plist templates from its own directory as the
# administrator and renders them into FLEET_HOME; a tampered template could
# inject launchd keys (ProgramArguments and the like) into an admin-owned
# plist that register-daemon.sh then accepts, so they are screened alongside
# the scripts that read them.
MONITOR_COLLECTOR_TEMPLATE="${CHECKOUT}/tools/fleet/launchd/com.almostrealism.fleet-collector.plist"
MONITOR_POLLER_TEMPLATE="${CHECKOUT}/tools/fleet/launchd/com.almostrealism.fleet-poller.plist"
TEMPLATE="${SCRIPT_DIR}/com.almostrealism.ci-runner.plist"
PLISTBUDDY="/usr/libexec/PlistBuddy"
DAEMONS_DIR="/Library/LaunchDaemons"
LABEL_BASE="com.almostrealism.ci-runner"
MONITOR_LABEL="com.almostrealism.fleet-collector"
ADMIN_GROUP="admin"
ONLINE_TIMEOUT_SECONDS=120
# The programs runner.sh and the jobs need on the daemon's PATH.
REQUIRED_TOOLS="java mvn curl jq git lsof"

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

# Echoes PATH when an ACL entry allows a subject one of RIGHTS, a
# '|'-separated list of ACL right names, and nothing otherwise. `ls -e` lists
# each ACL entry after the mode line as " N: <who> [inherited] allow|deny
# <rights,...>"; only an allow entry counts (a deny entry, like the "everyone
# deny delete" macOS puts on a home directory, does not). A host whose `ls` has
# no `-e` has no such ACLs to read, so nothing is reported. PRIV is as for
# untrusted_path.
acl_grant() {
    local rights="$1" path="$2" priv="${3:-}"
    ${priv} ls -lde "${path}" 2>/dev/null | awk -v p="${path}" -v r="(^|,)(${rights})(,|$)" '
        NR > 1 { i = ($3 == "inherited") ? 4 : 3 }
        NR > 1 && $i == "allow" && $(i + 1) ~ r { print p; exit }' || true
}

# Echoes PATH when an ACL entry grants a subject a right that lets them change
# or replace it even with the mode bits clear — the gap a mode-only check leaves
# open on macOS, closed the same way register-daemon.sh closes it on the plist
# path. PRIV is as for untrusted_path.
acl_write_grant() {
    acl_grant "$(acl_write_rights)" "$1" "${2:-}"
}

# The ACL right names, '|'-separated as acl_grant takes them, that let a subject
# change or replace a file or directory.
acl_write_rights() {
    echo "write|delete|delete_child|append|add_file|add_subdirectory|writeattr|writeextattr|writesecurity|chown"
}

# Echoes the `ls` line of the first file or directory in the tree at DIR, other
# than links and anything under PRUNE, that carries an ACL entry allowing a
# subject one of the acl_write_rights — the gap a mode-bit scan of the tree
# leaves open, as acl_write_grant closes it for a single path — and nothing when
# none does. A listing that cannot be made reports DIR rather than passing it.
# PRIV is as for untrusted_path.
acl_write_grant_tree() {
    local dir="$1" prune="$2" priv="${3:-}" listing
    listing="$(${priv} find "${dir}" -path "${prune}" -prune -o ! -type l -print0 \
        | ${priv} xargs -0 ls -lde)" || { echo "${dir}"; return 0; }
    awk -v r="(^|,)($(acl_write_rights))(,|$)" '
        !/^ [0-9]+: / { entry = $0; next }
        { i = ($3 == "inherited") ? 4 : 3 }
        $i == "allow" && $(i + 1) ~ r { print entry; exit }' <<EOF
${listing}
EOF
}

# Echoes PATH when an account other than root or OWNER could change it — when it
# is a symlink, is group- or world-writable, is owned by a third account, or
# carries a write-granting ACL entry — and nothing when only root and OWNER can.
# A find that cannot run — the probe this check is built on — reports the path
# rather than passing it, so a probe that fails to execute can never read as
# trusted; untrusted_tool_dir fails closed the same way.
# PRIV is "sudo" to reach a path under a service account's home the invoker
# cannot stat, or empty to stat as the invoker. This is the check
# register-daemon.sh makes on every component of the plist's path, applied here
# to the files fleet.sh trusts before it is reached.
untrusted_path() {
    local owner="$1" path="$2" priv="${3:-}" bad
    bad="$(${priv} find "${path}" -maxdepth 0 \
        \( -type l -o -perm -g+w -o -perm -o+w \
           -o \( ! -user "${owner}" -a ! -user root \) \) 2>/dev/null)" || bad="${path}"
    [ -n "${bad}" ] || bad="$(acl_write_grant "${path}" "${priv}")"
    [ -z "${bad}" ] || echo "${path}"
}

# Echoes PATH when a credential file there could be read or changed by an
# account other than root or OWNER: when untrusted_path reports it, when a group
# or world read bit is set, or when an ACL entry allows another subject to read
# it. Nothing when only root and OWNER can. A probe that cannot run reports the
# path, as untrusted_path does.
exposed_secret() {
    local owner="$1" path="$2" bad
    bad="$(untrusted_path "${owner}" "${path}")"
    [ -n "${bad}" ] \
        || bad="$(find "${path}" -maxdepth 0 \( -perm -g+r -o -perm -o+r \) 2>/dev/null)" \
        || bad="${path}"
    [ -n "${bad}" ] || bad="$(acl_grant "read" "${path}")"
    [ -z "${bad}" ] || echo "${path}"
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
    # ${parts[@]+…} guards the empty-array case: a "/" path leaves parts empty,
    # and expanding an empty array under `set -u` is an unbound-variable error
    # on the bash macOS ships (3.2), which would abort the walk mid-stream.
    for component in ${parts[@]+"${parts[@]}"}; do
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
# than ADMIN_GROUP, owned by a third account, or carries a write-granting ACL
# entry — and nothing otherwise. Looser
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
    [ -n "${out}" ] || out="$(acl_write_grant "${path}" "${priv}")"
    [ -z "${out}" ] || echo "${out}"
}

# Echoes the first file or directory anywhere in the tree at DIR, links aside,
# that an account other than root, OWNER or an administrator could change — one
# untrusted_tool_dir would flag by its mode or owner, or one a write-granting
# ACL entry exposes — and nothing when the whole tree is safe. This is
# untrusted_tool_dir applied to every entry at once: the monitor install
# imports and runs the Python under tools/fleet as the administrator, so a
# single writable module anywhere in that tree is a code path that runs as
# them. Links are skipped as the runner-tree scan skips them — their own mode
# bits mean nothing, and a link can only be planted by whoever can write the
# directory holding it, which the scan reaches on its own. A scan that cannot
# run reports DIR rather than passing it, as acl_write_grant_tree does.
# PRIV is as for untrusted_path.
untrusted_tool_tree() {
    local owner="$1" dir="$2" priv="${3:-}" member out
    local -a owners=(! -user root ! -user "${owner}")
    for member in $(admin_members); do
        owners+=(! -user "${member}")
    done
    out="$(${priv} find -H "${dir}" ! -type l \
        \( -perm -o+w -o \( -perm -g+w ! -group "${ADMIN_GROUP}" \) \
           -o \( "${owners[@]}" \) \) -print -quit 2>/dev/null)" || out="${dir}"
    [ -n "${out}" ] || out="$(acl_write_grant_tree "${dir}" "" "${priv}")"
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
    # `read -a` drops a trailing empty field, so "/usr/bin:" would split to
    # just /usr/bin; that trailing entry (or an empty search) is the current
    # directory all the same, and is reported here rather than lost.
    case "${search}" in
        ""|*:) echo "(empty entry)" ;;
    esac
    IFS=':' read -r -a dirs <<< "${search}"
    # ${dirs[@]+…} guards the empty-array case the same way untrusted_ancestor
    # does: an empty SEARCH would otherwise abort under `set -u` on bash 3.2.
    for dir in ${dirs[@]+"${dirs[@]}"}; do
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

# Echoes the nearest existing ancestor of PATH — PATH itself when it exists —
# descending through directories that do not exist yet. runner.sh creates both
# RUNNER_DIR and RUNNER_WORKDIR with `mkdir -p`, which needs write and search
# access on this directory to create the path below it. Stats through sudo so a
# path under a home the administrator cannot enter still resolves.
nearest_existing_dir() {
    local dir="$1"
    while ! sudo test -d "${dir}" && [ "${dir}" != "/" ]; do
        dir="$(dirname "${dir}")"
    done
    echo "${dir}"
}

# Echoes one line for each reason the runner account could not be trusted to
# create and use the directory at PATH, which NAME names in the message, and
# nothing when it can: PATH is not absolute; a component on the way to it is a
# symlink, writable by others, or owned by neither root nor RUNNER_USER, so
# another account could swap it for one of its own; it exists as something other
# than a directory; or the nearest existing directory is one RUNNER_USER cannot
# create in. Each is a directory runner.sh or this install makes with mkdir -p
# as the runner, so the write probe runs as the runner rather than as root.
runner_dir_problems() {
    local name="$1" path="$2" bad at
    case "${path}" in
        /*) ;;
        *) echo "${name} (${path}) must be an absolute path"; return 0 ;;
    esac
    bad="$(untrusted_ancestor "${RUNNER_USER}" "${path}" sudo)"
    if [ -n "${bad}" ]; then
        echo "${bad}, on the path to ${name} (${path}), is a symlink, is writable by others, or is owned by neither root nor ${RUNNER_USER}; another account could swap what the runner keeps and runs there. Keep it under a path only root and ${RUNNER_USER} can write."
    elif sudo test -e "${path}" && ! sudo test -d "${path}"; then
        echo "${name} (${path}) exists but is not a directory, so the runner's mkdir -p there would fail. Remove or relocate it."
    else
        at="$(nearest_existing_dir "${path}")"
        sudo -u "${RUNNER_USER}" /bin/sh -c 'test -w "$1" && test -x "$1"' _ "${at}" \
            || echo "${RUNNER_USER} cannot create ${name} (${path}): ${at}, the nearest existing directory, is not writable by it. Fix: sudo chown ${RUNNER_USER} ${at}, or choose a path it can create."
    fi
}

# Echoes the first place on the way to the program at the absolute PATH where an
# account other than root, OWNER or an administrator could change what runs: a
# directory or file, judged as untrusted_tool_dir judges them, along PATH itself,
# along every symlink it passes through, and along what it finally resolves to.
# A trusted directory can still hold a writable program, or a link to one
# somewhere else, so screening the search path alone is not enough. A program
# whose links cannot be followed (a loop, a dangling link) is reported, never
# passed. Nothing when the program is safe. PRIV is as for untrusted_path.
untrusted_program() {
    local owner="$1" path="$2" priv="${3:-}" hops hop bad
    hops="$(${priv} /bin/sh -c '
        p=$1 n=0
        while :; do
            d=$(cd -P "$(dirname "$p")" 2>/dev/null && pwd -P) || exit 1
            c="${d%/}/$(basename "$p")"
            echo "$c"
            [ "$c" = "$p" ] || echo "$p"
            p=$c
            [ -L "$p" ] || break
            n=$((n + 1))
            [ "$n" -le 40 ] || exit 1
            t=$(readlink "$p") || exit 1
            case "$t" in
                /*) p=$t ;;
                *) p="${d%/}/$t" ;;
            esac
        done
        [ -e "$p" ] || exit 1' _ "${path}")" || { echo "${path}"; return 0; }
    while IFS= read -r hop; do
        bad="$(untrusted_ancestor "${owner}" "${hop}" "${priv}" untrusted_tool_dir)"
        if [ -n "${bad}" ]; then
            echo "${bad}"
            return 0
        fi
    done <<EOF
${hops}
EOF
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
# and not the administrator's. ENV_PATH_OVERRIDE is the PATH the file leaves
# behind when it assigns one, and empty when it does not: runner.sh sources the
# staged copy after launchd has set the screened RUNNER_PATH, so a PATH in the
# file would replace it.
read_env() {
    local values
    values="$(env -i HOME="${RUNNER_HOME}" PATH=/usr/bin:/bin /bin/bash -c '
        set -a
        . "$1" >/dev/null
        for v in GITHUB_PAT GITHUB_OWNER GITHUB_REPO RUNNER_SCOPE RUNNER_NAME RUNNER_DIR RUNNER_WORKDIR RUNNER_LABELS RUNNER_PATH; do
            eval "x=\${$v-}"
            printf "ENV_%s=%q\n" "$v" "$x"
        done
        [ "${PATH}" = /usr/bin:/bin ] && x="" || x="${PATH}"
        printf "ENV_PATH_OVERRIDE=%q\n" "$x"' _ "$1")" || {
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
    local admin_user
    admin_user="$(id -un)"

    echo "Preflight"

    # The env file is the operator's one piece of configuration.
    if [ ! -f "${ENV_FILE}" ]; then
        if [ "${ENV_FILE}" = "${SCRIPT_DIR}/.env" ] && [ -f "${SCRIPT_DIR}/.env.example" ]; then
            # cp runs with the administrator's privileges and follows a symlink
            # at ENV_FILE, so the path it writes through must be screened first —
            # the same walk the existing env file gets below. A dangling symlink
            # here, or a checkout under a parent another account can write, would
            # otherwise let that account redirect the administrator's cp onto a
            # path of its choosing; the trust check after the copy is too late.
            local create_bad
            create_bad="$(untrusted_ancestor "${admin_user}" "${ENV_FILE}")"
            if [ -n "${create_bad}" ]; then
                echo "ERROR: ${create_bad}, on the path to ${ENV_FILE}, is a symlink, is writable by" >&2
                echo "  others, or is owned by neither you (${admin_user}) nor root; it is not safe to create" >&2
                echo "  the env file there. Fix that component, or create a trusted ${ENV_FILE} yourself." >&2
                exit 1
            fi
            # The destination walk above keeps the cp from being redirected, but
            # the template it reads from is a trust input of its own: its
            # contents become ${ENV_FILE}, which a later invocation sources with
            # the administrator's privileges. A .env.example that is a symlink,
            # sits under a parent another account can write, or is owned by
            # neither you nor root is attacker-controlled — it could carry shell
            # commands that read_env then runs as you, and the owner-only mode
            # 600 .env this copy leaves behind sails through every later check.
            # Screen the source the same way the staged sources are, before cp
            # ever reads it.
            local template_bad
            template_bad="$(untrusted_ancestor "${admin_user}" "${SCRIPT_DIR}/.env.example")"
            if [ -n "${template_bad}" ]; then
                echo "ERROR: ${template_bad}, on the path to ${SCRIPT_DIR}/.env.example, is a symlink, is" >&2
                echo "  writable by others, or is owned by neither you (${admin_user}) nor root; its" >&2
                echo "  contents would be sourced as you out of the ${ENV_FILE} this creates. Fix that" >&2
                echo "  component, or create a trusted ${ENV_FILE} yourself." >&2
                exit 1
            fi
            cp "${SCRIPT_DIR}/.env.example" "${ENV_FILE}"
            # The template becomes the home of GITHUB_PAT once filled in, so
            # create it owner-only rather than at the copy's default mode.
            chmod 600 "${ENV_FILE}"
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
    # Stat the home through sudo: a service account's home is commonly not
    # traversable by the administrator, so an unprivileged `[ -d ]` would read a
    # real home as missing and reject a supported setup. The rest of the
    # preflight already uses sudo for the paths under this home.
    if [ -z "${RUNNER_HOME}" ] || ! sudo test -d "${RUNNER_HOME}"; then
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
    local env_bad
    env_bad="$(untrusted_ancestor "${admin_user}" "${ENV_FILE}")"
    if [ -n "${env_bad}" ]; then
        echo "ERROR: ${env_bad}, on the path to ${ENV_FILE}, is a symlink, is writable by" >&2
        echo "  others, or is owned by neither you (${admin_user}) nor root. The env file is" >&2
        echo "  sourced with your privileges, so anyone else who can change it, or the" >&2
        echo "  directories above it, could run commands as you." >&2
        echo "  Fix: sudo chown ${admin_user} ${env_bad} && chmod go-w ${env_bad}" >&2
        exit 1
    fi

    # The walk above keeps another account from *changing* the env file, but it
    # deliberately allows a readable one. This file holds GITHUB_PAT, the
    # long-lived registration token, so a group- or world-readable env file (the
    # mode a plain `cp .env.example .env` leaves behind) lets any local account —
    # the runner that runs CI jobs among them — read the token straight out of
    # the checkout, even though the staged copy is written mode 600. Hold the
    # file itself, not its directories, to the credential standard store-url gets.
    if [ -n "$(exposed_secret "${admin_user}" "${ENV_FILE}")" ]; then
        echo "ERROR: ${ENV_FILE} can be read by an account other than you (${admin_user}) and root." >&2
        echo "  It holds GITHUB_PAT, so any local account — including the runner account that runs CI" >&2
        echo "  jobs — could read the registration token out of the checkout." >&2
        echo "  Fix: chmod 600 ${ENV_FILE} (and remove any ACL: chmod -N ${ENV_FILE})." >&2
        exit 1
    fi

    read_env "${ENV_FILE}"
    resolve_api_base
    # The daemon's PATH is RUNNER_PATH, screened below and rendered into the
    # plist; runner.sh then sources the staged env file on top of it, so a PATH
    # assigned there would quietly replace the screened one with an unchecked
    # one for runner.sh and every job.
    if [ -n "${ENV_PATH_OVERRIDE}" ]; then
        echo "ERROR: ${ENV_FILE} sets PATH (to ${ENV_PATH_OVERRIDE}). The runner's PATH must come from" >&2
        echo "  RUNNER_PATH, which install screens; runner.sh would otherwise replace it with this unchecked" >&2
        echo "  one. Remove the PATH line and set RUNNER_PATH instead." >&2
        exit 1
    fi
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
        for c in "$@"; do command -v "$c" >/dev/null 2>&1 || printf "%s " "$c"; done
        major=$(java -version 2>&1 | sed -n "1s/.*\"\([0-9][0-9]*\).*/\1/p")
        [ -n "$major" ] && [ "$major" -ge 17 ] || printf "jdk17+ "' _ ${REQUIRED_TOOLS} 2>/dev/null || true)"
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
    # Captured with its exit status, rather than read through `done < <(...)`
    # whose failure the loop never sees: a check that dies — say on a bash that
    # aborts the walk — then counts as an error instead of waving every entry
    # through unscreened.
    local tool_bad path_untrusted path_status=0
    path_untrusted="$(untrusted_search_path "${RUNNER_USER}" "${RUNNER_PATH}" sudo)" || path_status=$?
    if [ "${path_status}" -ne 0 ]; then
        echo "  ✗ could not screen RUNNER_PATH (${RUNNER_PATH}); the trust check exited ${path_status}." >&2
        echo "      Refusing to render an unscreened PATH into the daemon." >&2
        errors=$((errors + 1))
    elif [ -n "${path_untrusted}" ]; then
        while IFS= read -r tool_bad; do
            echo "  ✗ ${tool_bad}, on RUNNER_PATH, is not absolute, or can be written by an account other" >&2
            echo "      than root, ${RUNNER_USER} and the administrators; a program put there would run as ${RUNNER_USER}." >&2
            errors=$((errors + 1))
        done <<EOF
${path_untrusted}
EOF
    fi

    # A trusted directory can still hold a program another account can rewrite,
    # or a link to one elsewhere, so each program the daemon will actually run is
    # screened too — the file, and every link on the way to it. A relative result
    # comes from a relative RUNNER_PATH entry, which the screen above reports.
    local programs program program_bad
    programs="$(sudo -u "${RUNNER_USER}" env -i HOME="${RUNNER_HOME}" PATH="${RUNNER_PATH}" /bin/bash -c '
        for c in "$@"; do command -v "$c" 2>/dev/null || true; done' _ ${REQUIRED_TOOLS} || true)"
    while IFS= read -r program; do
        case "${program}" in
            /*) ;;
            *) continue ;;
        esac
        program_bad="$(untrusted_program "${RUNNER_USER}" "${program}" sudo)"
        if [ -n "${program_bad}" ]; then
            echo "  ✗ ${program_bad}, on the way to ${program}, can be changed by an account other than" >&2
            echo "      root, ${RUNNER_USER} and the administrators, or its links cannot be followed;" >&2
            echo "      the daemon would run whatever it holds as ${RUNNER_USER}." >&2
            errors=$((errors + 1))
        fi
    done <<EOF
${programs}
EOF
    # Resolved by absolute path, not through the administrator's inherited PATH:
    # this preflight runs as the administrator, so a PATH entry another account
    # can write would let that account run its own xcodebuild as the
    # administrator. /usr/bin/xcodebuild is the OS shim that honors the selected
    # developer directory, and it is the only xcodebuild a job would ever use.
    if ! /usr/bin/xcodebuild -version >/dev/null 2>&1; then
        echo "  ! full Xcode is not selected; jobs that run xcodebuild will fail (see README, Prerequisites)"
    fi

    # register-daemon.sh accepts a plist only when nobody but root and its
    # owner can change it, which includes every directory above it.
    if [ -n "$(find "${RUNNER_HOME}" -maxdepth 0 -perm -g+w 2>/dev/null)$(find "${RUNNER_HOME}" -maxdepth 0 -perm -o+w 2>/dev/null)" ]; then
        echo "  ✗ ${RUNNER_HOME} is group- or world-writable; register-daemon.sh will refuse the plist" >&2
        echo "      Fix: sudo chmod go-w ${RUNNER_HOME}" >&2
        errors=$((errors + 1))
    fi

    # A RUNNER_DIR that already exists as something other than a directory — a
    # regular file, most likely — slips past every check below: the ownership
    # and write-bit blocks are gated on [ -d ], the ancestor walk treats the
    # file as a trusted leaf when the runner owns it, and the write probe falls
    # back to its parent. runner.sh then runs `mkdir -p "${RUNNER_DIR}"`, which
    # fails on a non-directory, and launchd retries until wait_online times out.
    # sudo stats it so a path under a home the administrator cannot enter still
    # reads correctly.
    if sudo test -e "${RUNNER_DIR}" && ! sudo test -d "${RUNNER_DIR}"; then
        echo "  ✗ ${RUNNER_DIR} exists but is not a directory; runner.sh runs mkdir -p there" >&2
        echo "      and would fail, leaving the daemon waiting for a runner that never registers." >&2
        echo "      Fix: remove or relocate ${RUNNER_DIR}, or set RUNNER_DIR to a directory path." >&2
        errors=$((errors + 1))
    fi

    # The runner directory must belong to the runner account (runner.sh
    # explains why at length); catching it here beats a retry loop in a log.
    # sudo, like the finds inside, so a RUNNER_DIR under a home the
    # administrator cannot enter is still scanned rather than skipped.
    if sudo test -d "${RUNNER_DIR}"; then
        local foreign
        foreign="$(sudo find "${RUNNER_DIR}" ! -user "${RUNNER_USER}" -print -quit 2>/dev/null || true)"
        if [ -n "${foreign}" ]; then
            echo "  ✗ ${RUNNER_DIR} holds files not owned by ${RUNNER_USER} (first: ${foreign})" >&2
            echo "      Fix: sudo chown -R ${RUNNER_USER} ${RUNNER_DIR}" >&2
            errors=$((errors + 1))
        fi
        # Owned by the runner is not enough when a group or world write bit lets
        # another account rewrite run.sh, or anything it runs, under the daemon.
        # _work holds the jobs' checkouts, which each job replaces anyway; links
        # are skipped because their own mode bits mean nothing.
        local writable
        writable="$(sudo find "${RUNNER_DIR}" -path "${RUNNER_DIR}/_work" -prune \
            -o ! -type l \( -perm -g+w -o -perm -o+w \) -print -quit 2>/dev/null || true)"
        if [ -n "${writable}" ]; then
            echo "  ✗ ${RUNNER_DIR} holds files others can write (first: ${writable})" >&2
            echo "      Fix: sudo chmod -R go-w ${RUNNER_DIR}" >&2
            errors=$((errors + 1))
        fi
        # Mode bits are not the whole story on macOS: an ACL entry can let another
        # account rewrite a mode-755 run.sh just the same, so the same tree is
        # scanned for write-granting ACL entries.
        local acl_writable
        acl_writable="$(acl_write_grant_tree "${RUNNER_DIR}" "${RUNNER_DIR}/_work" sudo)"
        if [ -n "${acl_writable}" ]; then
            echo "  ✗ ${RUNNER_DIR} holds a file an ACL lets another account write (first: ${acl_writable})" >&2
            echo "      Fix: sudo chmod -R -N ${RUNNER_DIR}" >&2
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

    # Each runner keeps its registration state (.runner, config.sh, _work) in its
    # own RUNNER_DIR, and the suffix that keeps instances apart is applied only
    # when RUNNER_DIR falls back to the default. A custom ENV_RUNNER_DIR can
    # therefore name a directory another installed instance already uses; with
    # the other service stopped there is no listener to detect, so both installs
    # succeed, then share .runner/config.sh and config.sh --replace makes each
    # registration overwrite the other's. Reject a RUNNER_DIR already registered
    # to a different service's plist.
    local other_plist other_dir
    for other_plist in "${DAEMONS_DIR}/${LABEL_BASE}"*.plist; do
        [ -e "${other_plist}" ] || continue
        [ "${other_plist}" = "${INSTALLED_PLIST}" ] && continue
        other_dir="$(plist_value "${other_plist}" ProgramArguments:3)"
        if [ -n "${other_dir}" ] && [ "${other_dir}" = "${RUNNER_DIR}" ]; then
            echo "  ✗ ${RUNNER_DIR} is already the runner directory of ${other_plist##*/}; two runners" >&2
            echo "      sharing one directory would overwrite each other's GitHub registration." >&2
            echo "      Give this instance its own RUNNER_DIR, or unset RUNNER_DIR to use the derived default." >&2
            errors=$((errors + 1))
        fi
    done

    # runner.sh checks out and runs every job in RUNNER_WORKDIR, defaulting to
    # ${RUNNER_DIR}/_work when the env file leaves it unset, and runs mkdir -p on
    # it unconditionally; the default is screened as much as a custom one, since
    # the RUNNER_DIR walk above does not cover _work itself. The stage directory
    # is created as the runner by this install, under the runner's home, and
    # receives runner.env — GITHUB_PAT — before register-daemon.sh ever sees the
    # path. Both get runner_dir_problems: a symlinked or other-writable directory
    # on the way would let another account redirect the staged credential or
    # change what jobs run, and a path the runner cannot create fails only after
    # this preflight has passed.
    local runner_workdir="${ENV_RUNNER_WORKDIR:-${RUNNER_DIR}/_work}" problem
    while IFS= read -r problem; do
        [ -n "${problem}" ] || continue
        echo "  ✗ ${problem}" >&2
        errors=$((errors + 1))
    done <<EOF
$(runner_dir_problems RUNNER_WORKDIR "${runner_workdir}")
$(runner_dir_problems "the stage directory" "${STAGE_DIR}")
EOF

    # Trust is necessary but not sufficient: runner.sh runs as ${RUNNER_USER}
    # and must create ${RUNNER_DIR}/config.sh and _work. A runner directory the
    # runner account cannot write — owned by root, or by the runner with its own
    # write bit cleared — passes every check above yet leaves the daemon waiting
    # for a runner that can never register. A ${RUNNER_DIR} that does not exist
    # yet is created with mkdir -p, which needs the same write access on the
    # nearest directory that does exist. Probe as the runner so the answer is
    # the daemon's, not root's; the walk up stats through sudo for the same
    # reason the ownership scan does.
    local writable_at
    writable_at="$(nearest_existing_dir "${RUNNER_DIR}")"
    if ! sudo -u "${RUNNER_USER}" /bin/sh -c 'test -w "$1" && test -x "$1"' _ "${writable_at}"; then
        if [ "${writable_at}" = "${RUNNER_DIR}" ]; then
            echo "  ✗ ${RUNNER_USER} cannot write to ${RUNNER_DIR}; runner.sh could not create" >&2
            echo "      config.sh there, and the daemon would wait for a runner that never registers." >&2
            echo "      Fix: sudo chown ${RUNNER_USER} ${RUNNER_DIR} && sudo chmod u+rwx ${RUNNER_DIR}" >&2
        else
            echo "  ✗ ${RUNNER_DIR} does not exist and ${RUNNER_USER} cannot create it: the nearest" >&2
            echo "      existing directory ${writable_at} is not writable by ${RUNNER_USER}, so the" >&2
            echo "      daemon would wait for a runner that never registers." >&2
            echo "      Fix: sudo chown ${RUNNER_USER} ${writable_at}, or choose a RUNNER_DIR the" >&2
            echo "      runner account can create." >&2
        fi
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
    else
        # The register script is handed to sudo below, so it runs as root. Its -x
        # bit says nothing about who can rewrite it: if any component on the way to
        # it — anywhere in the checkout — is a symlink, writable by others, or
        # owned by neither you nor root, another account could swap register-
        # daemon.sh for its own between this preflight and the sudo call and have
        # root run it. Walk the full path the same way the env file is walked.
        local register_bad
        register_bad="$(untrusted_ancestor "${admin_user}" "${REGISTER_SCRIPT}")"
        if [ -n "${register_bad}" ]; then
            echo "  ✗ ${register_bad}, on the path to ${REGISTER_SCRIPT}, is a symlink, is writable by" >&2
            echo "      others, or is owned by neither you (${admin_user}) nor root; another account could" >&2
            echo "      replace the script fleet runs as root. Keep the checkout under a path only you and" >&2
            echo "      root can write (sudo chown and chmod go-w the flagged component)." >&2
            errors=$((errors + 1))
        fi
    fi

    # runner.sh and cpu-watcher.sh are read from the checkout and staged into
    # STAGE_DIR, where the runner account runs them under launchd with runner.env
    # — the staged GITHUB_PAT — beside them; the ci-runner plist template is read
    # here and handed, rendered, to register-daemon.sh, which installs it as
    # root. All three sit in the same position as the register script: a symlink,
    # an other-writable component, or an owner that is neither you nor root
    # anywhere on the path lets another account swap the file between this
    # preflight and the staging step. A swapped runner.sh or cpu-watcher.sh could
    # exfiltrate the staged credential when the runner runs it; a swapped
    # template could inject launchd keys that register-daemon.sh installs as
    # root. Walk each the same way the register script is walked.
    local staged_source staged_bad
    for staged_source in "${SCRIPT_DIR}/runner.sh" "${SCRIPT_DIR}/cpu-watcher.sh" "${TEMPLATE}"; do
        staged_bad="$(untrusted_ancestor "${admin_user}" "${staged_source}")"
        if [ -n "${staged_bad}" ]; then
            echo "  ✗ ${staged_bad}, on the path to ${staged_source}, is a symlink, is writable by" >&2
            echo "      others, or is owned by neither you (${admin_user}) nor root; another account could" >&2
            echo "      replace a file fleet stages and runs as the runner, or installs as root." >&2
            errors=$((errors + 1))
        fi
    done

    if [ "${MONITOR}" = true ]; then
        # STORE_FROM is forwarded to install.sh, which hands
        # "${STORE_FROM}:fleet/store-url" to scp as you. scp reads a leading-dash
        # operand as an option, so a value such as -oProxyCommand=... would run a
        # command as you during the credential copy. Hold it to a strict
        # USER@HOST — the only shape a tailnet copy needs — with no leading dash
        # and none of the characters a host or user name never contains, before
        # it is forwarded.
        if [ -n "${STORE_FROM}" ] \
                && ! [[ "${STORE_FROM}" =~ ^[A-Za-z0-9._][A-Za-z0-9._-]*@[A-Za-z0-9._-]+$ ]]; then
            echo "  ✗ --store-from must be USER@HOST (got '${STORE_FROM}'); a leading dash or any" >&2
            echo "      character outside a user or host name is refused, so it cannot reach scp as" >&2
            echo "      an option. Pass something like worker@mac-studio." >&2
            errors=$((errors + 1))
        fi
        # install.sh, and the render.sh it calls, both run with your privileges
        # (no sudo), straight from the checkout — exactly the position the
        # register script is in before it runs as root. render.sh also reads the
        # two plist templates beside it and renders them, as you, into
        # admin-owned plists register-daemon.sh will accept, so a tampered
        # template is as dangerous as a tampered script. If any component on the
        # way to any of these — anywhere in the checkout — is a symlink, writable
        # by others, or owned by neither you nor root, another account could swap
        # a file fleet runs or renders as you between this preflight and the
        # monitor step. Walk the full path of each, the same way the register
        # script is walked.
        local monitor_code monitor_bad
        for monitor_code in "${MONITOR_INSTALL}" "${MONITOR_RENDER}" \
                "${MONITOR_COLLECTOR_TEMPLATE}" "${MONITOR_POLLER_TEMPLATE}"; do
            monitor_bad="$(untrusted_ancestor "${admin_user}" "${monitor_code}")"
            if [ -n "${monitor_bad}" ]; then
                echo "  ✗ ${monitor_bad}, on the path to ${monitor_code}, is a symlink, is writable by" >&2
                echo "      others, or is owned by neither you (${admin_user}) nor root; another account could" >&2
                echo "      replace a file the monitor install runs or renders as you." >&2
                errors=$((errors + 1))
            fi
        done
        # install.sh does not just run those scripts: it runs the Python under
        # tools/fleet as you — `python3 -m tools.fleet.collector` and
        # `tools.fleet.cli`, with PYTHONPATH=${CHECKOUT} — to take and read back
        # the proving sample. The loop above screens install.sh, render.sh and
        # the plist templates, but not the modules those import and execute:
        # collector.py, cli.py, store.py and the rest. The tree's ancestors are
        # already walked with install.sh above, so a writable directory on the
        # way to it is caught there; its contents are scanned here the same way
        # the runner tree is scanned, with the administrator's trust boundary. A
        # single runner-writable module anywhere in it would otherwise run as
        # you during the sample step.
        local monitor_py_bad
        monitor_py_bad="$(untrusted_tool_tree "${admin_user}" "${CHECKOUT}/tools/fleet")"
        if [ -n "${monitor_py_bad}" ]; then
            echo "  ✗ ${monitor_py_bad}, under ${CHECKOUT}/tools/fleet, is writable by others or is owned by" >&2
            echo "      neither you (${admin_user}) nor root; the monitor install imports and runs the Python" >&2
            echo "      there as you. Keep the checkout under a path only you, root and the administrators can write." >&2
            errors=$((errors + 1))
        fi
        # install.sh keeps the monitor's database credential in FLEET_HOME and
        # runs FLEET_HOME's Python as you, so a FLEET_HOME the runner account (or
        # any other) could change — one under the runner's home, say — would
        # hand CI jobs both the credential and your account.
        local fleet_home="${FLEET_HOME:-${HOME}/fleet}" fleet_bad
        case "${fleet_home}" in
            /*) fleet_bad="$(untrusted_ancestor "${admin_user}" "${fleet_home}")" ;;
            *) fleet_bad="${fleet_home}" ;;
        esac
        if [ -n "${fleet_bad}" ]; then
            echo "  ✗ ${fleet_bad}, on the path to the monitor's FLEET_HOME (${fleet_home}), is not absolute," >&2
            echo "      is a symlink, is writable by others, or is owned by neither you (${admin_user}) nor root;" >&2
            echo "      the monitor's credential kept there would be within another account's reach." >&2
            errors=$((errors + 1))
        else
            # A trusted FLEET_HOME path does not vouch for what already lives
            # inside it. render.sh writes the rendered plists and the services'
            # logs into ${FLEET_HOME}/launchd and ${FLEET_HOME}/logs and creates
            # the venv under ${FLEET_HOME}/venv, keeping an existing one. A
            # launchd, logs, or venv directory left there from an earlier, looser
            # state that is now a symlink, writable by others, or owned by another
            # account would let that account redirect what you write, or (through
            # the venv) run code as you. Walk each directory the same way;
            # untrusted_ancestor stops at the first component that does not exist
            # yet, so a first install passes and render.sh creates them as you.
            local monitor_dir monitor_dir_bad
            for monitor_dir in "${fleet_home}/launchd" "${fleet_home}/logs" "${fleet_home}/venv"; do
                monitor_dir_bad="$(untrusted_ancestor "${admin_user}" "${monitor_dir}")"
                if [ -n "${monitor_dir_bad}" ]; then
                    echo "  ✗ ${monitor_dir_bad}, inside FLEET_HOME on the path to ${monitor_dir}, is a symlink, is" >&2
                    echo "      writable by others, or is owned by neither you (${admin_user}) nor root; another account" >&2
                    echo "      could redirect what the monitor install writes or run code as you." >&2
                    errors=$((errors + 1))
                fi
            done
            # A trusted FLEET_HOME path still has to be one you can write.
            # render.sh and install.sh create FLEET_HOME and its launchd, logs
            # and venv subdirectories with mkdir -p as you; a pre-existing
            # root-owned or read-only (mode 0555) home passes the walks above but
            # fails that mkdir -p — after the runner is already registered,
            # leaving the one-command install half done. Probe write and search
            # access on the home, or the nearest existing ancestor of one that
            # does not exist yet, before anything is registered. The probe runs
            # as you (this install's account), the account that creates them.
            local fleet_write_at
            fleet_write_at="$(nearest_existing_dir "${fleet_home}")"
            if ! { test -w "${fleet_write_at}" && test -x "${fleet_write_at}"; }; then
                echo "  ✗ you (${admin_user}) cannot create the monitor's FLEET_HOME (${fleet_home}): ${fleet_write_at}," >&2
                echo "      the nearest existing directory, is not writable by you, so the monitor install's" >&2
                echo "      mkdir -p there would fail after the runner is already registered." >&2
                echo "      Fix: sudo mkdir -p ${fleet_home} && sudo chown ${admin_user} ${fleet_home}" >&2
                errors=$((errors + 1))
            fi
        fi
        # The interpreter install.sh runs as you is an explicit FLEET_PYTHON, or
        # ${FLEET_HOME}/venv/bin/python3 by default. A venv's python3 is itself a
        # symlink to the base interpreter, so this one is not walked like a plain
        # file: untrusted_program follows the whole link chain and screens every
        # hop, refusing an interpreter that is writable, or that links into a
        # place another account controls. It is screened only once it exists — a
        # first install has yet to create the venv, and render.sh then builds it
        # as you inside the FLEET_HOME screened above. A dangling link counts as
        # existing: its target could be created or repointed later, so it is
        # handed to untrusted_program, which refuses a link it cannot follow.
        # TODO(review): a venv left dangling by a Python upgrade is now refused (render.sh would have rebuilt it); say "remove ${fleet_home}/venv" in the error.
        local fleet_python="${FLEET_PYTHON:-${fleet_home}/venv/bin/python3}" python_bad=""
        case "${fleet_python}" in
            /*)
                if sudo test -e "${fleet_python}" -o -L "${fleet_python}"; then
                    python_bad="$(untrusted_program "${admin_user}" "${fleet_python}" sudo)"
                fi
                ;;
            *) python_bad="${fleet_python}" ;;
        esac
        if [ -n "${python_bad}" ]; then
            echo "  ✗ ${python_bad}, on the path to the monitor interpreter (${fleet_python}), is not absolute, is" >&2
            echo "      writable by others, is owned by neither you (${admin_user}) nor root, or cannot be followed;" >&2
            echo "      another account could replace the interpreter the monitor install runs as you." >&2
            errors=$((errors + 1))
        fi
        # Before any venv is involved, install.sh runs the python3 your PATH
        # resolves to check for the venv module, and on a first install render.sh
        # builds the venv with it — both as you, existing venv or not. That host
        # interpreter is screened like the venv one: followed through every link
        # and refused if another account could change any hop.
        local host_python host_python_bad=""
        host_python="$(command -v python3 || true)"
        case "${host_python}" in
            /*) host_python_bad="$(untrusted_program "${admin_user}" "${host_python}")" ;;
            "") host_python_bad="python3 (not found on your PATH)" ;;
            *) host_python_bad="${host_python}" ;;
        esac
        if [ -n "${host_python_bad}" ]; then
            echo "  ✗ ${host_python_bad}, on the way to the python3 the monitor install runs as you, is not found," >&2
            echo "      is not absolute, is writable by others, is owned by neither you (${admin_user}) nor root," >&2
            echo "      or cannot be followed; another account could replace the interpreter it runs." >&2
            errors=$((errors + 1))
        fi
        # render.sh reuses an existing venv rather than recreating it, and runs
        # that venv's pip as you to bring an old install's dependencies up to
        # date (PyYAML). So an existing ${FLEET_HOME}/venv/bin/pip is a code path
        # the monitor install executes as you, just like the interpreter; screen
        # it the same way — followed with untrusted_program, and only once it
        # exists (a dangling link included, as for the interpreter), so a first
        # install (the venv not yet created) is not refused. A
        # venv created freshly by render.sh lives inside the FLEET_HOME screened
        # above, so only a pre-existing one needs this.
        local fleet_pip="${fleet_home}/venv/bin/pip" pip_bad=""
        case "${fleet_pip}" in
            /*)
                if sudo test -e "${fleet_pip}" -o -L "${fleet_pip}"; then
                    pip_bad="$(untrusted_program "${admin_user}" "${fleet_pip}" sudo)"
                fi
                ;;
            *) pip_bad="${fleet_pip}" ;;
        esac
        if [ -n "${pip_bad}" ]; then
            echo "  ✗ ${pip_bad}, on the path to the monitor venv pip (${fleet_pip}), is not absolute, is" >&2
            echo "      writable by others, is owned by neither you (${admin_user}) nor root, or cannot be followed;" >&2
            echo "      another account could replace the pip the monitor install runs as you." >&2
            errors=$((errors + 1))
        fi
        # An existing credential is used as it stands, and install.sh restricts
        # its mode only after the runner is already online taking jobs, so one
        # the runner account can read — a mode-0644 file, or one the runner owns
        # — must be refused here rather than repaired later.
        local store_url="${fleet_home}/store-url" store_bad
        store_bad=""
        if [ -e "${store_url}" ] || [ -L "${store_url}" ]; then
            store_bad="$(exposed_secret "${admin_user}" "${store_url}")"
        fi
        if [ -n "${store_bad}" ]; then
            echo "  ✗ ${store_url} is a symlink, is owned by neither you (${admin_user}) nor root, or can be" >&2
            echo "      read or written by another account; CI jobs could take the monitor's credential." >&2
            echo "      Fix: chmod 600 ${store_url} (and remove any ACL: chmod -N ${store_url})" >&2
            errors=$((errors + 1))
        elif [ -f "${store_url}" ] && [ -s "${store_url}" ]; then
            echo "  ✓ monitor credential present (${store_url})"
        elif [ -e "${store_url}" ] && [ ! -f "${store_url}" ]; then
            # `test -s` is true for a non-empty directory, so an accidental
            # store-url directory would otherwise read as a present credential;
            # install.sh then skips its missing-file branch, chmods the directory,
            # and the collector fails when it tries to read it as a file.
            echo "  ✗ ${store_url} exists but is not a regular file; the monitor credential must be a file." >&2
            echo "      Remove it, or pass --store-from USER@HOST to copy a real credential in." >&2
            errors=$((errors + 1))
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
        # The runner is registered and online by this point. A failure in the
        # monitor install (an scp from --store-from, a first sample) must not be
        # reported as an install that changed nothing and failed: the runner is
        # up and taking jobs, and booting it out to match the failure would throw
        # away the working half to make the state tidy. Report the partial state
        # and how to finish the monitor instead, then exit non-zero so the
        # failure is not mistaken for success. Re-running install retries only
        # the monitor, since the runner registration is idempotent.
        if ! "${MONITOR_INSTALL}" ${STORE_FROM:+--store-from "${STORE_FROM}"}; then
            echo "" >&2
            echo "ERROR: ${LABEL} is installed and online, but the metrics collector failed to install." >&2
            echo "  The runner is taking jobs; only monitoring is missing. Re-run this install to retry" >&2
            echo "  the monitor (the runner registration is idempotent), or install it directly with" >&2
            echo "  ${MONITOR_INSTALL}." >&2
            exit 1
        fi
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
    # The plist was registered and enabled just above, and it carries KeepAlive,
    # so launchd would otherwise keep retrying this runner in the background
    # after the install reports failure — a half-installed service that could
    # later come online and take jobs without a completed install. Boot it out
    # and disable it so the failure leaves nothing running, matching `stop`.
    echo "Backing the half-installed ${LABEL} out so launchd stops retrying it..." >&2
    sudo launchctl disable "system/${LABEL}" || true
    sudo launchctl bootout "system/${LABEL}" || true
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
        # Not loaded now — booted out by hand, say — is not the same as stopped:
        # an enabled plist in ${DAEMONS_DIR} is loaded again at the next boot.
        sudo launchctl disable "system/${LABEL}"
        echo "${LABEL} is not running. It stays stopped across reboots until: tools/bin/fleet macos start${INSTANCE:+ --instance ${INSTANCE}}"
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
    elif [ -r "${ENV_FILE}" ] && [ -n "$(exposed_secret "$(id -un)" "${ENV_FILE}")" ]; then
        # install refuses an env file other local accounts can read, because it
        # holds GITHUB_PAT; the ancestor walk above deliberately permits a
        # readable file, so status holds it to the same read-exposure standard
        # rather than sourcing a credential file visible to the runner account.
        echo "  GitHub:   not checked; ${ENV_FILE} is readable by another account (it holds GITHUB_PAT), so it is not read"
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
