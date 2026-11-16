---
status: proposed
created: 2026-11-15
closed:
tracker: []
---

# The commit path must not drop a changed file whose path contains a space

**Branch:** `fix/stager-spaced-paths`
**Release:** the release under development (the version in the root `pom.xml`), project "Common"
**Estimated complexity:** Small to medium

---

## Motivation

A coding-agent job on a workstream for a downstream application changed a tracked
IDE run configuration, `.run/<Name With Spaces>.run.xml`, along with other files. The
job committed and pushed the other files, left that one out, and reported `SUCCESS`.
Nothing in the job's output said a file was missing. The follow-up jobs on the same
pull request then went `DEGRADED` while they tried to work out why the change was not
in the branch.

The file had passed every staging guardrail. The commit path decided to include it and
then failed to include it without noticing. This plan has two goals:

1. Fix the cause, so a path containing a space (or any other character git quotes) is
   staged like any other path.
2. Make the commit path fail loudly whenever a file it approved is not in the commit,
   whatever the cause. A wrong path parse is one way to lose a file, and this plan
   should close the whole class of failure, not just that one cause.

## Root cause

An earlier diagnosis said that "porcelain quoting of spaces broke `git add`", then
dismissed it on the grounds that git prints paths with spaces unquoted. The dismissal
is wrong for current git. Reproduced on the macOS worker's git (2.50) in a scratch
repository:

```
$ git status --porcelain -uall | od -c
A     " . r u n / M y   E m b e d d e d   C l a n g . r u n . x m l " \n
? ?   " n e w   f i l e . t x t " \n
? ?   " q \ " u o t e . t x t " \n

$ git status --porcelain -z -uall | od -c
A     . r u n / M y   E m b e d d e d   C l a n g . r u n . x m l \0
? ?   n e w   f i l e . t x t \0
? ?   q " u o t e . t x t \0
```

Porcelain v1 without `-z` wraps a path that contains a space in double quotes and
C-escapes it. (Recent git quotes SP in short-format status. Older git did not, which
explains why the earlier diagnosis did not see it. Which git version introduced this
is not settled here. It does not matter for the fix, because `-z` output is never
quoted on any version. `core.quotePath` controls only the escaping of non-ASCII bytes,
not this quoting.)

From there, four steps in sequence turn the quoting into a silent drop:

1. **Parse.** `GitOperations.parseChangedFiles` (`flowtree/base`, package
   `io.flowtree.git`) takes `line.substring(3).trim()` and keeps the quotes. The path
   it returns is `"\".run/My Embedded Clang.run.xml\""`, literal quote characters
   included. `requireChangedFiles` and `getChangedFiles` both go through it, so
   `GitCommitHandler.findChangedFiles` gets the quoted name.
2. **Guardrails.** `FileStager.evaluateFiles` computes `isDeleted = !f.exists()`. No
   file is named with literal quotes, so the entry is treated as a *deletion*. A
   deletion skips the size and binary guardrails, and nothing else rejects it, so the
   quoted name ends up in `StagingResult.getStagedFiles()`.
3. **Stage.** `GitCommitHandler.stageFiles` calls `GitOperations.stage(path)`. Because
   the path does not exist in the working tree, `stage` runs
   `git rm --cached --quiet --ignore-unmatch -- <quoted path>`. `--ignore-unmatch`
   makes that exit 0 when nothing matches. The result reports success, so the quoted
   name goes into `stagedFiles` and the log says `Staged: ".run/..."`.
4. **Commit.** The other files are really staged, so `stagedFiles` is not empty,
   `commit()` runs, and the commit leaves out the file. `JobWorkOutcome.allChangesDropped()` only
   fires when *nothing* was staged, so the completion event is `SUCCESS`.

The `GitCommitHandler.reset HEAD` before staging is not involved. It runs in both the
working and the failing case.

### Other places that read porcelain v1 the same way

| Site | What happens with a quoted path |
|---|---|
| `GitCommitHandler.handleDependentRepos` | Has its own copy of the parse loop. It calls `gitOps.execute("add", file)`, without `--` and without checking the exit code, and records the file as staged. `git add` with the quoted name fails with "pathspec did not match", but the dependent repo commits anyway, without the file and without an entry in `dependentRepoFailures`. |
| `GitRepositorySetup.getAllDirtyFiles` | Uses `parseChangedFiles` on `status --porcelain`. Dirty-file reporting and exclusion filtering see the quoted name. |
| `GitRepositorySetup` merge-conflict scan (base-branch sync) | Its own `UU `/`AA `/... prefix loop. A conflicted file with a space is reported to the agent with quotes around it. |
| `GitOperations.fingerprintUncommittedState` | Hashes the quoted name as "no regular file" (`-1`), so changing the content of a spaced file does not change the fingerprint. |
| `WorkingTreeRescue`, `JobWorkOutcome`, `GitCommitHandler.previewStaging` | Go through `requireChangedFiles`/`getChangedFiles`, so they get the same quoted names. `StagingSkipRule` and `EnforceChangesRule` act on a preview that says the file will be staged. |
| `GitRepositorySetup.prepareDependentReposBranches` | Only tests `status --porcelain` for emptiness, which is unaffected. |

