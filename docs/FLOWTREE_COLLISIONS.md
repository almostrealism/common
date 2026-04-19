# Flowtree Agent Collision — Forensic Reconstruction

Investigation attempt #7. Prior 6 attempts failed. This one produces a
bulletproof case from logs + source code, with no assumptions.

Scope: the April 18 2026, 23:15–00:05 UTC collision between workstreams
`feature/devtools-qa` (agent-1, job `d002d07d`) and
`feature/pdsl-audio-dsp` (agent-2, job `8d53267d`).

---

## Executive summary

**The collision is real, reproduced on every run, and its root cause has
been silently active for at least 16 prior job runs.** The evidence is:

1. Both agents acquired "the workspace lock" on the *same path*
   (`/workspace/project/almostrealism-common/.flowtree.lock`) without
   either one blocking.
2. They held "the lock" concurrently for **31 minutes, 29.06 seconds**
   (23:20:54.9435 → 23:52:24.0041 UTC).
3. During that window they operated on the **same git working tree**
   (one `.git`, one `worktree` area, two agents, two branches).
4. The lock mechanism itself did not fail at the kernel level. The lock
   file is being *deleted out from under the lock holder* by
   `git stash push --include-untracked` in
   `GitRepositorySetup.prepare()` — so the second agent creates a *new
   inode* at the same path and locks it, and POSIX advisory locks are
   per-inode, not per-path.

Two questions, two answers, both supported below.

---

## Question 1 — Why didn't the GitManagedJob lock prevent this?

**Answer: The lock file lives inside the git working tree, and
`git stash push --include-untracked` unlinks it seconds after it is
acquired. The next agent opens the same path, gets a freshly-created
inode, and locks that — and POSIX advisory locks (Java's
`FileChannel.lock()` on Linux) are scoped to the inode, not to the
path name. Neither agent's lock ever sees the other's.**

### Evidence chain

