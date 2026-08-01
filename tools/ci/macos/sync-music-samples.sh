#!/usr/bin/env bash
set -euo pipefail

# ─── Seed /Users/Shared/Music onto a macOS CI runner ─────────────────
#
# Several benchmark tests (AudioSceneSingleVsMultiChannelTest,
# PdslHotPathBreakdownTest, ...) read real sample data from
# /Users/Shared/Music/Samples. A runner that lacks that directory silently
# falls back to synthetic samples and reports misleading timings, so every
# macOS self-hosted runner ([self-hosted, macos, ar-ci]) must have the real
# library present.
#
# This script mirrors the local /Users/Shared/Music to a remote Mac via
# rsync over SSH, then makes the tree group-readable on the remote so the
# runner's user account (any regular macOS user, which is a member of the
# `staff` group) can read it during CI jobs.
#
# rsync is used rather than zip+ssh because the payload is ~1.8 GB across
# thousands of files: rsync is incremental, resumable (--partial), and
# idempotent, so a re-run ships only changed files.
#
# Run it as a user whose SSH key authenticates as REMOTE_USER on the remote
# host. No sudo is required: /Users/Shared is world-writable (mode 1777) on
# macOS, and changing a file's group to a group you belong to, on files you
# own, is permitted.
#
#   ./sync-music-samples.sh                      # mirror to the default host
#   ./sync-music-samples.sh --dry-run            # preview, transfer nothing
#   ./sync-music-samples.sh --host other-mac.local --user michael

# ---------- Configuration (env vars, overridable by the flags below) ----------
REMOTE_HOST="${REMOTE_HOST:-michaels-mac-mini-2}"
REMOTE_USER="${REMOTE_USER:-michael}"
REMOTE_GROUP="${REMOTE_GROUP:-staff}"   # shared group the runner user belongs to (staff = every macOS user)
SRC="${SRC:-/Users/Shared/Music}"
DEST="${DEST:-/Users/Shared/Music}"
SSH_KEY="${SSH_KEY:-}"                   # optional explicit private key; empty = ssh-agent / default identity
DELETE=1                                 # 1 = mirror (prune remote extras); disable with --no-delete
DRY_RUN=0

usage() {
    cat <<EOF
Usage: $(basename "$0") [options]

Mirror ${SRC} to REMOTE_USER@REMOTE_HOST:${DEST} and make it group-readable.

Options:
  --host HOST        Remote host                  (default: ${REMOTE_HOST})
  --user USER        Remote SSH user              (default: ${REMOTE_USER})
  --group GROUP      Remote group to grant read   (default: ${REMOTE_GROUP})
  --src PATH         Local source directory       (default: ${SRC})
  --dest PATH        Remote destination directory (default: ${DEST})
  --key FILE         SSH private key              (default: ssh-agent / default key)
  --no-delete        Do not prune remote files that are absent from the source
  -n, --dry-run      Show what would transfer; change nothing on the remote
  -h, --help         Show this help

Every option also reads from a same-named environment variable
(REMOTE_HOST, REMOTE_USER, REMOTE_GROUP, SRC, DEST, SSH_KEY).
EOF
}

# ---------- Parse flags ----------
while [ $# -gt 0 ]; do
    case "$1" in
        --host)       REMOTE_HOST="$2"; shift 2 ;;
        --user)       REMOTE_USER="$2"; shift 2 ;;
        --group)      REMOTE_GROUP="$2"; shift 2 ;;
        --src)        SRC="$2"; shift 2 ;;
        --dest)       DEST="$2"; shift 2 ;;
        --key)        SSH_KEY="$2"; shift 2 ;;
        --no-delete)  DELETE=0; shift ;;
        -n|--dry-run) DRY_RUN=1; shift ;;
        -h|--help)    usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

SRC="${SRC%/}"     # normalize away any trailing slash; one is appended explicitly below
DEST="${DEST%/}"
REMOTE="${REMOTE_USER}@${REMOTE_HOST}"

# ---------- SSH invocation (array for ssh, matching string for rsync -e) ----------
SSH_OPTS=(-o StrictHostKeyChecking=accept-new -o ConnectTimeout=10)
SSH_E="ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"
if [ -n "$SSH_KEY" ]; then
    SSH_OPTS+=(-i "$SSH_KEY")
    SSH_E="$SSH_E -i $SSH_KEY"
