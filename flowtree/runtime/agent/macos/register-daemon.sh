#!/usr/bin/env bash
#
# Register (or replace) a LaunchDaemon in the system domain from a plist
# rendered by the account the daemon will run as. This is the one step of a
# native-agent install that needs root, and it is the same step for the agent
# (install.sh renders its plist and prints this command) and for the runner
# that redeploys it (tools/ci/macos/README.md).
#
# Trust boundary. The plist comes from a service account's home, and that
# account executes code it did not write (it runs coding-agent jobs), so
# nothing it can write may be trusted to decide what root does:
#
#   - This script refuses to run unless the file it was invoked from, and
#     every directory on the path to it, is owned by root or by the
#     administrator invoking sudo, is not writable by anyone else — by mode
#     bits or by an ACL entry — and is not a symlink; a trusted file inside a
#     directory the service account can write to can be swapped before sudo
#     opens it. Run it from a checkout you own, under directories only you
#     and root can write — never from a copy under the service account's
#     home, never from a checkout that account owns, and not from /tmp.
#   - It runs with the base-system PATH only; nothing outside /usr/bin, /bin,
#     /usr/sbin and /sbin is needed, so nothing another account could put on
#     the administrator's PATH is consulted.
#   - The administrator names the service on the command line, and the plist
#     must carry exactly that Label. The plist decides nothing about WHICH
#     service is replaced — otherwise it could name any daemon on the host
#     and have the printed command boot that out and overwrite it — and the
#     label must be under com.almostrealism., so no system service can be
#     named at all.
#   - The plist is copied into a directory only root can reach (under
#     /var/root) before anything reads it, so what is validated is what gets
#     installed, and nothing can be swapped in between.
#   - The daemon must run as the account that owns the plist, with that
#     account's primary group: UserName is required and must name the owner,
#     GroupName if present must be the owner's primary group, and the owner
#     must not be root. A plist can therefore only register a service running
#     as whoever wrote it — which is what a user could already do with a
#     LaunchAgent, minus the login-session requirement.
#   - Only the keys a plain service needs are accepted (see ALLOWED_KEYS).
#     launchd opens the StandardOutPath/StandardErrorPath files as the
#     service user, so those are not a root write path and are allowed.
#
# What it does, in order:
#   1. Checks its own provenance (above), lints the plist and checks its
#      Label against the one named on the command line; the file under
#      /Library/LaunchDaemons is named after the label.
#   2. If a service with that label is already registered, boots it out and
#      waits for launchd to stop listing it. bootout returns before the
#      service is gone, and a bootstrap that lands while the old process is
#      still exiting would briefly run two of them — for the agent, two
#      processes with the same node identity on the controller. If the old
#      service is still listed after the wait, this script fails rather than
#      install beside it.
#   3. Installs the plist as root:wheel, mode 644, and bootstraps it.
#   4. Prints the service's state and pid.
#
# Usage:
#   sudo /path/to/your/checkout/flowtree/runtime/agent/macos/register-daemon.sh <label> <rendered plist>
#
#   <label>  the service being registered, e.g. com.almostrealism.flowtree-agent;
#            the plist's Label must be exactly this
#
# Exit codes:
#   0 - the service is registered from the given definition
#   1 - not root, the script or plist failed a trust check, the plist is
#       missing or invalid or carries a different Label, or the previous
#       instance would not stop

set -euo pipefail

# Everything this script runs ships with macOS, so it uses the system PATH
# and nothing else: sudo sanitises PATH anyway (Homebrew's bin is usually
# absent under it), and a root process should not be looking up commands
# in directories another account can populate.
export PATH="/usr/bin:/bin:/usr/sbin:/sbin"
PLISTBUDDY="/usr/libexec/PlistBuddy"

DAEMONS_DIR="/Library/LaunchDaemons"
# Only root can enter this directory, so a file staged under it cannot be
# replaced between validation and install by anyone else.
STAGING_ROOT="/var/root"
STOP_TIMEOUT_SECONDS=30
ALLOWED_KEYS="Label UserName GroupName ProgramArguments EnvironmentVariables WorkingDirectory RunAtLoad KeepAlive ThrottleInterval StandardOutPath StandardErrorPath ProcessType Nice"
LABEL_PREFIX="com.almostrealism."
# An ACL entry granting any of these lets its subject change or replace the
# file whatever the mode bits say.
ACL_WRITE_RIGHTS="write|delete|delete_child|append|add_file|add_subdirectory|writeattr|writeextattr|writesecurity|chown"

