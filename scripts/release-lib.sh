#!/usr/bin/env bash
#
# Shared release procedure for a Maven repository that is released by tagging
# master and deploying from it. A repository's release.sh describes itself in a
# few variables, sources this file and calls release_main "$@". Written for the
# bash that ships with macOS (3.2): no associative arrays, no mapfile.
#
# What a release does, in order:
#
#   1. Runs the release.sh of the repository this one builds on
#      (RELEASE_UPSTREAM), so the versions it depends on exist before it is
#      tagged. That repository does the same, so a release starts at the bottom
#      of the dependency tree.
#   2. Verifies: master is checked out, the working tree is clean, local master
#      is exactly origin/master, the pom.xml version is a release version with
#      no tag yet, deploy credentials are configured, and every dependency named
#      in RELEASE_DEPENDENCIES is on a version tagged in its repository.
#   3. Decides whether there is anything to release. There is not when the only
#      commit after the last version tag is the "Start of version X" commit that
#      moved pom.xml to the current version, unless that commit pointed a
#      dependency at a version that has since been released. Nothing to release
#      exits 0, so the script is safe to run again.
#   4. Tags HEAD with the pom.xml version (message "Version X"), pushes the tag,
#      then runs mvn deploy -DskipTests.
#   5. Moves every pom to the next version (a minor bump, 0.76 -> 0.77, unless
#      --next-version is given), points each dependency property at the version
#      its repository is now on, and commits and pushes "Start of version Y".
#
# Set by the calling release.sh before sourcing:
#
#   RELEASE_REPO_DIR      absolute path of the repository to release
#   RELEASE_UPSTREAM      absolute path of the repository below this one in the
#                         dependency tree; empty when there is none
#   RELEASE_DEPENDENCIES  space-separated property=directory pairs: a root
#                         pom.xml property holding the version of another
#                         repository's artifacts, and that repository's checkout
#
# Hooks the calling release.sh may define:
#
#   release_verify_extra          additional checks, run during step 2
#   release_bump_extra OLD NEW    additional edits, run during step 5
#
# Environment:
#
#   RELEASE_MAVEN_SETTINGS  settings.xml for Maven (default ~/.m2/settings.xml)
#
# Options:
#
#   --dry-run            verify and report what would happen; change nothing
#   --force              release even when step 3 finds nothing new
#   --next-version X     version to move to after this release (this
#                        repository only; repositories below it still use
#                        their minor bump)

release_usage() {
	sed -n '2,/^$/s/^# \{0,1\}//p' "${BASH_SOURCE[0]}"
}

release_say() {
	printf '%s: %s\n' "$RELEASE_NAME" "$*"
}

release_die() {
	printf '%s: ERROR: %s\n' "$RELEASE_NAME" "$*" >&2
	exit 1
}

release_mvn() {
	if [ -n "${RELEASE_MAVEN_SETTINGS:-}" ]; then
		mvn -s "$RELEASE_MAVEN_SETTINGS" "$@"
	else
		mvn "$@"
	fi
}

# Prints the project version of the pom.xml in the current directory.
release_project_version() {
	release_mvn -q -N help:evaluate -Dexpression=project.version -DforceStdout
}

# Prints the first value of the <$1> element read from standard input.
release_pom_property() {
	awk -v name="$1" 'found == 0 {
		start = index($0, "<" name ">")
		if (start > 0) {
			rest = substr($0, start + length(name) + 2)
			print substr(rest, 1, index(rest, "<") - 1)
			found = 1
		}
	}'
}

release_is_version() {
	printf '%s' "$1" | grep -Eq '^[0-9]+\.[0-9]+(\.[0-9]+)?$'
}

# Prints the minor bump of a version: 0.76 -> 0.77, 1.4.2 -> 1.5.0.
release_next_version() {
	local IFS=.
	set -- $1
	case $# in
		2) printf '%s.%s\n' "$1" "$((10#$2 + 1))" ;;
		3) printf '%s.%s.0\n' "$1" "$((10#$2 + 1))" ;;
		*) return 1 ;;
	esac
}

# Replaces every occurrence of the literal text $2 with $3 in file $1, failing
# when the text is not there.
release_replace_text() {
	grep -qF -- "$2" "$1" || release_die "expected '$2' in $1 while moving to the next version"
	perl -pi -e 'BEGIN { $from = shift; $to = shift } s/\Q$from\E/$to/g' "$2" "$3" "$1"
}

# Sets the <$2> element of file $1 to $3.
release_set_property() {
	grep -q "<$2>" "$1" || release_die "no <$2> property in $1"
	perl -pi -e 'BEGIN { $name = shift; $value = shift } s{<\Q$name\E>[^<]*</\Q$name\E>}{<$name>$value</$name>}g' "$2" "$3" "$1"
}

# Succeeds when $2 is a version tag of the repository at $1, or, in a dry run,
# a version a repository below this one reported it would release.
release_is_released() {
	git -C "$1" rev-parse --quiet --verify "refs/tags/$2" >/dev/null && return 0
	grep -qxF "$1 $2" "$RELEASE_LEDGER"
}

