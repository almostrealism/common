#!/usr/bin/env bash
set -euo pipefail

# ─── Prepare a host to run the AR OpenCL CI runner fleet ─────────────
#
# Performs the one-time host setup the runner fleet depends on: an unprivileged
# service account, durable render-device access, rootless-container
# prerequisites, and the sample-library destination.
#
# Every step is idempotent — re-running changes nothing that is already correct,
# so this doubles as the repair path when something drifts.
#
# Usage:
#   sudo ./setup-host.sh                    # apply
#   sudo ./setup-host.sh --check            # verify only, change nothing
#   sudo ./setup-host.sh --user ar-ci --admin-user michael
#
# Options:
#   --user USER          Service account to create      (default: ar-ci)
#   --admin-user USER    Account that will rsync the sample library; added to
#                        the service group so it can hand the tree over
#                        (default: $SUDO_USER)
#   --samples-dest PATH  Sample library destination     (default: /srv/ar-ci/music)
#   --no-udev            Skip the udev rule (use when another rule already
#                        grants the render group durably)
#   --check              Report what would change; make no changes
#   -h, --help           Show this help
#
# After this succeeds, the remaining setup is in README.md: build the image,
# fill in .env, and register the runner.

SERVICE_USER="ar-ci"
ADMIN_USER="${SUDO_USER:-}"
SAMPLES_DEST="/srv/ar-ci/music"
RENDER_GROUP="render"
# Sorted deliberately high so it wins over a distribution or ROCm rule that also
# claims kfd/renderD — udev applies rules files in filename order and, for `=`
# assignments, the last one wins. A 70- prefix loses to ROCm's own 70-amdgpu.rules.
UDEV_RULE="/etc/udev/rules.d/99-amdgpu-ci.rules"
SUBID_START=200000
SUBID_COUNT=65536
DO_UDEV=1
CHECK_ONLY=0

CHANGES=0
PROBLEMS=0

usage() { sed -n '4,30p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --user)         SERVICE_USER="$2"; shift 2 ;;
        --admin-user)   ADMIN_USER="$2"; shift 2 ;;
        --samples-dest) SAMPLES_DEST="$2"; shift 2 ;;
        --no-udev)      DO_UDEV=0; shift ;;
        --check)        CHECK_ONLY=1; shift ;;
        -h|--help)      usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if [ "$(id -u)" -ne 0 ]; then
    echo "ERROR: run with sudo — this creates an account and writes a udev rule." >&2
    exit 1
fi

# ---------- Reporting helpers ----------

# Records an action that was applied (or would be, under --check).
act() {
    CHANGES=$((CHANGES + 1))
    if [ "$CHECK_ONLY" = 1 ]; then
        echo "  WOULD CHANGE: $1"
    else
        echo "  changed: $1"
    fi
}

ok()   { echo "  ok: $1"; }

# Reports one problem. Use detail() for continuation lines so a multi-line
# explanation is not counted as several distinct problems.
warn() { PROBLEMS=$((PROBLEMS + 1)); echo "  WARNING: $1"; }
detail() { echo "           $1"; }

# Runs a command unless --check is active.
apply() {
    if [ "$CHECK_ONLY" = 0 ]; then
        "$@"
    fi
}

echo "Host setup for the AR OpenCL CI runner"
echo "  service account: ${SERVICE_USER}"
echo "  admin account:   ${ADMIN_USER:-<none — pass --admin-user>}"
echo "  samples dest:    ${SAMPLES_DEST}"
if [ "$CHECK_ONLY" = 1 ]; then echo "  mode:            CHECK ONLY (no changes)"; fi
echo

# ---------- Render group ----------

echo "Render group"
if getent group "${RENDER_GROUP}" > /dev/null; then
    ok "group '${RENDER_GROUP}' exists (gid $(getent group "${RENDER_GROUP}" | cut -d: -f3))"
else
    warn "group '${RENDER_GROUP}' does not exist."
    detail "The GPU driver package normally creates it; without it the udev rule"
    detail "below cannot resolve a group."