if [ "$#" -ne 2 ]; then
    echo "Usage: sudo $0 <label> <rendered plist>" >&2
    exit 1
fi
EXPECTED_LABEL="$1"
SOURCE="$2"

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: run as root: sudo $0 ${EXPECTED_LABEL} ${SOURCE}" >&2
    exit 1
fi
case "${EXPECTED_LABEL}" in
    "${LABEL_PREFIX}"*) ;;
    *)
        echo "ERROR: label '${EXPECTED_LABEL}' is not under ${LABEL_PREFIX}; this script registers only this project's services." >&2
        exit 1
        ;;
esac
case "${EXPECTED_LABEL}" in
    *[!A-Za-z0-9._-]*)
        echo "ERROR: label '${EXPECTED_LABEL}' contains characters other than letters, digits, '.', '_' and '-'." >&2
        exit 1
        ;;
esac
for cmd in plutil launchctl "${PLISTBUDDY}"; do
    if ! command -v "${cmd}" >/dev/null 2>&1; then
        echo "ERROR: ${cmd} is not available." >&2
        exit 1
    fi
done

# ── Provenance of this script ──────────────────────────────────────
#
# A script root runs must not be writable by the account whose plist it is
# validating, or the validation is theirs to remove. That is a property of
# the whole path, not of one inode: a file owned by the administrator inside
# a directory the service account can write to can be renamed away and
# replaced before sudo opens it. So every component from / down to the file
# must be owned by root or by the administrator behind sudo, carry no group
# or world write bit, grant no write right through an ACL (macOS ACLs can
# allow writing with the mode bits clear), and not be a symlink.

ADMIN_UID="${SUDO_UID:-0}"

