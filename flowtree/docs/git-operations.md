# GitOperations Class Reference

`GitOperations` encapsulates all git and general subprocess execution for jobs
that need to interact with a git repository. It extracts the process-execution
concerns from `GitManagedJob` into a reusable, standalone class so that any
component -- not only `GitManagedJob` subclasses -- can run git commands, query
branch state, and clone repositories through a uniform interface.

**Package:** `io.flowtree.jobs`

**Source file:** `flowtree/src/main/java/io/flowtree/jobs/GitOperations.java`

**Implements:** `ConsoleFeatures` (provides `log()`, `warn()`, and
`formatMessage()` for structured logging)

---

## Table of Contents

1. [Design Philosophy](#design-philosophy)
2. [Construction and Identity](#construction-and-identity)
3. [Public Method Reference](#public-method-reference)
4. [Working Directory Preparation Sequence](#working-directory-preparation-sequence)
5. [Branch Management](#branch-management)
6. [Base Branch Synchronization and Merge Conflict Detection](#base-branch-synchronization-and-merge-conflict-detection)
7. [Commit Identity Management](#commit-identity-management)
8. [Push with Explicit Refspec](#push-with-explicit-refspec)
9. [SSH Configuration for Non-Interactive Environments](#ssh-configuration-for-non-interactive-environments)
10. [Error Handling and Exit Code Conventions](#error-handling-and-exit-code-conventions)
11. [Internal Process Configuration](#internal-process-configuration)
12. [Relationship to GitManagedJob](#relationship-to-gitmanagedjob)
13. [Thread Safety and Concurrency Considerations](#thread-safety-and-concurrency-considerations)
14. [Troubleshooting](#troubleshooting)

---

## Design Philosophy

`GitOperations` follows several design principles that inform its API and
implementation:

**Process isolation over configuration mutation.** All git identity and SSH
settings are applied per-process through environment variables and command-line
flags. Nothing is ever written to `.git/config`, `~/.gitconfig`, or
`~/.ssh/config`. This means multiple `GitOperations` instances with different
identities can safely operate in the same JVM, and no cleanup is required
after a process completes or fails.

**Stderr merged into stdout.** Every `ProcessBuilder` is configured with
`redirectErrorStream(true)`. This simplifies output handling because callers
only need to read a single stream. It also prevents a common class of
deadlocks where a process blocks writing to stderr while the parent process
only reads from stdout (or vice versa). The tradeoff is that callers cannot
distinguish between stdout and stderr content, but in practice this
distinction is rarely needed for git commands.

**Exit codes over exceptions for command failures.** Methods like `execute()`
return integer exit codes rather than throwing exceptions on failure. This
makes it natural to write conditional logic based on git command results
(e.g., checking if a branch exists) without try-catch boilerplate. Exceptions
are reserved for infrastructure-level failures that prevent the process from
starting at all.

**Stateless command execution with stateful configuration.** The
`GitOperations` instance holds configuration (working directory, identity) but
does not cache any git state (current branch, list of remotes, etc.). Every
method call executes a fresh git command, which guarantees the result reflects
the actual repository state at the moment of the call. This avoids stale-cache
bugs at the cost of slightly more process overhead.

**Two-tier API: git commands and general commands.** The class provides
separate methods for git commands (`execute`, `executeWithOutput`) and general
commands (`executeCommand`, `executeCommandWithOutput`). Git methods
automatically configure SSH and identity settings; general methods only set
the working directory and stderr redirect. This separation ensures that
non-git programs (like `gh` for GitHub CLI operations) are not polluted with
irrelevant git environment variables, while git commands always get the full
configuration they need.

---

## Construction and Identity

### Constructor

```java
public GitOperations(String workingDirectory, String taskId)
```

| Parameter          | Type     | Description |
|--------------------|----------|-------------|
| `workingDirectory` | `String` | The directory in which git commands will be executed. May be `null` to use the JVM's current working directory. |
| `taskId`           | `String` | An identifier used in log message formatting. May be `null`. |

The constructor stores both parameters as instance fields. Neither is validated
at construction time; `null` values are handled gracefully at the point of use.

### Identity Setters and Getters

```java
public void   setGitUserName(String gitUserName)
public void   setGitUserEmail(String gitUserEmail)
public String getGitUserName()
public String getGitUserEmail()
```

These setters configure the git identity that is injected into every git process
environment via the `GIT_AUTHOR_NAME`, `GIT_COMMITTER_NAME`,
`GIT_AUTHOR_EMAIL`, and `GIT_COMMITTER_EMAIL` environment variables. The
identity is **process-scoped** -- it never modifies the repository's local
`.git/config`.

Both name and email default to `null`, which means no identity environment
variables are injected and git falls back to whatever is configured in the
system or global gitconfig.

### Quick Usage Example

```java
GitOperations git = new GitOperations("/path/to/repo", "task-42");
git.setGitUserName("bot");
git.setGitUserEmail("bot@example.com");

int exitCode = git.execute("status", "--porcelain");
String branch = git.getCurrentBranch();
boolean exists = git.branchExists("feature/foo");
```

The above snippet demonstrates the typical lifecycle: construct an instance,
configure identity, then call methods. The instance can be reused for multiple
commands within the same task.

---

## Public Method Reference

### `execute`

```java
public int execute(String... args) throws IOException, InterruptedException
```

Executes a git command and returns the process exit code.

| Parameter | Type       | Description |
|-----------|------------|-------------|
| `args`    | `String[]` | The git sub-command and its arguments (e.g., `"status"`, `"--porcelain"`). |

**Returns:** The process exit code. `0` indicates success; any non-zero value
indicates failure.

**Behavior:** The command is constructed as `git <args>`. Standard error is
merged into standard output via `ProcessBuilder.redirectErrorStream(true)`. If
the process exits with a non-zero code, the combined output is logged as a
warning through `ConsoleFeatures.warn()`.

**Throws:**
- `IOException` if the process cannot be started (e.g., `git` not on PATH).
- `InterruptedException` if the current thread is interrupted while waiting.

**Example:**
```java
int exitCode = git.execute("status", "--porcelain");
if (exitCode == 0) {
    // Working tree is clean or changes listed successfully
}
```

---

### `executeWithOutput`

```java
public String executeWithOutput(String... args) throws IOException, InterruptedException
```

Executes a git command and returns the standard output.

| Parameter | Type       | Description |
|-----------|------------|-------------|
| `args`    | `String[]` | The git sub-command and its arguments. |

**Returns:** The combined standard output and standard error as a single
string. Output is returned **regardless of the exit code** -- the caller must
check the exit code separately if needed.

**Behavior:** Identical to `execute()` in how the `ProcessBuilder` is
configured (working directory, SSH command, git identity), but returns the
output string instead of the exit code. No warning is logged for non-zero exit
codes because the caller is expected to inspect the output directly.

**Example:**
```java
String branch = git.executeWithOutput("rev-parse", "--abbrev-ref", "HEAD").trim();
```

---

### `getCurrentBranch`

```java
public String getCurrentBranch() throws IOException, InterruptedException
```

Returns the name of the currently checked-out branch.

**Returns:** The current branch name (e.g., `"master"`, `"feature/task-42"`).
The return value is trimmed of trailing whitespace.

**Implementation:** Delegates to `executeWithOutput("rev-parse", "--abbrev-ref",
"HEAD")` and trims the result.

**Edge case:** When HEAD is in detached state, `git rev-parse --abbrev-ref
HEAD` returns the literal string `"HEAD"`.

---

### `branchExists`

```java
public boolean branchExists(String branch) throws IOException, InterruptedException
```

Checks whether a branch exists locally or on the remote `origin`.

| Parameter | Type     | Description |
|-----------|----------|-------------|
| `branch`  | `String` | The branch name to look up (e.g., `"feature/foo"`). |

**Returns:** `true` if the branch exists as a local ref at
`refs/heads/<branch>` or as a remote ref at `refs/remotes/origin/<branch>`.

**Implementation:** Performs two sequential `git show-ref --verify --quiet`
checks. The local check runs first; if it succeeds (`exit code == 0`), the
method returns `true` immediately without checking the remote. This is an
optimization: if the branch already exists locally, the remote check is
unnecessary.

**Example:**
```java
if (!git.branchExists("feature/task-42")) {
    git.execute("checkout", "-b", "feature/task-42", "--no-track", "origin/master");
}
```

---

### `cloneRepository`

```java
public void cloneRepository(String repoUrl, String targetPath)
        throws IOException, InterruptedException
```

Clones a git repository into the specified target path.

| Parameter    | Type     | Description |
|--------------|----------|-------------|
| `repoUrl`    | `String` | The remote repository URL (e.g., `"https://github.com/owner/repo.git"`). |
| `targetPath` | `String` | The local directory to clone into. |

**Behavior:**
1. Creates parent directories as needed via `File.mkdirs()`.
2. Runs `git clone <repoUrl> <targetPath>`.
3. Configures the non-interactive SSH command on the process environment but
   does **not** apply git identity variables (they are not relevant for clone
   operations).
4. If the clone exits with a non-zero code, throws a `RuntimeException` with
   the exit code and process output.

**Throws:**
- `IOException` if the process cannot be started.
- `InterruptedException` if the current thread is interrupted.
- `RuntimeException` if the clone exits with a non-zero code.

**Note:** Unlike `execute()` and `executeWithOutput()`, this method does not
set the process's working directory to the instance's `workingDirectory`
because the target directory does not exist yet.

**URL format support:** Both SSH URLs (`git@github.com:owner/repo.git`) and
HTTPS URLs (`https://github.com/owner/repo.git`) are supported. When using
SSH URLs, the `GIT_SSH_COMMAND` environment variable ensures non-interactive
host key handling. When using HTTPS URLs, git may require authentication via
credential helpers or tokens configured in the environment.

---

### `executeCommand`

```java
public int executeCommand(String... command) throws IOException, InterruptedException
```

Executes an arbitrary (non-git) command and returns the exit code.

| Parameter | Type       | Description |
|-----------|------------|-------------|
| `command` | `String[]` | The command and its arguments (e.g., `"gh"`, `"pr"`, `"list"`). |

**Returns:** The process exit code.

**Behavior:** The working directory is set to the directory supplied at
construction time. Standard error is merged into standard output. If the
process exits with a non-zero code, the output is logged as a warning.

This method does **not** configure the SSH command or git identity environment
variables because it is intended for non-git programs.

---

### `executeCommandWithOutput`

```java
public String executeCommandWithOutput(String... command)
        throws IOException, InterruptedException
```

Executes an arbitrary (non-git) command and returns the standard output.

| Parameter | Type       | Description |
|-----------|------------|-------------|
| `command` | `String[]` | The command and its arguments. |

**Returns:** The combined standard output and standard error.

**Behavior:** Same process configuration as `executeCommand()` (working
directory, no SSH command, no git identity), but returns the output string
instead of the exit code.

---

### `formatMessage`

```java
@Override
public String formatMessage(String msg)
```

Formats a log message with the `GitOperations` prefix and optional task ID.

**Returns:**
- `"GitOperations [task-42]: <msg>"` when a task ID is present.
- `"GitOperations: <msg>"` when no task ID is configured.

---

## Working Directory Preparation Sequence

When `GitManagedJob` runs, it goes through a detailed working directory
preparation sequence before the actual work begins. `GitOperations` provides
the low-level git commands that power each step. The full sequence is:

### Step 1: Repository Resolution and Cloning

If a `repoUrl` is configured but no working directory exists, the repository
must first be cloned. This step is handled by `resolveAndCloneRepository()`,
which first resolves a workspace path and then clones into it if no `.git`
directory is present.

The workspace path is resolved in priority order by `resolveWorkspacePath()`:

1. An explicitly configured `defaultWorkspacePath` from YAML configuration.
   This is the preferred option for production deployments where the workspace
   location should be deterministic and persistent across job runs.
2. `/workspace/project` if that directory exists (typical in container
   environments where a volume is mounted at `/workspace`).
3. `/tmp/flowtree-workspaces/<repo-name>` as a fallback, where `<repo-name>`
   is extracted from the URL (e.g., `owner-repo` from
   `https://github.com/owner/repo.git`).

The repo name extraction handles both SSH URLs
(`git@github.com:owner/repo.git` produces `owner-repo`) and HTTPS URLs
(`https://github.com/owner/repo.git` produces `owner-repo`). The `.git`
suffix is stripped, and slashes are replaced with dashes to produce a flat
directory name.

If the resolved directory already contains a `.git` directory, the clone step
is skipped. Otherwise, `cloneRepository()` handles the clone.

### Step 2: Uncommitted Change Detection

Before any branch operations, the working directory is checked for
uncommitted changes using `git status --porcelain`. The `--porcelain` flag
produces machine-readable output where each line starts with a two-character
status code followed by a space and the file path. Lines shorter than 3
characters are ignored (they represent empty output lines).

Files matching excluded patterns (build outputs, agent artifacts, IDE files)
are ignored in this check, so only "real" changes are flagged. The excluded
patterns are the same patterns used for file staging guardrails (see the
file-staging documentation). This prevents false positives from files like
`claude-output/` or `.claude/` that are expected to be present in the working
directory but should never be committed.

If uncommitted changes are found, they are treated as residue from a prior
(likely failed) job run. The working directory is cleaned:

```
git checkout .     # Discard modified tracked files
git clean -fd      # Remove untracked files
```

This is safe in the agent worker context because workers should never have
manual edits. The cleaning uses two separate git commands because `git checkout .`
only discards modifications to tracked files, while `git clean -fd` removes
untracked files and directories. The `-f` flag forces the clean operation (git
refuses to clean without it by default as a safety measure), and the `-d` flag
includes untracked directories (not just files).

If `git checkout .` fails (returns a non-zero exit code), a `RuntimeException`
is thrown immediately. This prevents the job from proceeding with a dirty
working directory, which would lead to confusing merge conflicts or incorrect
diffs later.

### Step 3: Fetch Latest Refs

```
git fetch origin
```

All remote refs are updated so that subsequent branch operations see the
current state of the remote. A non-zero exit code from fetch causes the
preparation to fail with a `RuntimeException`.

### Step 4: Branch Checkout

The target branch is checked out (see [Branch Management](#branch-management)
below for the full creation/checkout logic).

### Step 5: Fast-Forward Sync with Remote

If a remote tracking branch `origin/<targetBranch>` exists:

```
git pull --ff-only origin <targetBranch>
```

If fast-forward fails (e.g., the local branch has diverged due to a
force-push or rebase on the remote), the job falls back to a hard reset:

```
git reset --hard origin/<targetBranch>
```

This ensures tool server files (MCP Python scripts, configuration, etc.) are
always up to date with the remote.

If no remote tracking branch exists (the branch was just created locally),
the pull step is skipped entirely.

After a successful sync, the current HEAD hash is logged at the short (7-char)
level to provide a traceable reference in the job logs:

```
Working directory is up to date with origin/feature/task-42 at a1b2c3d
```

### Step 6: Base Branch Synchronization

The final preparation step merges the latest base branch into the working
branch. See [Base Branch Synchronization](#base-branch-synchronization-and-merge-conflict-detection)
for details.

---

## Branch Management

### Checking the Current Branch

`getCurrentBranch()` uses `git rev-parse --abbrev-ref HEAD` to determine
which branch is currently checked out. This is called at the start of
preparation to record the `originalBranch` (for reference in completion
events) and again before the post-work git operations to verify the working
branch is correct.

### Branch Existence Check

`branchExists(branch)` performs a two-phase check:

1. **Local ref check:** `git show-ref --verify --quiet refs/heads/<branch>`
2. **Remote ref check:** `git show-ref --verify --quiet refs/remotes/origin/<branch>`

The `--quiet` flag suppresses output; only the exit code matters. The
`--verify` flag requires the full refname, preventing partial matches. Without
`--verify`, `git show-ref` would accept partial branch names and could match
unintended refs.

The two-phase approach is important because a branch might exist only on the
remote (it was created by another job and pushed, but the local repo does not
have a local tracking branch yet) or only locally (it was just created but not
yet pushed). By checking both, the method provides a complete picture.

### Branch Creation

When the target branch does not exist and `createBranchIfMissing` is `true`
(the default), a new branch is created from the base branch:

```
git checkout -b <targetBranch> --no-track origin/<baseBranch>
```

The `--no-track` flag is critical. Without it, the new branch would inherit
`origin/<baseBranch>` as its upstream, which means a plain `git push` would
push to the base branch instead of the target branch. The upstream is set
correctly later during the push step (see [Push with Explicit Refspec](#push-with-explicit-refspec)).

### Branch Checkout

When the target branch already exists (locally or remotely), a simple
checkout is performed:

```
git checkout <targetBranch>
```

If the branch exists only on the remote, git's automatic tracking behavior
creates a local branch from the remote ref.

### Handling the `createBranchIfMissing` Flag

When `createBranchIfMissing` is `false` and the target branch does not exist,
`ensureOnTargetBranch()` logs a warning and returns `false`. The caller
(`prepareWorkingDirectory()` or `handleGitOperations()`) then throws a
`RuntimeException`, aborting the job. This mode is useful when a branch is
expected to already exist (e.g., resuming work on an established feature
branch) and creating a new branch would be an error indicating
misconfiguration.

### Dry Run Mode

When `dryRun` is `true`, branch operations are logged but not executed. The
method logs what it *would* do (create or checkout) and returns `true`
without running any git commands. Dry run mode affects branch checkout, file
staging, commit, and push -- essentially all write operations. Read operations
(status, diff, branch existence checks) still execute normally because they
do not modify repository state.

---

## Base Branch Synchronization and Merge Conflict Detection

After the working directory is synced with the remote target branch, the job
attempts to incorporate any changes that have landed on the base branch
(typically `master`) since the target branch was created. This reduces merge
conflicts when the branch is eventually merged back via a pull request.

### When Synchronization is Skipped

The merge is skipped entirely when:

- `baseBranch` is `null` or empty.
- `baseBranch` equals `targetBranch` (merging a branch into itself is a no-op).
- The remote ref `origin/<baseBranch>` does not exist.
- The merge-base between HEAD and `origin/<baseBranch>` equals the head of
  `origin/<baseBranch>` (the working branch is already fully up to date).

### The Merge Operation

When new commits are detected on the base branch:

```
git merge origin/<baseBranch> --no-edit -m "Merge origin/<baseBranch> into <targetBranch>"
```

The `--no-edit` flag prevents an editor from opening (important in headless
environments). A custom merge commit message is provided for clarity in the
git log.

### Merge Conflict Handling

If the merge exits with a non-zero code, the job enters conflict-handling
mode:

1. `mergeConflictsDetected` is set to `true`.
2. `git status --porcelain` is parsed to identify conflicted files. Status
   codes indicating conflicts are: `UU`, `AA`, `DD`, `AU`, `UA`, `DU`, `UD`.
3. The conflicted file list is stored in `conflictFiles`.
4. The merge is **aborted** (`git merge --abort`) to restore the working
   directory to a clean state.
5. Subclasses (e.g., `ClaudeCodeJob`) can inspect `hasMergeConflicts()` and
   `getMergeConflictFiles()` to adjust their behavior -- for instance, by
   adding conflict resolution instructions to a coding agent's prompt.

This design means the agent always starts with a clean working directory but
is aware of pending conflicts that need resolution.

### Merge-Base Calculation

Before attempting the merge, the job calculates the merge-base between HEAD
and `origin/<baseBranch>`:

```
git merge-base HEAD origin/<baseBranch>
```

The merge-base is the common ancestor commit. If it equals the current head
of `origin/<baseBranch>`, that means the working branch already contains all
commits from the base branch, and no merge is needed. This check prevents
unnecessary merge commits that add noise to the git log.

The merge-base and base branch HEAD are logged with their first 7 characters
for traceability:

```
Synchronizing with origin/master (merge-base: a1b2c3d, base HEAD: e4f5g6h)...
```

### Conflict Status Codes

| Code | Meaning |
|------|---------|
| `UU` | Both modified (classic merge conflict) |
| `AA` | Both added (conflicting new files) |
| `DD` | Both deleted |
| `AU` | Added by us, modified by them |
| `UA` | Modified by us, added by them |
| `DU` | Deleted by us, modified by them |
| `UD` | Modified by us, deleted by them |

---

## Commit Identity Management

Git requires a user name and email to create commits. In headless and
containerized environments, a global gitconfig may not exist. The system
provides two complementary mechanisms to ensure commits are always attributed
correctly.

### Environment Variable Injection

`GitOperations` injects identity via process environment variables on every
git command:

| Variable              | Value source      |
|-----------------------|-------------------|
| `GIT_AUTHOR_NAME`    | `gitUserName`     |
| `GIT_COMMITTER_NAME` | `gitUserName`     |
| `GIT_AUTHOR_EMAIL`   | `gitUserEmail`    |
| `GIT_COMMITTER_EMAIL`| `gitUserEmail`    |

These variables are set in the `ProcessBuilder.environment()` map by the
private `applyGitIdentity()` method. They apply only to the child process and
are never persisted to the repository's local config or the user's global
config.

The identity is applied to **all** git commands (not just `commit`), which
ensures that any git operation that might record authorship (e.g., merge
commits during base branch synchronization) uses the correct identity.

### Command-Line Config Flags

`GitManagedJob.commit()` uses an additional mechanism for the commit command
specifically: `-c` flags before the subcommand:

```
git -c user.name=<name> -c user.email=<email> commit -m "<message>"
```

The `-c` flags must appear **before** the `commit` subcommand. They override
any gitconfig value for the duration of that single command. This is the most
reliable way to set identity because it works regardless of container
environment, SSH configuration, or missing gitconfig files.

### Precedence

When both mechanisms are active (which is the normal case), git resolves
identity in this order:

1. `-c` flags on the command line (highest priority)
2. `GIT_AUTHOR_*` / `GIT_COMMITTER_*` environment variables
3. Repository-local `.git/config`
4. User global `~/.gitconfig`
5. System `/etc/gitconfig`

In practice, both mechanisms are set to the same values, so the precedence
does not matter. The dual approach exists for defense in depth: the `-c`
flags handle the commit command where identity is most critical, while the
environment variables cover merge commits and any other operations that
record authorship.

### When Identity is Not Set

If neither `gitUserName` nor `gitUserEmail` is configured, the
`applyGitIdentity()` method simply does not set any environment variables, and
the commit command omits the `-c` flags. Git falls back to whatever identity
is configured through normal gitconfig mechanisms. This is the correct
behavior for local development where a developer's global gitconfig is
already set up.

### Identity Logging

Before executing the commit, `GitManagedJob` logs the identity that will be
used:

```
Committing as: bot <bot@example.com>
```

If either field is not set, `(default)` is shown in its place:

```
Committing as: (default) <(default)>
```

This logging is valuable for auditing which identity was used for a particular
commit, especially in multi-tenant environments where different jobs may use
different identities.

### commit.txt Cleanup

After a successful commit, `GitManagedJob` checks for a `commit.txt` file in
the working directory. This file is used as a temporary mechanism for passing
commit messages (e.g., from a Claude Code agent's output). If the file exists,
it is deleted to prevent it from being reused by a subsequent job run with a
stale message. A warning is logged if the deletion fails, but it does not
cause the job to fail.

---

## Push with Explicit Refspec

After a successful commit, `GitManagedJob` pushes to the remote using an
explicit refspec:

```
git push -u origin <targetBranch>:<targetBranch>
```

### Why an Explicit Refspec is Necessary

When a branch is created with `--no-track` (see [Branch Management](#branch-management)),
it has no upstream tracking configuration. A plain `git push origin` would
either fail (if `push.default` is `simple` and no upstream is set) or push to
an unexpected branch (if `push.default` is `matching`).

The explicit refspec `<targetBranch>:<targetBranch>` makes the push
deterministic: the local branch named `targetBranch` is pushed to the remote
branch of the same name, regardless of any upstream configuration.

### Setting the Upstream

The `-u` flag (`--set-upstream`) configures `origin/<targetBranch>` as the
upstream for the local branch after the push succeeds. This means subsequent
operations (like `git pull --ff-only` in a later job run) work correctly
without needing an explicit remote/branch argument.

### Push Conditions

The push is only attempted when all of these conditions are met:

- `pushToOrigin` is `true` (the default).
- `dryRun` is `false`.
- At least one file was staged and committed.

If the push fails (non-zero exit code), a `RuntimeException` is thrown with a
descriptive message including the target branch name.

### PR Detection After Push

After a successful push, `GitManagedJob` attempts to detect whether an open
pull request exists for the target branch. This uses the GitHub REST API
(either directly with a `GITHUB_TOKEN`/`GH_TOKEN` environment variable, or
through the controller's GitHub proxy endpoint). If a PR is found, its URL is
recorded and included in the job completion event so that status notifications
(e.g., Slack messages) can link directly to the PR.

The PR detection is purely informational -- it does not create PRs, and a
failure to detect a PR does not affect the job's success status.

---

## SSH Configuration for Non-Interactive Environments

Every `ProcessBuilder` created by `GitOperations` for git commands has the
following environment variable set:

```
GIT_SSH_COMMAND=ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes
```

This is defined as a class-level constant:

```java
private static final String SSH_COMMAND =
        "ssh -o StrictHostKeyChecking=accept-new -o BatchMode=yes";
```

### Why This is Necessary

In headless environments (CI/CD servers, Docker containers, agent workers),
there is no TTY available. Without these flags, SSH may:

- **Prompt for host key confirmation** on first connection, causing the
  process to hang indefinitely waiting for input that will never come.
- **Prompt for a passphrase** if the SSH key is encrypted, again causing a
  hang.

### Flag Breakdown

| Flag | Effect |
|------|--------|
| `StrictHostKeyChecking=accept-new` | Automatically accepts host keys for hosts not yet in `known_hosts`, but still rejects changed keys for known hosts. This is safer than `StrictHostKeyChecking=no` because it detects MITM attacks on previously-connected hosts. |
| `BatchMode=yes` | Disables all interactive prompts (password, passphrase, host key confirmation). If authentication cannot proceed non-interactively, the connection fails immediately with a clear error instead of hanging. |

### Scope

The `GIT_SSH_COMMAND` environment variable is set per-process via
`ProcessBuilder.environment()`. It does not modify the user's SSH
configuration files or any global settings.

The SSH command is applied to:
- All `execute()` and `executeWithOutput()` calls (git commands).
- `cloneRepository()` (clone operations).

It is **not** applied to `executeCommand()` and `executeCommandWithOutput()`
because those are for non-git commands that do not use SSH through git's
transport layer.

### Security Considerations

The choice of `StrictHostKeyChecking=accept-new` over
`StrictHostKeyChecking=no` is a deliberate security decision:

- `accept-new` accepts keys for hosts not yet in `known_hosts` but rejects
  changed keys for known hosts. This means the first connection to a host is
  trusted (TOFU -- Trust On First Use), but subsequent connections detect key
  changes that might indicate a man-in-the-middle attack.

- `no` would accept any key, including changed keys for known hosts, which
  provides no protection against MITM attacks.

For container environments where `known_hosts` starts empty on every run,
`accept-new` is effectively equivalent to `no` for the first connection. But
for persistent worker machines that retain their `known_hosts` file, it
provides meaningful protection.

The `BatchMode=yes` flag is the critical setting for preventing hangs. It
disables all interactive prompts -- not just host key confirmation but also
password prompts, passphrase prompts, and any other input requests. If
authentication requires user interaction, the SSH connection fails immediately
with an error message, which is far preferable to a hung process.

---

## Error Handling and Exit Code Conventions

### Exit Code Semantics

All `execute*` methods follow POSIX convention: an exit code of `0` indicates
success; any non-zero value indicates failure. The specific non-zero value
depends on the underlying command but is not interpreted by `GitOperations`
itself.

### Warning on Failure

Both `execute()` and `executeCommand()` log a warning (via
`ConsoleFeatures.warn()`) when the exit code is non-zero. The warning
includes:
- The full command string.
- The exit code.
- The trimmed process output (stdout + stderr combined).

Example warning:
```
GitOperations [task-42]: git push -u origin feature:feature failed (exit 1): error: failed to push some refs
```

### Output Methods Do Not Warn

`executeWithOutput()` and `executeCommandWithOutput()` do **not** log
warnings on non-zero exit codes. The rationale is that when callers
explicitly request the output, they are expected to handle error conditions
themselves based on the output content. Logging a warning would be redundant
and potentially confusing.

### Exception Propagation

`GitOperations` methods throw checked exceptions (`IOException`,
`InterruptedException`) only for infrastructure-level failures -- the
inability to start a process or thread interruption. Command-level failures
(non-zero exit codes) are communicated through return values, not exceptions.

The one exception is `cloneRepository()`, which throws a `RuntimeException`
on non-zero exit codes. This is because a failed clone is an unrecoverable
error: there is no repository to work with, so continuing is impossible.

### Exit Code Patterns in Calling Code

The typical usage pattern in `GitManagedJob` is:

```java
// Pattern 1: Check exit code, throw on failure
if (git.execute("fetch", "origin") != 0) {
    throw new RuntimeException("Failed to fetch from origin");
}

// Pattern 2: Check exit code, use boolean result
boolean exists = git.execute("show-ref", "--verify", "--quiet",
        "refs/heads/" + branch) == 0;

// Pattern 3: Get output, trim, and use
String branch = git.executeWithOutput("rev-parse", "--abbrev-ref", "HEAD").trim();
```

### Output Stream Handling

All process output (stdout and stderr combined) is read through a
`BufferedReader` in the private `readProcessOutput()` method. The method
reads line by line and joins with newline characters.

```java
private String readProcessOutput(Process process) throws IOException {
    StringBuilder output = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }
    }
    return output.toString();
}
```

The output is fully consumed **before** `process.waitFor()` is called. This
prevents deadlocks that can occur when the process's output buffer fills up
and the process blocks waiting for the buffer to be drained. The output
buffer on most operating systems is relatively small (typically 4 KB to 64 KB),
so even moderately verbose git commands can fill it. Reading the output first
is a standard best practice for subprocess management in Java.

### The handleGitOperations Sequence

After `doWork()` completes and `validateChanges()` returns true, the
`handleGitOperations()` method orchestrates the post-work git sequence:

1. **Ensure target branch.** Verifies the working directory is on the correct
   branch (calls `ensureOnTargetBranch()` again as a safety check).
2. **Find changed files.** Runs `git status --porcelain` and parses the
   output to build a list of modified, added, deleted, and untracked files.
   Renamed files are detected by the `->` separator in the status output; the
   new name is used.
3. **Stage files with guardrails.** Each changed file is run through the
   four guardrails (pattern exclusion, test file protection, size limit,
   binary detection). Files that pass are staged via `git add`.
4. **Commit.** The staged files are committed with the message returned by
   `getCommitMessage()`. Identity is set via `-c` flags.
5. **Push.** The commit is pushed to origin with an explicit refspec.
6. **Detect PR.** Checks for an existing open pull request on the target
   branch.
7. **Mark success.** Sets `gitOperationsSuccessful = true`.

If no files are changed (step 2) or no files pass the guardrails (step 3),
the sequence short-circuits with `gitOperationsSuccessful = true` -- an empty
diff is not an error.

---

## Internal Process Configuration

### `configureGitProcess`

```java
private void configureGitProcess(ProcessBuilder pb)
```

Applies the full git-specific configuration to a `ProcessBuilder`:

1. Calls `configureProcess()` for working directory and stderr redirect.
2. Sets `GIT_SSH_COMMAND` in the process environment.
3. Calls `applyGitIdentity()` to inject user name and email.

### `configureProcess`

```java
private void configureProcess(ProcessBuilder pb)
```

Applies the minimal process configuration:

1. Sets the working directory to `workingDirectory` if non-null.
2. Redirects stderr to stdout via `pb.redirectErrorStream(true)`.

This method is used by `executeCommand()` and `executeCommandWithOutput()`
for non-git commands.

### `applyGitIdentity`

```java
private void applyGitIdentity(ProcessBuilder pb)
```

Sets the four git identity environment variables (`GIT_AUTHOR_NAME`,
`GIT_COMMITTER_NAME`, `GIT_AUTHOR_EMAIL`, `GIT_COMMITTER_EMAIL`) on the
process environment, but only when the corresponding field is non-null and
non-empty. Missing fields are simply not set, allowing git's normal fallback
chain to apply.

---

## Relationship to GitManagedJob

`GitOperations` was extracted from `GitManagedJob` to separate concerns.
`GitManagedJob` contains the high-level orchestration logic (the
`prepareWorkingDirectory` / `handleGitOperations` lifecycle), while
`GitOperations` provides the low-level command execution.

`GitManagedJob` still has its own private `executeGit()` and
`executeGitWithOutput()` methods that mirror the behavior of
`GitOperations.execute()` and `GitOperations.executeWithOutput()`. These
exist because `GitManagedJob` predates `GitOperations` and has not yet been
fully refactored to delegate to it. Both implementations configure processes
identically: same working directory, same SSH command, same identity
injection.

`FileStager` defines its own `GitOperations` interface (a functional interface
with a single `execute` method) to decouple file staging from the concrete
`GitOperations` class. This allows `FileStager` to be tested with a mock
implementation.

### Class Hierarchy Summary

```
GitOperations              -- Standalone process executor (reusable)
GitManagedJob              -- Abstract job with git lifecycle
  +-- ClaudeCodeJob        -- Concrete job that runs Claude Code agent
FileStager                 -- Stateless file evaluation utility
  FileStager.GitOperations -- Functional interface for git commands
GitJobConfig               -- Immutable configuration for GitManagedJob
```

`GitOperations` and `GitManagedJob` are complementary. Use `GitOperations`
directly when you need fine-grained control over individual git commands. Use
`GitManagedJob` (via subclassing) when you want the full lifecycle:
preparation, work, staging, commit, push, and status reporting.

---

## Thread Safety and Concurrency Considerations

`GitOperations` is not thread-safe by design. Its mutable fields (`gitUserName`,
`gitUserEmail`) can be set at any time, and concurrent modification would lead
to unpredictable identity injection. In practice, this is not a problem because:

1. Each `GitManagedJob` instance creates or receives its own `GitOperations`
   instance, and jobs run sequentially within a single worker.
2. Identity is typically set once during initialization and never changed.
3. Each git command spawns its own process, so concurrent method calls would
   not interfere at the git level -- but they might produce interleaved log
   output.

If you need to run multiple git operations concurrently (e.g., operating on
different repositories), create separate `GitOperations` instances, each with
its own working directory. Because all configuration is injected per-process,
separate instances cannot interfere with each other.

The underlying `ProcessBuilder` and `Process` objects are created and consumed
within each method call. No process references are stored as instance fields,
so there are no lifecycle concerns around process cleanup.

---

## Troubleshooting

### Common Failure Modes

**`git` not found on PATH.** `execute()` throws an `IOException` with a
message indicating the command could not be started. Verify that git is
installed and on the system PATH. In container environments, ensure the base
image includes git.

**SSH authentication failures.** The process completes with a non-zero exit
code and output containing `Permission denied (publickey)`. Verify that the
SSH key is mounted or available in the environment, and that `BatchMode=yes`
is not preventing a necessary authentication step.

**Identity not set.** A `git commit` fails with `Author identity unknown`.
This means neither the `-c` flags nor the environment variables provided a
valid identity, and no gitconfig exists. Ensure `setGitUserName()` and
`setGitUserEmail()` are called before any commit operations.

**Fast-forward pull fails.** The working branch has diverged from the remote,
typically because a previous job's commit was rebased or force-pushed. The
preparation sequence handles this by falling back to `git reset --hard`, but
if the reset also fails, the job aborts. Check the remote branch state and
ensure no concurrent force-pushes are occurring.

**Clone fails with non-zero exit code.** The `RuntimeException` thrown by
`cloneRepository()` includes the full process output. Common causes include
invalid repository URLs, network connectivity issues, and authentication
failures. The SSH command configuration helps with the authentication case,
but HTTPS repositories may require a `GIT_ASKPASS` helper or pre-configured
credential manager.

### Diagnostic Logging

All `GitOperations` methods log through `ConsoleFeatures`, which prefixes
every message with `"GitOperations [<taskId>]:"`. To correlate logs with a
specific job, search for the task ID in the log output. Successful operations
are logged at the `log()` level; failures are logged at the `warn()` level.

The working directory preparation sequence in `GitManagedJob` logs each step
with descriptive messages, making it straightforward to identify which step
failed:

```
GitManagedJob [task-42]: Fetching latest from origin...
GitManagedJob [task-42]: Already on target branch: feature/task-42
GitManagedJob [task-42]: Syncing with origin/feature/task-42...
GitManagedJob [task-42]: Working directory is up to date with origin/feature/task-42 at a1b2c3d
GitManagedJob [task-42]: Already up to date with origin/master
```

### Merge Conflict with Base Branch

When a base branch merge fails due to conflicts, the log output shows the
conflicted files:

```
GitManagedJob [task-42]: Merge conflict detected while synchronizing with origin/master
GitManagedJob [task-42]: Conflicted files (2): src/main/java/Foo.java, pom.xml
GitManagedJob [task-42]: Merge aborted -- agent will be instructed to resolve conflicts
```

The merge is aborted and the working directory is restored to a clean state.
Subclasses can check `hasMergeConflicts()` and `getMergeConflictFiles()` to
decide how to proceed.

### Git Command Failure

When a git command fails, the warning log includes the full command, exit
code, and output:

```
GitOperations [task-42]: git push -u origin feature/task-42:feature/task-42 failed (exit 1): error: failed to push some refs to 'github.com:owner/repo.git'
```

This level of detail is usually sufficient to diagnose the issue without
needing to reproduce it.