fi
echo

# ---------- udev rule ----------

if [ "${DO_UDEV}" = 1 ]; then
    echo "Render device access (udev)"

    # Any other rules file claiming these devices may sort after ours and win.
    OTHERS=$(grep -rl 'kfd\|renderD' \
        /etc/udev/rules.d/ /usr/lib/udev/rules.d/ /lib/udev/rules.d/ 2>/dev/null \
        | grep -v "^${UDEV_RULE}$" || true)
    if [ -n "${OTHERS}" ]; then
        echo "  note: other rules files also claim kfd/renderD:"
        echo "${OTHERS}" | sed 's/^/    /'
        echo "    ${UDEV_RULE} sorts after these, so its GROUP/MODE win."
    fi

    WANT="KERNEL==\"kfd\", GROUP=\"${RENDER_GROUP}\", MODE=\"0660\"
SUBSYSTEM==\"drm\", KERNEL==\"renderD*\", GROUP=\"${RENDER_GROUP}\", MODE=\"0660\""

    if [ -f "${UDEV_RULE}" ] && [ "$(cat "${UDEV_RULE}")" = "${WANT}" ]; then
        ok "${UDEV_RULE} is up to date"
    else
        apply bash -c "printf '%s\n' \"\$1\" > \"\$2\"" _ "${WANT}" "${UDEV_RULE}"
        apply udevadm control --reload-rules
        apply udevadm trigger
        act "wrote ${UDEV_RULE} and reloaded udev"
    fi
    echo
fi

# ---------- Verify the devices ----------

echo "Render devices"
for dev in /dev/kfd /dev/dri/renderD128; do
    if [ ! -e "${dev}" ]; then
        warn "${dev} does not exist — is the GPU driver loaded?"
        continue
    fi

    DEV_GROUP=$(stat -c '%G' "${dev}")
    DEV_MODE=$(stat -c '%a' "${dev}")
    if [ "${DEV_GROUP}" = "${RENDER_GROUP}" ] && [ "${DEV_MODE}" -ge 660 ] 2>/dev/null; then
        ok "${dev} is ${DEV_GROUP} ${DEV_MODE}"
    else
        warn "${dev} is ${DEV_GROUP} ${DEV_MODE}, expected ${RENDER_GROUP} 660"
    fi

    # With an ACL present, the group bits reported by stat/ls are the ACL MASK,
    # not the group entry — a narrow mask silently revokes group access. Check
    # the mask directly rather than trusting the mode above.
    if command -v getfacl > /dev/null 2>&1; then
        MASK=$(getfacl -p "${dev}" 2>/dev/null | sed -n 's/^mask::\(.*\)$/\1/p')
        if [ -n "${MASK}" ] && [ "${MASK}" != "rw-" ] && [ "${MASK}" != "rwx" ]; then
            warn "${dev} ACL mask is '${MASK}' — this masks off group access"
        fi
    fi
done
echo

# ---------- Service account ----------

echo "Service account"
if id "${SERVICE_USER}" > /dev/null 2>&1; then
    ok "user '${SERVICE_USER}' exists"
else
    apply useradd --create-home --shell /bin/bash \
        --comment "AR CI runner service account" "${SERVICE_USER}"
    apply passwd -l "${SERVICE_USER}" > /dev/null
    act "created '${SERVICE_USER}' with a locked password"
fi

if [ "$CHECK_ONLY" = 1 ] && ! id "${SERVICE_USER}" > /dev/null 2>&1; then
    echo
    echo "Remaining checks need the account to exist; re-run without --check."
    exit 0
fi

SERVICE_UID=$(id -u "${SERVICE_USER}" 2>/dev/null || echo "")

# Runs a command as the service account from a neutral working directory.
#
# The cd is load-bearing. podman resolves its own working directory at startup,
# and this script is normally invoked from an admin user's home — which the
# service account cannot chdir into. Inheriting that directory makes every
# container fail with "cannot chdir ...: Permission denied" and exit 125,
# before any device is touched, which reads exactly like a GPU access problem.
as_service_user() {
    (cd / && runuser -u "${SERVICE_USER}" -- \
        env "XDG_RUNTIME_DIR=/run/user/${SERVICE_UID}" "$@")
}

