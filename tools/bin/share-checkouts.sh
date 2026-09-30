#!/usr/bin/env bash
set -euo pipefail

# ─── Share git checkouts between a developer and an agent account ────────
#
# A checkout that two accounts edit ends up with files owned by whichever
# account touched them last, and the other account can no longer write them.
# This puts every git repository under a directory into a shared group so
# that both accounts can edit everything, and keeps it that way for files
# either of them creates later.
#
# For each repository found under ROOT (a directory containing .git):
#   - group ownership of the whole tree, including .git, becomes GROUP
#   - the tree is group-readable and group-writable
#   - directories are setgid, so new entries inherit the group
#   - a default ACL grants the group write access to entries created later,
#     whatever the creating account's umask
#   - git's core.sharedRepository is set to "group", so objects and refs it
#     writes are group-writable too
#   - the repository is added to both accounts' safe.directory list, since
#     git refuses to operate on a repository owned by another account
#
# Every directory above each repository that the group cannot already enter
# (such as the agent's 0750 home directory) is given a group ACL allowing it
# to be traversed, but not listed, so that the repository can be reached.
#
# The group is created if it does not exist, and both accounts are added to
# it. Group membership takes effect at the next login of each account.
#
# Run as root:
#
#   share-checkouts.sh --owner michael --agent agent0 /home/agent0/Projects
#
# Options:
#   --owner USER    The developer account (required)
#   --agent USER    The agent account (required)
#   --group NAME    The shared group (default: ar-dev)
#   --depth N       How deep below ROOT to look for repositories (default: 2)
#   --dry-run       Print what would be done without changing anything
#   -h, --help      Show this help
#
# Requires: groupadd, usermod, setfacl (the acl package), sudo, git.

GROUP=ar-dev
OWNER=
AGENT=
DEPTH=2
DRY_RUN=0
ROOT=

usage() {
	sed -n '/^# ─── Share/,/^# Requires/p' "$0" | sed 's/^# \{0,1\}//'
}

run() {
	if [ "$DRY_RUN" -eq 1 ]; then
		echo "+ $*"
	else
		"$@"
	fi
}

while [ $# -gt 0 ]; do
	case "$1" in
		--owner) OWNER="$2"; shift 2 ;;
		--agent) AGENT="$2"; shift 2 ;;
		--group) GROUP="$2"; shift 2 ;;
		--depth) DEPTH="$2"; shift 2 ;;
		--dry-run) DRY_RUN=1; shift ;;
		-h|--help) usage; exit 0 ;;
		-*) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
		*) ROOT="$1"; shift ;;
	esac
done

if [ -z "$OWNER" ] || [ -z "$AGENT" ] || [ -z "$ROOT" ]; then
	echo "The owner account, the agent account and a root directory are all required." >&2
	usage >&2
	exit 2
fi

if [ ! -d "$ROOT" ]; then
	echo "Not a directory: $ROOT" >&2
	exit 2
fi

if [ "$DRY_RUN" -eq 0 ] && [ "$(id -u)" -ne 0 ]; then
	echo "Run this as root; it changes ownership and group membership." >&2
	exit 1
fi

for account in "$OWNER" "$AGENT"; do
	if ! id "$account" >/dev/null 2>&1; then
		echo "No such account: $account" >&2
		exit 2
	fi
done

if ! command -v setfacl >/dev/null 2>&1; then
	echo "setfacl is not installed; install the acl package first." >&2
	exit 1
fi

if getent group "$GROUP" >/dev/null; then
	echo "Group $GROUP exists"
else
	run groupadd "$GROUP"
fi

for account in "$OWNER" "$AGENT"; do
	if id -nG "$account" | tr ' ' '\n' | grep -qx "$GROUP"; then
		echo "$account is already in $GROUP"
	else
		run usermod -aG "$GROUP" "$account"
	fi
done

# Lets the group enter every directory above $1 that other accounts cannot
# enter. Only the execute bit is granted, so the directories can be traversed
# but not listed.
allow_traversal() {
	local dir
	dir="$(dirname "$1")"

	while [ "$dir" != "/" ]; do
		if [ -z "$(find "$dir" -maxdepth 0 -perm -o+x)" ]; then
			echo "Allowing $GROUP to traverse $dir"
			run setfacl -m "g:$GROUP:--x" "$dir"
		fi
		dir="$(dirname "$dir")"
	done
}

# Adds $2 to the global safe.directory list of account $1, unless present.
trust_repository() {
	local account="$1" repo="$2"

	if [ "$DRY_RUN" -eq 0 ] &&
			sudo -u "$account" -H git config --global --get-all safe.directory 2>/dev/null |
			grep -qxF "$repo"; then
		return
	fi

	run sudo -u "$account" -H git config --global --add safe.directory "$repo"
}

share_repository() {
	local repo="$1"
	echo "Sharing $repo"
	allow_traversal "$repo"
	run chgrp -R "$GROUP" "$repo"
	run chmod -R g+rwX "$repo"
	run find "$repo" -type d -exec chmod g+s {} +
	run setfacl -R -m "g:$GROUP:rwX" "$repo"
	run setfacl -R -d -m "g:$GROUP:rwX" "$repo"
	# root does not own the repository, so git would reject it as unsafe
	run git -c safe.directory="$repo" -C "$repo" config core.sharedRepository group
	trust_repository "$OWNER" "$repo"
	trust_repository "$AGENT" "$repo"
}

count=0
while IFS= read -r gitdir; do
	share_repository "$(dirname "$gitdir")"
	count=$((count + 1))
done < <(find "$ROOT" -maxdepth "$DEPTH" -name .git \( -type d -o -type f \) -print | sort)

if [ "$count" -eq 0 ]; then
	echo "No git repositories found under $ROOT within depth $DEPTH" >&2
	exit 1
fi

echo "Shared $count repositories with group $GROUP."
echo "Group membership applies at the next login of $OWNER and $AGENT."