release_verify() {
	command -v mvn >/dev/null || release_die "mvn is not on PATH"
	command -v perl >/dev/null || release_die "perl is not on PATH"
	git rev-parse --git-dir >/dev/null 2>&1 || release_die "$RELEASE_REPO_DIR is not a git repository"

	local branch
	branch=$(git symbolic-ref --quiet --short HEAD) || release_die "HEAD is detached; check out master"
	[ "$branch" = master ] || release_die "on branch '$branch'; releases are made from master"

	[ -z "$(git status --porcelain)" ] \
		|| release_die "the working tree has uncommitted or untracked changes; commit, stash or remove them first"
	[ -n "$(git config user.name)" ] && [ -n "$(git config user.email)" ] \
		|| release_die "git user.name and user.email must be set to tag and commit"

	git remote get-url origin >/dev/null 2>&1 || release_die "there is no remote named origin"
	release_say "fetching origin"
	git fetch --quiet --tags origin \
		|| release_die "could not fetch from origin (when a tag is rejected, a local tag differs from origin's)"

	local head remote_head
	head=$(git rev-parse HEAD)
	remote_head=$(git rev-parse --quiet --verify refs/remotes/origin/master) \
		|| release_die "origin has no master branch"
	if [ "$head" != "$remote_head" ]; then
		if git merge-base --is-ancestor HEAD "$remote_head"; then
			release_die "local master is behind origin/master; pull first"
		elif git merge-base --is-ancestor "$remote_head" HEAD; then
			release_die "local master has commits that are not on origin/master; push or drop them first"
		else
			release_die "local master and origin/master have diverged"
		fi
	fi

	RELEASE_VERSION=$(release_project_version) || release_die "could not read the project version from pom.xml"
	release_is_version "$RELEASE_VERSION" \
		|| release_die "pom.xml version '$RELEASE_VERSION' is not a release version (expected digits and dots, such as 0.76)"

	if git rev-parse --quiet --verify "refs/tags/$RELEASE_VERSION" >/dev/null; then
		local tagged
		tagged=$(git rev-parse "refs/tags/$RELEASE_VERSION^{commit}")
		if [ "$tagged" = "$head" ]; then
			release_die "tag $RELEASE_VERSION already points at HEAD but pom.xml was never moved to the next version, so an earlier release stopped after tagging. Make sure the $RELEASE_VERSION artifacts were deployed (mvn deploy -DskipTests from the tag), then move to the next version with mvn versions:set and commit it as \"Start of version <next>\"."
		fi
		release_die "tag $RELEASE_VERSION already exists (at $(git rev-parse --short "$tagged")) but pom.xml is still on $RELEASE_VERSION; move pom.xml to a new version first"
	fi

	local settings server
	settings=${RELEASE_MAVEN_SETTINGS:-$HOME/.m2/settings.xml}
	server=$(sed -n '/<distributionManagement>/,/<\/distributionManagement>/p' pom.xml | release_pom_property id)
	[ -n "$server" ] || release_die "pom.xml has no <distributionManagement> repository to deploy to"
	[ -f "$settings" ] || release_die "no Maven settings at $settings; mvn deploy needs credentials for server '$server'"
	sed -n '/<servers>/,/<\/servers>/p' "$settings" | grep -q "<id>$server</id>" \
		|| release_die "$settings has no <server> with id '$server'; mvn deploy would fail after the tag is pushed"

	if declare -F release_verify_extra >/dev/null; then
		release_verify_extra
	fi
}

