# Maven Artifact Staleness — A Verdict That Is Right, Quiet, and Enforced

**Status:** proposal (not started)
**Scope:** `.claude/hooks/mvn-artifact-staleness.py`, the ar-test-runner preflight
banner (`tools/mcp/test-runner/preflight.py`), and the opencode counterpart
(`.claude/hooks/lib/mvn_build_check.py`)

---

## 1. Why this document exists

The staleness hook exists to stop one failure: a test or build that resolves an
`ar-*` jar from `~/.m2` built from different source than the working tree, and so
produces evidence about the wrong code. On 2026-10-06 that failure happened anyway,
and **the hook reported the opposite of the truth just before it did**:

1. A session switched to `feature/cuda-async-runner` at 11:43 and ran a full
   `mvn clean install -DskipTests`.
2. The developer committed on that branch and then merged `origin/master` into it
   (merge `9761d8cd0`). The merge brought in new source in `compute/algebra`
   (`WeightedSumComputation.loopThreshold`) and new tests in `engine/utils` that
   use it.
3. Before the next `engine/utils` test run, the hook reported:
   `~/.m2 staleness check: all 37 module artifacts are newer than the current
   checkout (feature/cuda-async-runner at 2026-10-06 11:43).`
4. The run failed to compile `WeightedSumLoopTests` (`cannot find symbol:
   loopThreshold`) against the stale `ar-algebra` jar. Only a full reinstall fixed
   it.

This time the staleness showed up as a compile error. The dangerous version
compiles cleanly: an upstream method body changes, its signature doesn't, and the
test silently exercises the old behavior. It "does not reproduce", or it "passes
locally". The 2026-07-14 incident cited in the hook's own warning text was of that
kind.

## 2. What is wrong today

### 2.1 The baseline is the wrong event

`last_branch_switch()` takes the most recent `checkout: moving from X to Y` entry in
`git reflog` and calls an artifact stale only if it was installed before that
moment. Source changes without a checkout entry are invisible:

| Event | Changes source | Reflog entry | Detected today |
|---|---|---|---|
| `git checkout` / `git switch` | yes | `checkout:` | yes |
| `git merge` / `git pull` | yes | `merge` / `pull` | **no** |
| `git rebase`, `git reset --hard`, `git stash pop`, `git cherry-pick` | yes | other kinds | **no** |
| Editing files (agent or human), uncommitted | yes | none | **no** |
| `git commit` | no | `commit` | n/a |

The question the hook must answer is whether each jar was built from the source
that is in the working tree now. A reflog event is only a proxy for that, and it
is complete for just one kind of change.

### 2.2 It is noisy when nothing is wrong

- Every artifact-producing Maven call, and every `start_test_run` and
  `start_validation`, prints a status line even when all artifacts are fresh.
- The MCP tools are always treated as partial builds (`partial = True` for
  `MCP_MAVEN_TOOLS`), so the "Reactor caveat" paragraph is appended to every
  test run.
- The ar-test-runner preflight prints its own full age table into every run's
  output.

The result is that the warning reads as boilerplate. A reader learns to skip the
line, and then also skips it the one time it matters. When it is wrong as well, as
in §1, the habit costs nothing until it costs a session.

### 2.3 It never blocks

The hook only injects context (exit 0), even for the case where the outcome is
certainly worthless: a test run whose upstream jars are known to be stale. A
result gathered that way cannot be trusted whichever way it comes out, so allowing
the run has no upside.

### 2.4 It does not say which modules matter

It reports every module in the reactor. For a test run, only the **upstream
closure** of the module under test matters: the module itself is recompiled by
`mvn test -pl`, and downstream modules are irrelevant to it. A stale `ar-ml` jar
says nothing about an `engine/utils` run, yet it is listed beside the ones that do
matter.

### 2.5 Three implementations of one check