Paths reported for renames have the same problem on both sides of ` -> ` (and a path
that contains the text ` -> ` is split in the wrong place).

## Scope

In scope:

1. **Parse status with `-z`.** Every caller that turns status output into paths reads
   `git status --porcelain -z`, through one shared parser.
2. **Remove the duplicates.** The dependent-repo loop and the merge-conflict scan use
   that shared parser, not their own copies.
3. **Fail loudly on an approved file that is not committed.** On both the primary and
   the dependent-repo paths.
4. **Tests** that reproduce the original failure end to end with a real git repository.

Out of scope:

- Changing which files the guardrails approve or reject.
- Changing the `DEGRADED`/`SUCCESS` rules for files the guardrails *rejected*
  (`JobWorkOutcome.allChangesDropped`, `StagingSkipRule`). Those are working as designed, since a
  rejected file is reported as skipped. This plan is about *approved* files.

## Approach

### 1. One NUL-delimited status parser in `GitOperations` (`flowtree/base`)

- `queryChangedFiles` runs `status --porcelain -z -uall`.
- `parseChangedFiles(String)` is replaced by a parser for the `-z` form. Each record is
  `XY<space><path>\0`. For a rename or copy (`X` or `Y` is `R` or `C`) the record is
  followed by one more `\0`-terminated field, the *original* path. Under `-z` the new
  path comes first, which is the reverse of the ` -> ` text form. The parser returns
  the new path and consumes the original. Nothing is trimmed: a path may begin or end
  with a space.
- Callers that only have raw output from another invocation
  (`GitRepositorySetup.getAllDirtyFiles` today) are changed to request `-z` too. No
  parser for the v1 text form is kept, since keeping one invites the next caller to
  use it.
- The conflict scan needs the status code as well as the path. The parser returns
  entries that carry both (a small value type nested in `GitOperations`, for example
  `StatusEntry` with `code()` and `path()`, and an `isUnmerged()` predicate covering
  `UU AA DD AU UA DU UD`). `getChangedFiles`/`requireChangedFiles` keep their
  `List<String>` signatures by mapping entries to paths. The status-code knowledge
  sits on the type that owns it, and `GitRepositorySetup` no longer lists the prefixes.
- Rewrite the `parseChangedFiles*` tests in `GitOperationsTest` for the `-z` form and
  add cases for a path with a space, a path with `"`, a path with a leading space, a
  rename whose two sides contain spaces, and a path containing ` -> `.

### 2. Dependent repos use the shared parser and the shared stage

`GitCommitHandler.handleDependentRepos`:

- Replace the inline loop with `GitOperations.requireChangedFiles(depPath)`. A failed
  query then becomes a `dependentRepoFailures` entry rather than "no changes".
- Replace `gitOps.execute("add", file)` with `gitOps.stage(file)`, which uses `--`,
  handles deletions, and returns a `Result`. A failed stage is handled the same way as
  on the primary path (§3).

### 3. An approved file that is not committed fails the commit

Two layers, since the parse fix only removes one cause:

- **`GitOperations.stage` must not report success for a path git does not know.**
  The `rm --cached --ignore-unmatch` branch exists for a real deletion: a path that is
  in `HEAD` (or the index) but gone from the working tree, including the state
  `git rm` leaves (`stageRecordsARemovalAlreadyGoneFromTheIndex` pins this). When the
  path is in none of the working tree, the index, and `HEAD`, nothing is being
  deleted, and `stage` returns a failed `Result` that names the path as unknown to
  git. An existence check against `HEAD` (`cat-file -e HEAD:<path>`, as
  `matchesCommit` already does) and against the index (`ls-files --error-unmatch`) is
  enough. On an unborn `HEAD` the `HEAD` check is false, which is correct.
- **`GitCommitHandler.stageFiles` throws when an approved file fails to stage.**
  Today a failed stage is logged with `warn` and recorded in `skippedFiles`, and the
  commit continues, which is a silent drop presented as a guardrail skip. A file the
  guardrails approved and git could not stage is a harness defect, not a policy
  decision, so `stageFiles` throws a `RuntimeException` that names every such file
  and git's own message. `handle()` already lets `RuntimeException` fail the job.
- **Post-stage verification (normal path).** After staging and before `commit()`,
  read `git diff --cached --name-only -z --no-renames HEAD` and check that every
  path in `stagedFiles` is in it. Any approved path that is missing fails the commit
  with a message listing those paths. This catches every way of losing a file between
  approval and commit, including ones not yet known. It is a read-only check through
  `job.asGitOperations().executeForPaths(...)` (extended to accept `-z` output, or a
  sibling that does). On an unborn `HEAD` it compares against the empty tree.