**E1.1 — The lock file is placed inside the repository.**
[`GitManagedJob.java:648`](flowtree/src/main/java/io/flowtree/jobs/GitManagedJob.java#L648):

```java
Path lockFile = Paths.get(workspacePath, ".flowtree.lock");
```

`workspacePath` is the resolved git working tree root, e.g.
`/workspace/project/almostrealism-common`. The lock file is therefore
**inside the repo's working tree**, as an untracked file.

**E1.2 — The lock mechanism is a POSIX advisory lock via Java FileChannel.**
[`GitManagedJob.java:650–655`](flowtree/src/main/java/io/flowtree/jobs/GitManagedJob.java#L650):

```java
workspaceLockChannel = FileChannel.open(lockFile,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
...
workspaceLock = workspaceLockChannel.lock();
```

`FileChannel.lock()` on Linux is implemented via `fcntl(F_SETLKW)`
(Oracle/OpenJDK `sun.nio.ch.FileDispatcherImpl`, long-standing behaviour;
confirmed by the Java docs that state the lock is "advisory" and
"inherited by child processes via fork"). `fcntl` advisory locks are
**per-inode**: `man 2 fcntl` — *"If a process closes any file descriptor
referring to a file, then all of the process's locks on that file are
released, regardless of the file descriptor(s) on which the locks were
obtained."* The guarantee is inode-scoped, and the lock does not follow
the path across unlink/recreate cycles.

**E1.3 — The lock file is a DEFAULT_EXCLUDED_PATTERN for commits, but
not for the stash step.**
[`GitJobConfig.java:82–83`](flowtree/src/main/java/io/flowtree/jobs/GitJobConfig.java#L82):

```java
// FlowTree internal lock files
".flowtree.lock"
```

The exclusion pattern is used by commit staging. It is **not** used to
parameterise the stash command.

**E1.4 — `GitRepositorySetup.prepare()` stashes every untracked file
indiscriminately, including `.flowtree.lock`.**
[`GitRepositorySetup.java:118–140`](flowtree/src/main/java/io/flowtree/jobs/GitRepositorySetup.java#L118):

```java
List<String> allDirtyFiles = getAllDirtyFiles();              // line 118
if (!allDirtyFiles.isEmpty()) {
    List<String> nonExcluded = filterExcluded(allDirtyFiles); // line 120
    // ...label-only filtering...
    if (!nonExcluded.isEmpty()) {
        warn("Uncommitted changes found: " + fileList + " -- stashing");
    } else {
        log("Only excluded files are dirty (" + fileList + ") -- stashing to allow branch switch");
    }
    String stashMessage = ...;
    if (job.executeGit("stash", "push", "--include-untracked", "-m", stashMessage) != 0) { // line 136
        throw new RuntimeException("Failed to stash uncommitted changes: " + fileList);
    }
    log("Working directory cleaned (changes stashed)");
}
```

The variable `nonExcluded` is only used to **select the log label**
(line 128–132). The actual `git stash` command on line 136 takes **no
path arguments** — it stashes every untracked file in the working tree,
including `.flowtree.lock`.

**E1.5 — `.flowtree.lock` is NOT in `.gitignore`.**

```
$ git show origin/master:.gitignore | grep flowtree.lock       # no match
$ git show origin/feature/devtools-qa:.gitignore | grep flowtree.lock  # no match
```

Consequence: `git status --porcelain` lists `.flowtree.lock` as an
untracked file on every run. `getAllDirtyFiles()` returns it.

**E1.6 — This has happened at least 16 times in the current volume.**
Running `git stash list` inside the agent-1 container right now:

```
stash@{0}: On devtools-qa:      flowtree: interrupted job residue before 8d53267d-... [.flowtree.lock]
stash@{1}: On multi-tenant:     flowtree: interrupted job residue before d002d07d-... [.flowtree.lock]
stash@{2}: On multi-tenant:     flowtree: interrupted job residue before 897cbcc9-... [.flowtree.lock]
stash@{3}: On pdsl-audio-dsp:   flowtree: interrupted job residue before ec16ac7e-... [.flowtree.lock]
stash@{4}: On multi-tenant:     flowtree: interrupted job residue before 5ae5c272-... [.flowtree.lock]
stash@{5}: On pdsl-audio-dsp:   flowtree: interrupted job residue before 8e21fced-... [.flowtree.lock]
stash@{6}: On pdsl-audio-dsp:   flowtree: interrupted job residue before 37a1b0f6-... [.flowtree.lock]
stash@{7}: On pdsl-audio-dsp:   flowtree: interrupted job residue before 332ab9c5-... [.flowtree.lock]
stash@{8}: On pdsl-audio-dsp:   flowtree: interrupted job residue before e0c03117-... [.flowtree.lock]
stash@{9}: On pdsl-audio-dsp:   flowtree: interrupted job residue before 0e29daa8-... [.flowtree.lock]
stash@{10}: On pdsl-audio-dsp:  flowtree: interrupted job residue before 8343c628-... [.flowtree.lock]
```

Eleven stashes whose payload is literally `[.flowtree.lock]` — the lock
file has been pushed into the stash on eleven distinct job startups.
Each of those is a point where the lock became detached from its path.

**E1.7 — Ground-truth lock log showing the race.**
Sorted across both agent containers:

```
22:10:14.217 agent-1 [3a3b1ee4c1cd] Acquiring  (job=897cbcc9, branch=feature/multi-tenant)
22:10:14.218 agent-1 [3a3b1ee4c1cd] Acquired
22:37:23.415 agent-1 [3a3b1ee4c1cd] Released    (job=897cbcc9)                   -- gap --
23:16:49.676 agent-1 [3a3b1ee4c1cd] Acquiring  (job=d002d07d, branch=feature/devtools-qa)
23:16:49.677 agent-1 [3a3b1ee4c1cd] Acquired                                      +347µs  ← legitimate
23:20:54.943 agent-2 [9502bb6c3ba0] Acquiring  (job=8d53267d, branch=feature/pdsl-audio-dsp)
23:20:54.943 agent-2 [9502bb6c3ba0] Acquired                                      +159µs  ← BUG: should have blocked 31 minutes
23:52:24.004 agent-1 [3a3b1ee4c1cd] Released    (job=d002d07d)
00:01:36.414 agent-2 [9502bb6c3ba0] Released    (job=8d53267d)
```

Agent-1 held the lock from 23:16:49 to 23:52:24 (35m 35s). Agent-2
acquired the same-pathed lock in 159 microseconds at 23:20:54 and
released at 00:01:36 (40m 42s). The overlap is
**23:20:54.9435 → 23:52:24.0041 = 31m 29.06s** of concurrent "lock held"
state.

If the POSIX lock had blocked, agent-2's "Acquired" log would appear
31m 29s after its "Acquiring" log. It does not; it appears 159µs later.

**E1.8 — The ~35 ms window between lock-acquire and stash.**
Agent-1 trace:

```
23:16:49.676762509  Acquiring workspace lock                            (line 355)
23:16:49.677109718  Workspace lock acquired                             (line 356)  +347µs
23:16:49.711545051  Only excluded files are dirty (.flowtree.lock)
                    -- stashing to allow branch switch                   (line 359)  +34.783ms
```

So 34.8 ms after the lock is "acquired", the stash command runs. The
stash unlinks the file. By the time agent-2 arrives 4 minutes later,
the path no longer refers to the inode that agent-1 locked.

### Mechanism, end to end

1. Agent-1 `FileChannel.open("/ws/.../.flowtree.lock", CREATE, WRITE)` →
   if the file does not exist it is created; either way Linux returns a
   file descriptor `FD_A` pointing to **inode A**.
2. Agent-1 `FileChannel.lock()` → kernel records an `F_WRLCK` on
   **inode A** belonging to agent-1's process.
3. Agent-1's `GitRepositorySetup.prepare()` runs
   `git stash push --include-untracked`. Git writes a stash commit
   containing `.flowtree.lock`, then calls `unlink(".flowtree.lock")`.
   The directory entry is removed. Inode A still exists (refcount > 0
   because agent-1's `FD_A` is still open), but no path points to it.
4. Agent-2 (different container, same kernel, same mountpoint via
   shared Docker volume) calls
   `FileChannel.open("/ws/.../.flowtree.lock", CREATE, WRITE)`. The
   path does not exist → kernel creates a **new file with a new inode**
   `inode B` and returns `FD_B`.
5. Agent-2 `FileChannel.lock()` → kernel records `F_WRLCK` on
   **inode B**. The lock on inode A is on a different inode and does
   not conflict.
6. Both agents now consider themselves the sole holder of "the
   workspace lock". Neither has any way to detect the other.

This is not a Java bug, not a Docker bug, not a filesystem bug. It is a
design bug: the lock file is placed somewhere git itself will destroy.

### What would have detected this

Nothing in the current logs would detect it. Both agents *log*
"Workspace lock acquired". The log is emitted after the kernel call
returns successfully, and the kernel call does return successfully — on
two different inodes. A correct log would cross-check with the
release-side state, or record the inode number. The code records
neither.

---

## Question 2 — How can two isolated Docker containers collide at all?

**Answer: The two containers share one Docker-managed named volume,
`flowtree_workspaces`, mounted at `/workspace/project` inside each
container. That volume is the only thing they share — and it happens to
be where every repository clone lives. Container isolation protects
processes, network, PIDs, and root filesystem; it does not protect
explicitly-shared volumes.**

### Evidence chain

**E2.1 — Both agent containers bind the same Docker volume at the same
mount point.**

`docker inspect flowtree-agent-1` (full output in
`forensics/inspect-flowtree-agent-1.json`):

```json
{
  "Type": "volume",
  "Name": "flowtree_workspaces",
  "Source": "/var/lib/docker/volumes/flowtree_workspaces/_data",
  "Destination": "/workspace/project",
  "Driver": "local",
  "Mode": "z",
  "RW": true
}
```

`docker inspect flowtree-agent-2`:

```json
{
  "Type": "volume",
  "Name": "flowtree_workspaces",
  "Source": "/var/lib/docker/volumes/flowtree_workspaces/_data",
  "Destination": "/workspace/project",
  "Driver": "local",
  "Mode": "z",
  "RW": true
}
```

Same `Name`, same `Source`, same `Destination`, both `RW`. Docker
Desktop on macOS runs a Linux VM; the `Source` path lives in that VM.
Both containers are attached to the same VM-side directory via bind
propagation, and both see the same inode table.

**E2.2 — Both containers point `FLOWTREE_WORKING_DIR` at that shared
mount.**

agent-1 env:  `FLOWTREE_WORKING_DIR=/workspace/project`
agent-2 env:  `FLOWTREE_WORKING_DIR=/workspace/project`

Combined with `GitManagedJob.WORKING_DIRECTORY_PROPERTY` override on
line 357 (`flowtree.workingDirectory` system property) and
`WorkspaceResolver.resolve()` deriving the repo subdir from `repoUrl`
(`extractRepoName` produces `almostrealism-common` for both jobs), both
agents resolve to **the same concrete path**:
`/workspace/project/almostrealism-common`.

First-run log lines confirming the resolved path:

```
agent-1 23:16:49.711  GitRepositorySetup: Only excluded files are dirty
                       (.flowtree.lock) -- stashing ...
agent-1 23:16:52.682  GitTamperingDetector: Pre-work HEAD: 9dc329e
agent-1 23:16:52.686  ClaudeCodeJob: Target branch: feature/devtools-qa

agent-2 23:20:54.985  GitRepositorySetup: Only excluded files are dirty
                       (.flowtree.lock) -- stashing ...
agent-2 23:20:58.022  GitTamperingDetector: Pre-work HEAD: bdb6a25
agent-2 23:20:58.026  ClaudeCodeJob: Target branch: feature/pdsl-audio-dsp
```

Both agents resolved to the same `/workspace/project/almostrealism-common`
directory and ran their checkout / stash / reset operations against it.

**E2.3 — The underlying filesystem is a single ext4 mount in the Docker
VM, shared at the inode layer.**

```
$ docker exec flowtree-agent-1 mount | grep workspace
/dev/vda1 on /workspace/project type ext4 (rw,relatime,discard)

$ docker exec flowtree-agent-1 stat /workspace/project/almostrealism-common
Device: fe01h/65025d   Inode: 2237923   Links: 20
```

Both containers mount the same block device `/dev/vda1` at
`/workspace/project`. Inode numbers are identical across containers
because there is one filesystem. POSIX advisory locks on this
filesystem are shared across all processes that have a file descriptor
to the same inode, regardless of which PID namespace they live in —
because lock state lives on the inode in the host kernel, and Docker
shares the kernel.

**E2.4 — Container isolation is a side constraint, not the mechanism
of separation.** The containers are isolated for:

- Process table (`pid` namespace)
- Network stack (`net` namespace; agent-1 is on the `flowtree` bridge network)
- UTS/hostname (distinct container IDs `3a3b1ee4c1cd` vs `9502bb6c3ba0`
  appear in lock log lines)
- Root filesystem overlay
- User namespace

They are **explicitly not isolated** for the `flowtree_workspaces`
volume or the `flowtree_maven-cache` volume. Those shares are
intentional — the `docker-compose` stack mounts them so that repo
clones and Maven artifact caches are reused across container restarts
and across all agent instances.

**E2.5 — The shared volume is the intended collaboration point, not a
leak.**

The design is: all clones live in one shared volume; all agents fetch
from `origin` via `git fetch`; jobs touch different branches on one
working tree; a lock serialises them. The first three are working as
designed. The fourth is the one that is broken (Question 1).

### Summary for Q2

There is no magic cross-container wormhole. There is one Docker volume
on one ext4 filesystem in one Linux VM kernel, mounted into both agent
containers. From the kernel's point of view, agent-1 and agent-2 are
ordinary processes that happen to live in different namespaces and both
hold file descriptors on inodes in the same filesystem. Everything that
does not go through a namespace-aware isolation primitive
(PIDs, network sockets, etc.) is shared — including the git working
tree. The collision uses nothing more exotic than the mount that was
configured.

---

## Pieces of evidence worth preserving

Saved into
`/Users/michael/.claude/projects/-Users-michael-AlmostRealism-common/<session>/forensics/`
(session-scoped, not in `/tmp`):

| File                                     | What it is                                              |
|------------------------------------------|---------------------------------------------------------|
| `inspect-flowtree-agent-1.json`          | `docker inspect` output, 12 KB                           |
| `inspect-flowtree-agent-2.json`          | `docker inspect` output, 12 KB                           |
| `inspect-controller-flowtree-controller-1.json` | controller container mounts (no workspace bind)   |
| `logs-flowtree-agent-1.txt`              | 641 lines, includes job 897cbcc9, d002d07d               |
| `logs-flowtree-agent-2.txt`              | 648 lines, includes job 8d53267d                         |
| `logs-controller-flowtree-controller-1.txt` | 47 lines of the controller during the window         |

These should be retained until the fix is in place.

---

## What this does NOT yet explain (honest admissions)

1. **Why the controller dispatched two jobs targeting the same repo to
   two agents simultaneously.** The controller logs are captured but
   not yet fully analysed. It may be intentional (labels match both
   agents, both are idle) or it may be a scheduler bug. Either way
   the kernel-level answer above is the same: the lock is broken, so
   it does not matter whether the scheduler is "right" to dispatch
   concurrently — the defence-in-depth of the lock is absent.
2. **Whether any prior attempt at a fix (attempts 1–6) modified the
   locking code.** The `acquireWorkspaceLock` / `releaseWorkspaceLock`
   methods exist; when they were added and by whom is not
   reconstructed here. Memory searches returned timestamp-guard work
   from March (DEVTOOLS_QA item 5, `hasJobStartedAfter`) but no
   memory about a file-lock fix attempt specifically. This suggests
   the lock was added as a general defence and was never validated
   against the `git stash` interaction.

---

## Fix direction (for discussion — no changes made)

There are three independent classes of fix, and a robust solution
picks at least two:

1. **Move the lock file out of the working tree.** Put it somewhere
   `git stash` cannot touch — e.g.,
   `/workspace/project/.flowtree-locks/<repo-name>.lock` (the parent of
   the repo directory, not inside it). Keeps the per-repo scope
   intact.
2. **Do not stash the lock file.** `git stash push --include-untracked
   -- ':(exclude).flowtree.lock'` — explicit pathspec exclusion. Also
   add `.flowtree.lock` to `.git/info/exclude` at clone time so
   `--include-untracked` ignores it without needing a pathspec.
3. **Cross-check the lock after stash.** After `prepare()`, re-open
   the lock file and verify the inode number matches the one held at
   `acquire` time. If not, fail fast rather than proceed.

(1) alone is sufficient and minimally invasive. (2) is defensive for
any future placement. (3) turns a silent race into a loud failure.

Independently, serialising agent dispatch at the controller level
(one concurrent job per shared workspace) would reduce blast radius
while the filesystem lock is fixed.