fi

# ---------- Preflight ----------
if [ ! -d "$SRC" ]; then
    echo "ERROR: local source directory not found: $SRC" >&2
    exit 1
fi

if [ "$DRY_RUN" = 1 ]; then MODE_DESC="DRY RUN (no changes)"; else MODE_DESC="LIVE"; fi
if [ "$DELETE" = 1 ]; then DELETE_DESC="mirror (--delete)"; else DELETE_DESC="no-delete"; fi

echo "Source : $SRC"
du -sh "$SRC" 2>/dev/null | awk '{print "Size   : " $1}' || true
echo "Target : ${REMOTE}:${DEST}"
echo "Group  : ${REMOTE_GROUP} (group-readable on remote)"
echo "Mode   : ${MODE_DESC}, ${DELETE_DESC}"
echo

echo "Checking SSH connectivity to ${REMOTE} ..."
if ! ssh "${SSH_OPTS[@]}" -o BatchMode=yes "$REMOTE" true 2>/dev/null; then
    # Retry without BatchMode so a first-time host-key acceptance or a key
    # passphrase prompt can surface interactively.
    if ! ssh "${SSH_OPTS[@]}" "$REMOTE" true; then
        echo "ERROR: cannot SSH to ${REMOTE}." >&2
        echo "  - Is the hostname resolvable? Try '${REMOTE_HOST}.local' or an IP," >&2
        echo "    or add a 'Host' entry to ~/.ssh/config." >&2
        echo "  - Is your key authorized for ${REMOTE_USER} there? Test: ssh ${REMOTE}" >&2
        exit 1
    fi
fi
echo "SSH OK."
echo

# ---------- Transfer ----------
# -a               archive (recurse, preserve times/symlinks/perms)
# --partial        keep partially transferred files so an interrupted run resumes
# --progress       live per-file progress for the large payload
# --exclude        skip macOS Finder cruft
# --chmod          land directories/files group-readable immediately (reinforced below)
RSYNC_OPTS=(-a --human-readable --partial --progress --exclude=.DS_Store --chmod=Dg+rx,Fg+r)
if [ "$DELETE" = 1 ];  then RSYNC_OPTS+=(--delete); fi
if [ "$DRY_RUN" = 1 ]; then RSYNC_OPTS+=(-n -v --stats); fi

if [ "$DRY_RUN" = 0 ]; then
    echo "Ensuring remote destination exists ..."
    ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p \"$DEST\""
fi

echo "Transferring ..."
rsync "${RSYNC_OPTS[@]}" -e "$SSH_E" "$SRC/" "${REMOTE}:${DEST}/"
echo

if [ "$DRY_RUN" = 1 ]; then
    echo "Dry run complete — no files were transferred and no permissions changed."
    exit 0
fi

# ---------- Make it group-readable on the remote (authoritative step) ----------
# DEST and REMOTE_GROUP are passed as positional args to a remote `bash -s`,
# so nothing is interpolated into the remote script body.
echo "Applying group ownership and group-read permissions on the remote ..."
ssh "${SSH_OPTS[@]}" "$REMOTE" bash -s -- "$DEST" "$REMOTE_GROUP" <<'REMOTE_FIX'
set -eu
dest="$1"; group="$2"
# Group-own the tree by the shared group. Permitted without sudo: the caller
# owns the files and is a member of the group.
chgrp -R "$group" "$dest" 2>/dev/null || true
# Add group read everywhere. Capital X grants group-execute only to directories
# (and already-executable files), which is what lets the runner user descend
# into subdirectories.
chmod -R g+rX "$dest"
chmod g+rx "$dest"
# Ensure the parent is traversable by group and other (normally 1777 already).
chmod g+x,o+x /Users/Shared 2>/dev/null || true
echo "  $(ls -ld "$dest")"
echo "  remote file count: $(find "$dest" -type f | wc -l | tr -d ' ')"
REMOTE_FIX

echo
echo "Done. ${REMOTE}:${DEST} is populated and group-readable by '${REMOTE_GROUP}'."
echo "The macOS CI runner (any user in '${REMOTE_GROUP}') can now read the sample library."