if id -nG "${SERVICE_USER}" | tr ' ' '\n' | grep -qx "${RENDER_GROUP}"; then
    ok "'${SERVICE_USER}' is in '${RENDER_GROUP}'"
else
    apply usermod -aG "${RENDER_GROUP}" "${SERVICE_USER}"
    act "added '${SERVICE_USER}' to '${RENDER_GROUP}'"
    # A running user manager holds the group set it started with.
    apply loginctl terminate-user "${SERVICE_USER}" 2>/dev/null || true
fi
echo

# ---------- Rootless container prerequisites ----------

echo "Rootless container prerequisites"
# Without subuid/subgid ranges rootless podman cannot start ANY container, and
# the error it produces mentions neither subuid nor this account.
if grep -q "^${SERVICE_USER}:" /etc/subuid && grep -q "^${SERVICE_USER}:" /etc/subgid; then
    ok "subuid/subgid ranges present"
else
    apply usermod \
        --add-subuids "${SUBID_START}-$((SUBID_START + SUBID_COUNT - 1))" \
        --add-subgids "${SUBID_START}-$((SUBID_START + SUBID_COUNT - 1))" \
        "${SERVICE_USER}"
    act "allocated subuid/subgid ranges for '${SERVICE_USER}'"
    if command -v podman > /dev/null 2>&1; then
        apply as_service_user podman system migrate 2>/dev/null || true
    fi
fi

if loginctl show-user "${SERVICE_USER}" --property=Linger 2>/dev/null | grep -q 'Linger=yes'; then
    ok "lingering enabled"
else
    apply loginctl enable-linger "${SERVICE_USER}"
    act "enabled lingering for '${SERVICE_USER}'"
fi

if [ -n "${SERVICE_UID}" ] && [ "$CHECK_ONLY" = 0 ]; then
    # enable-linger creates /run/user/<uid> asynchronously.
    for _ in $(seq 1 20); do
        [ -d "/run/user/${SERVICE_UID}" ] && break
        sleep 0.5
    done
fi

if [ -n "${SERVICE_UID}" ] && [ -S "/run/user/${SERVICE_UID}/podman/podman.sock" ]; then
    ok "podman user socket is active"
elif command -v podman > /dev/null 2>&1; then
    apply as_service_user systemctl --user enable --now podman.socket
    act "enabled the podman user socket"
else
    warn "podman is not installed; skipping the user socket"
fi
echo

# ---------- Sample library destination ----------

echo "Sample library destination"
SAMPLES_PARENT=$(dirname "${SAMPLES_DEST}")

if [ -z "${ADMIN_USER}" ]; then
    warn "no admin account given (--admin-user); skipping ${SAMPLES_DEST} ownership."
    detail "The rsync account must own the tree AND belong to '${SERVICE_USER}'"
    detail "for the sync script's chgrp step to work without sudo."
else
    if [ -d "${SAMPLES_DEST}" ]; then
        ok "${SAMPLES_DEST} exists"
    else
        apply mkdir -p "${SAMPLES_DEST}"
        act "created ${SAMPLES_DEST}"
    fi

    apply chown "${ADMIN_USER}:${SERVICE_USER}" "${SAMPLES_PARENT}" "${SAMPLES_DEST}"
    apply chmod 750 "${SAMPLES_PARENT}"
    ok "${SAMPLES_PARENT} owned by ${ADMIN_USER}:${SERVICE_USER}, mode 750"

    if id -nG "${ADMIN_USER}" | tr ' ' '\n' | grep -qx "${SERVICE_USER}"; then
        ok "'${ADMIN_USER}' is in group '${SERVICE_USER}'"
    else
        apply usermod -aG "${SERVICE_USER}" "${ADMIN_USER}"
        act "added '${ADMIN_USER}' to group '${SERVICE_USER}'"
        echo "  note: '${ADMIN_USER}' must log out and back in for this to apply."
    fi