release_main() {
	RELEASE_REPO_DIR=$(cd "$RELEASE_REPO_DIR" && pwd -P)
	RELEASE_NAME=$(basename "$RELEASE_REPO_DIR")

	local dry_run=0 force=0 next_version=""
	while [ $# -gt 0 ]; do
		case "$1" in
			--dry-run) dry_run=1 ;;
			--force) force=1 ;;
			--next-version)
				[ $# -ge 2 ] || release_die "--next-version needs a value"
				next_version=$2
				shift
				;;
			-h|--help) release_usage; exit 0 ;;
			*) release_die "unknown option '$1' (see --help)" ;;
		esac
		shift
	done

	if [ -z "${RELEASE_LEDGER:-}" ]; then
		RELEASE_LEDGER=$(mktemp "${TMPDIR:-/tmp}/release-ledger.XXXXXX")
		export RELEASE_LEDGER
		trap 'rm -f "$RELEASE_LEDGER"' EXIT
	fi

	if [ -n "${RELEASE_UPSTREAM:-}" ]; then
		[ -f "$RELEASE_UPSTREAM/release.sh" ] || release_die "no release.sh in $RELEASE_UPSTREAM"
		release_say "releasing $(basename "$RELEASE_UPSTREAM") first"
		if [ $dry_run -eq 1 ]; then
			bash "$RELEASE_UPSTREAM/release.sh" --dry-run
		else
			bash "$RELEASE_UPSTREAM/release.sh"
		fi || release_die "$(basename "$RELEASE_UPSTREAM") did not release cleanly; stopping"
	fi

	cd "$RELEASE_REPO_DIR"
	release_verify

	local last_tag changes=0 reasons=""
	last_tag=$(git describe --tags --abbrev=0 --match '[0-9]*' HEAD 2>/dev/null || true)
	if [ -z "$last_tag" ]; then
		changes=1
		reasons="no earlier version tag"
	else
		changes=$(git log --format=%s "$last_tag..HEAD" \
			| { grep -vxF "Start of version $RELEASE_VERSION" || true; } | wc -l | tr -d ' ')
		[ "$changes" -eq 0 ] || reasons="$changes new commit(s) since $last_tag"
	fi

	local pair prop dir value previous unreleased=""
	for pair in ${RELEASE_DEPENDENCIES:-}; do
		prop=${pair%%=*}
		dir=$(cd "${pair#*=}" && pwd -P) || release_die "checkout for $prop not found at ${pair#*=}"
		value=$(release_pom_property "$prop" < pom.xml)
		[ -n "$value" ] || release_die "pom.xml has no <$prop> property"
		if release_is_released "$dir" "$value"; then
			previous=""
			[ -z "$last_tag" ] || previous=$(git show "$last_tag:pom.xml" | release_pom_property "$prop")
			if [ "$previous" != "$value" ]; then
				changes=$((changes + 1))
				reasons="${reasons:+$reasons; }$prop moved to released $value"
			fi
		else
			unreleased="${unreleased:+$unreleased, }$prop=$value"
		fi
	done

	if [ "$changes" -eq 0 ] && [ $force -eq 0 ]; then
		release_say "nothing to release: no changes since $last_tag (pom.xml is on $RELEASE_VERSION)"
		return 0
	fi
	[ $force -eq 0 ] || reasons="${reasons:+$reasons; }--force"

	[ -z "$unreleased" ] \
		|| release_die "cannot release $RELEASE_VERSION: pom.xml uses versions that are not tagged in their repositories ($unreleased). Release those first (with --force if they have nothing new of their own) or point the properties at released versions."

	if [ -z "$next_version" ]; then
		next_version=$(release_next_version "$RELEASE_VERSION") \
			|| release_die "cannot work out the version after $RELEASE_VERSION; pass --next-version"
	fi
	release_is_version "$next_version" || release_die "--next-version '$next_version' is not a release version"
	[ "$next_version" != "$RELEASE_VERSION" ] || release_die "the next version must differ from $RELEASE_VERSION"
	! git rev-parse --quiet --verify "refs/tags/$next_version" >/dev/null \
		|| release_die "the next version, $next_version, is already tagged"

	if [ $dry_run -eq 1 ]; then
		release_say "would release $RELEASE_VERSION ($reasons): tag $(git rev-parse --short HEAD) as $RELEASE_VERSION, push the tag, mvn deploy -DskipTests, then commit and push \"Start of version $next_version\""
		printf '%s %s\n' "$RELEASE_REPO_DIR" "$RELEASE_VERSION" >> "$RELEASE_LEDGER"
		return 0
	fi

	release_say "releasing $RELEASE_VERSION ($reasons)"
	git tag -a "$RELEASE_VERSION" -m "Version $RELEASE_VERSION"
	if ! git push --quiet origin "refs/tags/$RELEASE_VERSION"; then
		git tag -d "$RELEASE_VERSION" >/dev/null
		release_die "pushing tag $RELEASE_VERSION failed; the local tag was removed and nothing was deployed"
	fi

	release_say "deploying $RELEASE_VERSION"
	release_mvn deploy -DskipTests \
		|| release_die "mvn deploy failed after tag $RELEASE_VERSION was pushed. Fix the cause, deploy from the tag, then move pom.xml to $next_version and commit it as \"Start of version $next_version\"."

	release_say "moving to $next_version"
	release_mvn -q versions:set -DnewVersion="$next_version" -DgenerateBackupPoms=false -DprocessAllModules=true \
		|| release_die "mvn versions:set failed; $RELEASE_VERSION is released, but the move to $next_version is incomplete"
	for pair in ${RELEASE_DEPENDENCIES:-}; do
		prop=${pair%%=*}
		value=$(cd "${pair#*=}" && release_project_version) || release_die "could not read the version of ${pair#*=}"
		release_set_property pom.xml "$prop" "$value"
	done
	if declare -F release_bump_extra >/dev/null; then
		release_bump_extra "$RELEASE_VERSION" "$next_version"
	fi

	local stale
	stale=$(git grep -lF "<version>$RELEASE_VERSION</version>" -- '*pom.xml' || true)
	[ -z "$stale" ] || release_say "warning: these poms still contain <version>$RELEASE_VERSION</version>; check them: $(echo $stale)"

	git add -u
	git commit --quiet -m "Start of version $next_version"
	git push --quiet origin HEAD:refs/heads/master \
		|| release_die "pushing \"Start of version $next_version\" failed; $RELEASE_VERSION is released, push master by hand"
	release_say "released $RELEASE_VERSION; master is now on $next_version"
}