| Location | Surface | Logic |
|---|---|---|
| `.claude/hooks/mvn-artifact-staleness.py` | Claude PreToolUse (Bash and MCP) | reflog-checkout verdict; install time from the `_remote.repositories` marker |
| `tools/mcp/test-runner/preflight.py` (`format_artifact_age_report`) | inside every test run's output | age table only, no verdict; install time from jar mtime |
| `.claude/hooks/lib/mvn_build_check.py` | opencode | a short steer, no staleness computation |

They already differ in how they read install time, and only one of them reaches a
verdict.

## 3. Proposed design

### 3.1 Judge staleness by source, per module

For each `ar-*` module with an installed jar:

```
source_time(module)  = newest mtime over
                         module/src/main/**   (Java, resources, native sources)
                         module/pom.xml
                         every ancestor pom.xml up to the reactor root
install_time(module) = mtime of the installed jar's _remote.repositories marker
                       (fall back to the jar's own mtime)

stale(module) = not installed  OR  source_time(module) > install_time(module)
```

Why mtimes are the right signal:

- Every way of changing source in the table in §2.1 rewrites the changed files,
  which sets their mtime to "now". That includes checkout, merge, pull, rebase,
  reset, stash pop, editor saves and agent `Write`/`Edit` calls.
- So one rule covers every row of that table and needs no reflog parsing.
- Files a branch switch leaves untouched keep their old mtime, which is correct,
  because their content did not change.
- No module publishes a test-jar (no `test-jar` or `<classifier>tests` in any
  `pom.xml`), so `src/test/**` cannot make an upstream jar stale and is excluded.
  That keeps editing a test from flagging its own module.

**Cost, measured:** a stat of every file under every `src/main` in the repository
(7,894 files) takes about 42 ms with a warm cache. That is cheap enough to compute
on every call with no caching. If that ever changes, cache per module keyed on the
newest directory mtime.

**Known false positive:** touching a file without changing it (for example
`touch`, or some formatters) marks its module stale. The remedy (rebuild) is
always correct, so the false positive is cheap. Accept it.

**Known false negative (residual risk):** an install made from a *different*
checkout into the same `~/.m2` produces a jar that is newer than this tree's
source but built from other code. Section 3.6 covers the decision about it.

### 3.2 Report only the modules that matter

- **Test runs** (`start_test_run` with `module=M`, or Bash `mvn test -pl M`
  without `-am`): check the transitive upstream closure of `M`, built from the
  `ar-*` dependencies in the poms. The two existing pom parsers already resolve
  the coordinates; add the dependency edges.
- **Partial builds** (`-pl M` without `-am`, any artifact goal): same closure as
  for a test run.
- **`-pl M -am`, or a full-reactor `install`/`package`:** these rebuild the
  closure, so no warning is needed for the run itself.
- **`start_validation` with `skip_build: false`:** it runs `mvn install
  -DskipTests` itself, so no check is needed. With `skip_build: true` it reads the
  compiled tree, so check every module.

### 3.3 Silent when clean

Emit nothing when the relevant closure is fresh: no status line, no reactor caveat,
no age table. The hook speaks only when something is wrong, so the reader never
learns to skip it.

The ar-test-runner preflight banner follows the same rule. It reports stale
upstream modules, or nothing, instead of the full age table on every run.

### 3.4 Block test runs against stale upstream jars

When the relevant closure contains a stale module:

- **`start_test_run`** (the PreToolUse hook) **and Bash `mvn test -pl M`:**
  deny the call. The reason names each stale module, the newest changed source
  file in it with its time, and the one command that fixes it:
  `mvn install -DskipTests -pl M -am` (or the module list when that is narrower).
- **Other partial builds** (`compile`, `install -pl M` without `-am`): warn, since
  a build can be part of the fix. Keep the warning to a few lines: the stale
  modules and the command.

The check fails open. Any exception in the hook allows the call, as today, so a bug
in the hook can never block all work.

### 3.5 One core, three adapters