fi
echo

# ---------- Gate ----------

echo "Gate: render group reaches inside a rootless container"

if [ "$CHECK_ONLY" = 1 ]; then
    echo "  skipped under --check (it would pull an image)"
elif ! command -v podman > /dev/null 2>&1; then
    warn "podman is not installed; cannot run the gate"
else
    # Test READABILITY, which is what actually matters — not the group id as it
    # appears inside the container.
    #
    # Under --group-add keep-groups the host's supplementary groups are retained
    # on the process, but a gid outside the subgid map is unmapped in the user
    # namespace and therefore DISPLAYS as 65534(nogroup). Searching `id` output
    # for the render gid reports failure on a correctly configured host. The
    # kernel evaluates access against the process's real credentials, where the
    # group is intact, so a readability test is both valid and direct.
    #
    # The container always exits 0; success is signalled by the marker. That
    # keeps "podman could not start" (non-zero, e.g. 125) distinguishable from
    # "podman ran and the devices were unreadable".
    #
    # stderr is deliberately not discarded. A pull failure, a missing
    # XDG_RUNTIME_DIR, or a runtime that does not understand keep-groups would
    # otherwise be indistinguishable from a permissions problem.
    GATE_OUT=$(as_service_user podman run --rm \
        --device /dev/kfd --device /dev/dri \
        --group-add keep-groups \
        docker.io/library/debian:12 \
        sh -c 'id; if test -r /dev/kfd && test -r /dev/dri/renderD128; then echo GATE_OK; fi' \
        2>&1) && GATE_RC=0 || GATE_RC=$?

    if [ "${GATE_RC}" -ne 0 ]; then
        warn "the gate container did not run at all (exit ${GATE_RC}):"
        printf '%s\n' "${GATE_OUT}" | sed 's/^/           /'
        detail "This is a container-runtime problem, not a GPU problem. Exit 125"
        detail "means podman failed before the container started — most often an"
        detail "unreadable working directory or an unset XDG_RUNTIME_DIR."
    elif printf '%s' "${GATE_OUT}" | grep -q 'GATE_OK'; then
        ok "'${SERVICE_USER}' can read both render devices inside a container"
    else
        warn "'${SERVICE_USER}' cannot read the render devices inside a container."
        detail "Container reported:"
        printf '%s\n' "${GATE_OUT}" | sed 's/^/             /'
        detail "Check 'sudo -iu ${SERVICE_USER} id' for the '${RENDER_GROUP}' group, and"
        detail "that the runtime is crun (podman info --format"
        detail "'{{.Host.OCIRuntime.Name}}') — keep-groups is a crun feature and is"
        detail "ignored under runc. See README.md."
        detail "Do not proceed: a later failure would be misattributed to the ICD"
        detail "or the ROCm mount."
    fi
fi
echo

# ---------- Summary ----------

echo "───────────────────────────────────────────────"
if [ "$CHECK_ONLY" = 1 ]; then
    echo "Check complete: ${CHANGES} item(s) would change, ${PROBLEMS} problem(s)."
else
    echo "Setup complete: ${CHANGES} change(s) applied, ${PROBLEMS} problem(s)."
fi

if [ "${PROBLEMS}" -gt 0 ]; then
    echo "Resolve the warnings above before registering a runner."
    exit 1
fi

cat <<EOF

Next:
  1. Stage the sample library (from a machine that holds it):
       tools/ci/sync-music-samples.sh --host \$(hostname) --user ${ADMIN_USER:-<admin>} \\
           --group ${SERVICE_USER} --dest ${SAMPLES_DEST}
  2. Configure and start the fleet, as ${SERVICE_USER}:
       export DOCKER_HOST=unix:///run/user/${SERVICE_UID:-<uid>}/podman/podman.sock
       cp .env.example .env && \$EDITOR .env
       docker compose up -d --build
  3. Reboot once and re-run this script with --check to confirm the render
     group access survived. That is the point of the udev rule.
EOF