- **Merge path.** A merge-carried candidate may legitimately match `HEAD`, so the
  "in `diff --cached HEAD`" check is not valid for every merge candidate. Apply it
  only to the merge candidates that `git status` itself reported as changed. Those
  differ from `HEAD` by definition, so once staged they must show in the cached diff.
  Merge-carried candidates keep their current check (`restoreRejected`,
  `matchesCommit`).
- **Dependent repos.** The same two layers apply per dependent repo. A failed stage
  or a verification miss is added to `dependentRepoFailures` with the missing paths,
  and the dependent repo is not committed. The approver should decide whether a
  dependent-repo miss also fails the job (open question 2).

The thrown failure carries the paths, so the completion event's error and the Slack
notification name the file that would have been dropped.

### 4. Remaining consumers

`fingerprintUncommittedState`, `WorkingTreeRescue`, `JobWorkOutcome`,
`previewStaging` and the dirty-file report need no changes beyond §1. Each of them
gets correct paths once `requireChangedFiles`/`getChangedFiles` return them. The tests
in §Success criteria show that the preview and the commit agree on a spaced path.

### Placement notes

- Parsing status output and deciding whether a path is a real deletion are facts about
  git, so both stay on `GitOperations` in `flowtree/base`. No new helper class is
  added.
- The "approved means committed" invariant belongs to the commit, so it stays in
  `GitCommitHandler`. The primary and dependent paths share one private method for
  it, not two copies.

## Success criteria

All new tests extend `TestSuiteBase`, have `@Test(timeout = ...)`, and use real
scratch git repositories (the `initRepo`/`gitRun` pattern of `GitOperationsTest` and
`GitManagedJobGitOperationsTest`).

- **End-to-end reproduction** (`GitManagedJobGitOperationsTest`): a repository with a
  committed `.run/Name With Spaces.run.xml` and a committed `plain.txt`. The job
  modifies both. Before the fix, the resulting commit contains only `plain.txt`
  (write this test first and confirm it fails on the current code). After the fix,
  `git show --name-only HEAD` lists both, and `getStagedFiles()` reports the unquoted
  path.
- The same with an untracked new file whose name contains a space, a deletion of a
  tracked file whose name contains a space, a rename between two spaced names, and a
  file whose name contains `"`.
- **Fail-loud:** a `GitCommitHandler` run where an approved path cannot be staged (a
  stubbed `FileStager.GitOperations`/staging step that reports success but stages
  nothing, or a path unknown to git) throws, the commit is not created, and the
  message names the path.
- **`GitOperations.stage`:** a path unknown to git returns a failed `Result`.
  `stageRecordsARemovalAlreadyGoneFromTheIndex` still passes unchanged.
- **Dependent repos** (`GitCommitHandlerDependentRepoTest`): a spaced file changed in
  a dependent repo is committed there, and a stage failure appears in
  `dependentRepoFailures` with no dependent commit.
- **Conflict scan:** a merge conflict in a spaced file is reported unquoted.
- **Preview agrees with commit:** `previewStaging()` on a tree with a modified spaced
  file reports the unquoted path as staged, and the commit includes it.
- Existing tests stay green: `GitOperationsTest` (with the `parseChangedFiles*` tests
  rewritten for `-z`, keeping every case they already cover), `FileStagerTest`,
  `GitManagedJobGitOperationsTest`, `GitCommitHandlerDependentRepoTest`,
  `WorkingTreeRescueTest`.
- `mvn clean install -DskipTests` passes and `ar-build-validator` (checkstyle,
  code_policy, test_timeouts, duplicate_code) is clean.

## Dependencies

None. Everything this builds on is on master. The work runs on any node, so no Metal or
macOS label is needed. The end-to-end test must not depend on the worker's git
version: it should fail before the fix on any git that quotes SP in short status, and
the `-z` parse is correct on every version.

## Open questions

1. **Merge-path verification scope.** §3 limits the cached-diff check during a merge to
   the paths `git status` reported. The alternative, checking each merge candidate with
   `matchesCommit` against the working tree, is stricter but slower on large merges.
   Recommendation: the status-reported subset.
2. **Does a dependent-repo miss fail the whole job?** Today, dependent-repo problems
   are collected in `dependentRepoFailures` and the job continues. Recommendation: fail
   the job for an *approved-but-not-staged* file in a dependent repo too, since that is
   the failure this plan exists to prevent. A size-skipped file keeps today's
   reporting-only behavior.
3. **Recovering the lost change on the affected branch.** The downstream file that was
   dropped is still in that job's working tree only if the workspace was not reused.
   That recovery is an operator task on the downstream repository and is not part of
   this change.

## Estimated complexity

**Small to medium.** One parser rewrite with a small value type in `GitOperations`, a
stricter `stage`, one verification method and two call-site changes in
`GitCommitHandler`, and one call-site change in `GitRepositorySetup`. Most of the effort
is the git-backed tests.