Move the computation into one shared core, `staleness_verdict(project_root,
target_module | None, scope)`. It returns
`{stale: [(module, source_time, newest_file, install_time)], scope}`. Put it under
`.claude/hooks/lib/`, which is already the home of shared hook cores, following the
shared-core + thin-adapter pattern in `docs/plans/OPENCODE_HOOKS.md`.

- **The Claude hook** becomes a thin adapter: payload in, then deny, warn or
  silence.
- **The ar-test-runner preflight** calls the same core. It can then also refuse a
  stale run itself, which protects every client of the test runner, not just
  Claude sessions. The two pom parsers merge into the core.
- **The opencode adapter** can use the same verdict when its turn comes.

Unify the install-time rule while doing this (marker first, then jar mtime), since
the two current implementations disagree.

### 3.6 Decisions for the owner

1. **Block, or auto-rebuild, in ar-test-runner?** The runner could instead run
   `mvn install -DskipTests -pl M -am` itself before testing when the closure is
   stale. That makes the failure impossible on the main path, at the cost of
   unannounced build time. **Recommendation:** block by default with the exact
   command, and possibly add an opt-in `rebuild_upstream: true` parameter later.
2. **Bash `mvn test`** is already blocked by `block-mvn-test-direct.sh`, so §3.4
   matters mostly for the MCP tool. Confirm that no Bash test path remains that
   needs the deny.
3. **Cross-checkout installs (§3.1).** Accept the residual risk, or have every
   install record the source fingerprint it was built from (for example a small
   file written next to the jar by a hook on `mvn install` from this checkout),
   so that the comparison becomes "built from this tree" rather than "built after
   this tree last changed". **Recommendation:** accept for now. Each agent user has
   its own `~/.m2`, so a second checkout sharing one is uncommon.

## 4. Optional follow-on: diagnosis at the point of failure

Add a PostToolUse step on `get_run_status` and `get_run_failures`. When a run
fails with a compile error (`cannot find symbol`, `does not exist`) or a linkage
error (`NoSuchMethodError`, `NoClassDefFoundError`, `IncompatibleClassChangeError`),
re-run the verdict and say whether a stale upstream module explains the failure.

That is the moment the hint is actionable. It also catches a run that was started
before the source changed. Once §3.4 is in place this should rarely fire, but it is
cheap insurance.

## 5. Tests

Add unit tests for the core, using a temporary project tree and a fake `~/.m2`:

- An artifact installed before an edit to its `src/main` file is stale. One
  installed after the edit is fresh.
- An edit to a module's `pom.xml` makes that module stale. An edit to the
  reactor-root `pom.xml` makes every module stale.
- An edit under `src/test` does not make the module stale.
- A missing artifact is stale.
- The upstream closure is computed through transitive `ar-*` dependencies. A
  stale downstream or unrelated module is not reported for the target.
- A source edit with no reflog checkout (the §1 scenario: merge-style rewrite of
  an upstream file after the install) is detected. This is the regression test
  for this document.

Add adapter tests:

- `start_test_run` is denied when the target's closure is stale, and allowed
  silently when it is fresh.
- The reactor caveat and status line are absent when fresh.
- `-pl M -am` and full-reactor installs are not warned.
- Any exception allows the call.

## 6. Rollout

1. Land the core and its tests. Switch the Claude hook to the core in **warn-only**
   mode, silent when clean, and run it for a few days of normal sessions to see
   whether it ever fires falsely.
2. Turn on the deny for `start_test_run`.
3. Move the ar-test-runner preflight onto the core, replacing the age table.
4. Optionally, add the post-failure diagnosis (§4).

## 7. A note on agent behavior

Independently of the hook, the session in §1 also skipped a rule it already had.
CLAUDE.md requires verifying git state before relying on it, and a `git log`
before the test run would have shown the merge. A correct hook makes that
oversight harmless, which is why the fix belongs in the hook. A hook that is quiet
when nothing is wrong also makes it more likely that the reader of an occasional
warning actually acts on it.