# Fails unless the path is a trusted component; the reason is printed.
trusted_path() {
    local path="$1" owner mode acl
    if [ -L "${path}" ]; then
        echo "${path} is a symlink" >&2
        return 1
    fi
    owner="$(stat -f '%u' "${path}")"
    mode="$(stat -f '%Lp' "${path}")"
    if [ "${owner}" != "0" ] && [ "${owner}" != "${ADMIN_UID}" ]; then
        echo "${path} is owned by uid ${owner}, not root or the invoking administrator" >&2
        return 1
    fi
    if [ $(( 8#${mode} & 8#022 )) -ne 0 ]; then
        echo "${path} is group- or world-writable (mode ${mode})" >&2
        return 1
    fi
    # `ls -e` lists ACL entries after the mode line as " N: <who> allow|deny
    # <rights,...>". Any allow entry with a write-type right disqualifies the
    # path; deny entries (the usual "everyone deny delete" on a home) do not.
    acl="$(ls -lde "${path}" | awk 'NR > 1 && $3 == "allow" && $4 ~ /(^|,)('"${ACL_WRITE_RIGHTS}"')(,|$)/')"
    if [ -n "${acl}" ]; then
        echo "${path} has an ACL entry granting write access:${acl}" >&2
        return 1
    fi
}

# The path as invoked (logical, so a symlinked component is seen as one and
# refused, rather than resolved to wherever it points at this moment).
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -L)/$(basename "${BASH_SOURCE[0]}")"
PREFIX=""
for component in / ${SELF//\// }; do
    if [ "${component}" != "/" ]; then
        PREFIX="${PREFIX}/${component}"
    fi
    if ! trusted_path "${PREFIX:-/}"; then
        echo "ERROR: refusing to run from ${SELF}." >&2
        echo "  Root must not execute a file the service account can edit or replace, and" >&2
        echo "  every directory on the way to it counts. Run this script from a checkout" >&2
        echo "  you own, under directories only you and root can write." >&2
        exit 1
    fi
done

# ── A private copy of the plist ────────────────────────────────────
#
# Validated and installed from the same bytes: the source stays under the
# owner's control and could change between a check and the install. The
# copy lives in a directory only root can enter — not the inherited TMPDIR,
# which sudo may have taken from the administrator's environment and which
# is not this script's to vouch for — so nobody else can swap it either.

if [ "$(stat -f '%u' "${STAGING_ROOT}")" != "0" ] \
   || [ $(( 8#$(stat -f '%Lp' "${STAGING_ROOT}") & 8#077 )) -ne 0 ]; then
    echo "ERROR: ${STAGING_ROOT} is not a root-only directory; refusing to stage there." >&2
    exit 1
fi

if [ ! -f "${SOURCE}" ] || [ -L "${SOURCE}" ]; then
    echo "ERROR: ${SOURCE} is not a regular file." >&2
    exit 1
fi
OWNER_UID="$(stat -f '%u' "${SOURCE}")"
OWNER_NAME="$(id -un "${OWNER_UID}")"
OWNER_GROUP="$(id -gn "${OWNER_UID}")"
if [ "${OWNER_UID}" = "0" ]; then
    echo "ERROR: ${SOURCE} is owned by root; this script registers services for service accounts." >&2
    exit 1
fi

STAGING_DIR="$(mktemp -d "${STAGING_ROOT}/register-daemon.XXXXXX")"
trap 'rm -rf "${STAGING_DIR}"' EXIT
chmod 700 "${STAGING_DIR}"
STAGED="${STAGING_DIR}/${EXPECTED_LABEL}.plist"
cp "${SOURCE}" "${STAGED}"
chmod 600 "${STAGED}"
plutil -lint -s "${STAGED}"

# ── Content checks ─────────────────────────────────────────────────

# PlistBuddy prints the root dictionary with its own keys indented by exactly
# four spaces and everything nested deeper, so the top-level keys are the
# lines of the form "    Key = ...". Nothing outside the base system is
# needed to read them.
plist_keys() {
    "${PLISTBUDDY}" -c 'Print' "${STAGED}" | awk '/^    [^ ]+ = /{print $1}'
}
plist_string() {
    "${PLISTBUDDY}" -c "Print :$1" "${STAGED}" 2>/dev/null || true
}

for key in $(plist_keys); do
    case " ${ALLOWED_KEYS} " in
        *" ${key} "*) ;;
        *)
            echo "ERROR: ${SOURCE} sets '${key}', which this script does not accept." >&2
            echo "  Accepted keys: ${ALLOWED_KEYS}" >&2
            exit 1
            ;;
    esac
done

# The plist does not get to choose which service is replaced: the label is
# the administrator's, given on the command line, and the plist must agree.
LABEL="$(plist_string Label)"
if [ "${LABEL}" != "${EXPECTED_LABEL}" ]; then
    echo "ERROR: ${SOURCE} carries Label '${LABEL:-<missing>}', but ${EXPECTED_LABEL} was asked for." >&2
    echo "  A plist may only be registered as the service it was rendered for." >&2
    exit 1
fi

USER_NAME="$(plist_string UserName)"
if [ "${USER_NAME}" != "${OWNER_NAME}" ]; then
    echo "ERROR: ${SOURCE} is owned by ${OWNER_NAME} but its UserName is '${USER_NAME:-<missing>}'." >&2
    echo "  A daemon registered from an account's plist runs as that account, nothing else." >&2
    exit 1
fi
GROUP_NAME="$(plist_string GroupName)"
if [ -n "${GROUP_NAME}" ] && [ "${GROUP_NAME}" != "${OWNER_GROUP}" ]; then
    echo "ERROR: ${SOURCE} sets GroupName '${GROUP_NAME}'; ${OWNER_NAME}'s primary group is ${OWNER_GROUP}." >&2
    exit 1
fi

SERVICE="system/${LABEL}"
TARGET="${DAEMONS_DIR}/${LABEL}.plist"

# ── Replace or register ────────────────────────────────────────────

registered() {
    launchctl print "${SERVICE}" >/dev/null 2>&1
}

if registered; then
    echo "Stopping the registered ${SERVICE}..."
    launchctl bootout "${SERVICE}"
    for _ in $(seq 1 "${STOP_TIMEOUT_SECONDS}"); do
        if ! registered; then
            break
        fi
        sleep 1
    done
    if registered; then
        echo "ERROR: ${SERVICE} is still listed ${STOP_TIMEOUT_SECONDS}s after bootout." >&2
        echo "  Not installing beside it. Inspect with: launchctl print ${SERVICE}" >&2
        exit 1
    fi
fi

install -o root -g wheel -m 644 "${STAGED}" "${TARGET}"
echo "Installed ${TARGET} (runs as ${USER_NAME})"
launchctl bootstrap system "${TARGET}"
echo "Registered ${SERVICE}"
launchctl print "${SERVICE}" | awk '/^[[:space:]]*(state|pid) = /{print "  " $0}'
