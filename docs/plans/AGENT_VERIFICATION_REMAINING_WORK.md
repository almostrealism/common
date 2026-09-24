# Agent job verification — what is not done

Companion to `AGENT_JOB_END_TO_END_VERIFICATION.md`, which describes the
design. This one is the backlog: everything considered and not finished, why,
and what a person resuming needs to know to pick each item up cold.

It is deliberately blunt about the limits of what was built. A verification
system whose own gaps are undocumented invites exactly the mistake it exists
to prevent — reading a green result as a stronger claim than it supports.

---

## Where things stand

Built and verified locally:

| Layer | Artefact | State |
|---|---|---|
| L0 — oracle outside the process | `AgentJobEndToEndTest` | 8 scenarios, all asserting against a bare git remote |
| L1 — proven failure modes | `tools/ci/agent-e2e/faults/` + `run-fault-catalogue.sh` | 7 faults + 1 control |
| L2 — enumerated seam | `seam.txt` + `check-seam.sh` | 1 declared substitution |
| L3 — deployment parity | `BUILD_PROVENANCE` (written by `install.sh`) + `verify-deployed-build.sh` | selftest passes |
| L4 — post-deploy canary | `post-deploy-canary.sh` | selftest passes; never run against the fleet |

CI wiring: `agent-e2e-faults` in `analysis.yaml`, in the `All Checks` merge
gate; `verify-agent-canary` in `deploy.yaml`, after the agent redeploy.

Every check ships a `selftest` subcommand that must be seen both accepting
and rejecting. Each of the fault catalogue, the seam check and the
deployed-build check was additionally watched failing against a deliberately
sabotaged copy of itself. The canary's selftest passes but has not been
sabotage-tested, because the assistant's tooling cannot execute that script
at all (see below) and a human ran it once.

---

## 1. Nothing has run against the real fleet

**The largest gap, by a wide margin.** Both L3 and L4 are wired but have
never executed outside a selftest. Until each has passed once and been seen
to fail once against a real deploy, they are plausible mechanisms rather than
proven ones — which is precisely the epistemic position this whole system
exists to reject.

What to do first, in order:

1. ~~Run `post-deploy-canary.sh selftest` by hand.~~ **Done** — it accepts a
   landed nonce and rejects both an absent one and a nonexistent branch.
   Note for anyone iterating on that file: it has to be run by a human. The
   assistant's tooling refuses to execute any script containing `curl`, and
   splitting the verifier into a separate file purely to get it past that
   check would be the "restructure to evade the guard" move the guard
   forbids. Budget for a human in the loop on every change to it.
2. Trigger a deploy and watch `verify-agent-canary`. Expect: the
   deployed-build check passes, then a real job is submitted, runs on the
   fleet, and a nonce lands on the `agent-canary` branch.
3. Deliberately break it once — deploy a build that cannot publish, or point
   the canary at a nonce that will not land — and confirm the job fails with
   the intended message. A canary nobody has seen go red is decoration.

### Specific things likely to go wrong on that first run

Recorded so the first failure is diagnosed rather than rediscovered:

- **The `agent-canary` branch does not exist yet.** The first canary run
  creates it from `master` via the submit endpoint's workstream
  auto-creation. If `CREATE_WORKSTREAM` handling differs from expectation,
  the submission may be rejected — which the canary correctly treats as
  fatal ("the deploy is UNVERIFIED"), but which will read as a canary bug on
  first sight.
- **Runner contention.** `verify-agent-canary` occupies the
  `ar-deploy-agent` macOS runner for up to `CANARY_TIMEOUT_SECONDS` (default
  1800) while polling. If that is the only runner with that label, nothing
  else using it can proceed meanwhile.
- **Job intake.** `deploy-macos-agent` closes intake for the install and
  reopens it in an `always()` step. The canary is a separate job so it
  submits after intake reopens. If the reopen step ever fails, the canary
  submission is rejected and reports an unverified deploy — correct, but the
  root cause is the reopen, not the canary.
- **`BUILD_PROVENANCE` absent on the first deploy.** The very first run after
  this lands compares against an install that predates provenance recording,
  so `verify-deployed-build.sh` will fail with "no BUILD_PROVENANCE". That is
  the check working. It clears itself on the next deploy.

---

## 2. Keeping the catalogue whole

`MANIFEST` and the fault directories must agree or the runner refuses to run
at all. When adding or removing a fault, change both in the same commit — a
half-applied entry stops the entire catalogue rather than degrading it, which
is deliberate: a catalogue that is not the catalogue it claims to be should
not report results that look like they mean something.

The same applies to a fault's test. Removing a scenario from
`AgentJobEndToEndTest` without removing the fault that names it produces
NO EVIDENCE, not a pass.

---

## 3. Findings recorded but deliberately not fixed

Four places where the code that *looks* like a protection is not the code
doing the work. None is an open hole — in each case something else enforces
the property — but each means a reader debugging the subsystem is sent
somewhere nothing is wrong, and each is a latent trap: the layer that
actually works could be removed while the decorative one stays, and every
test would still pass.

They were left alone on purpose. Deciding which gate guards agent commits is
a design change, and folding it into the commit that merely *found* the
question would make both harder to review.

### 3a. `TestHidingAudit` cannot fire

`CodingAgentJob.validateChanges()` runs `detect-test-hiding.sh`, which
inspects `git diff <base>...HEAD` — committed history — at a point where the
agent's work is still uncommitted. The diff is empty, so the audit always
passes, and it logs "Test integrity check passed", which is positive
evidence for something it did not establish.

Enforced instead by `FileStager`/`TestMethodProtection` at staging time,
byte-for-byte over base-branch test methods in Java — which also covers
tolerance weakening, dimension reduction and `TestDepth` escalation, since
all of those edit an existing method body.

*Suggested fix:* either have the audit read the working tree (a stash or a
scratch worktree, as the staging path already does), or remove it in favour
of the gate that fires. Removing it is defensible; leaving it as-is is not,
because of the log line.

### 3b. `GitManagedJob.createEvent()`'s dropped-work branches are dead

`CodingAgentJob` overrides `createEvent()` and delegates to
`CodingAgentJobEvent.forJob()`, which carries its own copies of
`hasAllChangesDropped()` and `describeUnpublishedWork()`. One rule, two
implementations, one dead for the only subclass that matters.

*Suggested fix:* collapse to one. The live copy is
`CodingAgentJobEvent.forJob()`.

### 3c. The commit-message heuristic is enforced by the wrong branch

The rule asked for after the outage — a commit message written with zero
changes to push is a failure, not a success — has a term written for it,
`messageAuthored` in `JobWorkOutcome.computeUnpublishedWork()`. It never
decides anything: `hasAuthoredCommitMessage()` is true exactly when
`commit.txt` exists; `commit.txt` is an excluded pattern so writing it puts
an entry in the skipped list; a non-empty skipped list with nothing staged
and nothing committed is `hasAllChangesDropped()`; and that branch is
checked first.

The rule holds, but the reported reason is "All changes were dropped by
staging guardrails: commit.txt (excluded pattern)" — which sends anyone
debugging it to the staging code, where nothing is wrong.

*Suggested fix:* check the authored-message case before the
all-changes-dropped case, so the more specific explanation wins; or exclude
`commit.txt` from the skipped-files tally that feeds
`hasAllChangesDropped()`, since it is a harness artefact rather than
agent work that got dropped.

### 3d. `CommitMessageBuilder`'s prompt fallback is a backstop only

By the time `resolve()` runs, `commit.txt` always exists — the agent wrote
it, or `EnforcementRunner` wrote one after the `commit-message` rule
exhausted its retries. Unlike 3a–3c this is **not a defect**: it is
redundancy that works, two layers either of which is sufficient. Recorded so
nobody "cleans up" the unreachable one without realising it is the safety
net.

---

## 4. Coverage the end-to-end suite does not have

Each of these is behaviour the suite does not exercise at all — a larger gap
than an unproven assertion, because there is nothing to be unproven.

- **Dependent repositories.** `computeUnpublishedWork()` has a branch for
  `getDependentRepoFailures()` that no scenario reaches. Needs a second bare
  repository in the fixture and a job configured with `setDependentRepos`.
  Worth doing: a dependent repo whose changes are silently not published is
  the same failure shape as the outage, in a place nobody looks.
- **The phase machinery.** `newJob()` disables review, retrospective and
  falsification so the suite stays about the publish path. Nothing verifies
  that a phase which throws leaves the job reporting honestly, or that
  per-phase model routing does what it claims.
- **`OpencodeRunner`.** Only `ClaudeCodeRunner` is exercised. The seam is
  the agent binary, and there are two runners behind it; the second is
  entirely untested end to end.
- **Budget and turn limits.** A job that exhausts `maxTurns` or
  `maxBudgetUsd` mid-work is a realistic way to produce partial output, and
  no scenario covers what happens to that output.
- **The inactivity watchdog.** `AgentInactivityMonitor` destroys a process
  tree after a silence budget. Nothing tests that a killed session's work is
  reported rather than dropped.
- **Concurrency.** `JobWorkOutcome.capture(boolean)` exists because the
  workspace lock is released before the completion event is built, and a
  second job may already be editing the tree. There is no test for two jobs
  contending for one workspace.

---

## 5. Honest limits of what was built

Stated so a reviewer does not have to discover them by getting something
past a check.

- **`check-seam.sh` is a token scan, not a parser.** It cannot prove the
  absence of a stub. An indirection it does not recognise gets through. What
  it does is make the obvious routes loud, so evading it takes effort that
  reads as deliberate in review.
- **`BUILD_PROVENANCE` does not prove the JARs came from the commit it
  names.** Only a reproducible build proves that, and this is not one. It
  records that the installer ran on a given clean commit and compiled rather
  than reusing whatever was present. It catches a stale or dirty checkout,
  which is the realistic failure.
- **Nothing checks the fake agent still resembles the real CLI.** If Claude
  Code changes its `stream-json` shape, `fake-agent.sh` keeps emitting the
  old shape and every L0 test stays green against a format production no
  longer produces. This is the F2 failure mode waiting to happen inside the
  suite's own foundation.

  *Why it was not closed:* a fidelity fixture needs a reference transcript
  from the real binary. Capturing one means a paid model call, and the only
  transcripts on the development machine are the operator's own session
  logs, which must not be committed to a public repository. **L4 covers this
  drift in practice** — a real job on a real agent fails if the format moved
  — but only after a deploy, not before.

  *If picking this up:* capture a transcript deliberately from a throwaway
  prompt, strip it to event shapes (types and key names, no content), commit
  that as a fixture, and add a test asserting `fake-agent.sh` emits the same
  shapes. Then the staleness problem becomes real and needs its own answer —
  a fixture nobody re-captures is worse than none.
- **The fault catalogue runs one module.** Every fault targets
  `flowtree/runtime`. Nothing prevents a fault elsewhere; nothing exercises
  that path either.
- **`git apply` brittleness.** Fault patches are line-context diffs against
  specific files. Ordinary refactoring will break them, and the runner
  reports "patch no longer applies" rather than silently skipping — correct,
  but it means the catalogue has a real maintenance cost that must be paid
  rather than deferred.

---

## 6. Ideas considered and not pursued

- **Mutation testing over the whole module.** Strictly more thorough than a
  hand-written fault catalogue, and rejected for this purpose: the value
  here is that each fault is a *real defect with a name and a story*, which
  is what makes a NOT DETECTED result interpretable. A mutation score is a
  number nobody acts on. The two are complementary; if mutation testing is
  ever added, it should not replace the catalogue.
- **Signing the fault catalogue.** Considered as protection against an agent
  editing faults on a `ci/` branch, where the CI-file locks are lifted.
  `MANIFEST` was built instead because it is legible and costs nothing; a
  signature would need key handling that the harness deliberately keeps out
  of an agent's environment. Revisit only if a fault is ever found weakened
  in the wild.
- **Asserting exact reason strings.** Rejected as too brittle: tests assert
  that a reason names a specific artefact (`commit.txt`, `tampering`) rather
  than matching whole messages, so wording can be improved without breaking
  the suite. The one place the status itself is pinned exactly is the
  tampering revert (`FAILED`, not merely "not SUCCESS"), because the
  `FAILED`/`DEGRADED` distinction is what a fleet dashboard is read at.
- **A canary that runs continuously rather than post-deploy.** Would catch
  environmental failures between deploys, which is how the original outage
  actually presented. Not built because the post-deploy canary had to exist
  and be trusted first. This is the most valuable single addition once L4 is
  proven.

---

## 7. Notes for whoever edits these tests next

- **`TestFeatures` shadows `assertEquals` with typed overloads only** —
  `(String, PackedCollection, PackedCollection)`, `(String, double, double)`,
  `(double, double, double)`, `(String, int, int)`, `(String, boolean,
  boolean)`. There is no `(String, Object, Object)`, so
  `assertEquals("msg", someEnum, otherEnum)` and
  `assertEquals("msg", "a", b)` do **not** compile. Use
  `assertTrue("msg; got " + x, x.equals(y))`. The two-argument
  `assertEquals(Object, Object)` is fine. This cost two build failures while
  the suite was being written.
- **A compile error in the suite makes the catalogue report NO EVIDENCE on
  every entry**, not a pass and not a failure. That is correct behaviour and
  worth recognising on sight: it means the tree does not build, not that the
  faults stopped working.
- **To find out what a job actually does**, rather than reading code and
  guessing: temporarily add `assertTrue("OBSERVE " + fields, false)` to the
  scenario, run the single method, and read the assertion message from the
  failure. Three wrong inferences in a row were settled this way in minutes.

## 8. For an external reviewer

Things a reviewer will reasonably ask, answered here to save the round trip:

- **"Do these tests actually run in CI?"** `AgentJobEndToEndTest` runs under
  `test-flowtree`. The catalogue, the seam check and the two selftests run
  under `agent-e2e-faults`, which is in the `All Checks` merge gate. The
  canary and the deployed-build check run in `deploy.yaml` after the agent
  redeploy, not on pull requests.
- **"Has any of it been seen failing?"** Every fault was watched failing.
  The fault-catalogue runner, the seam check and the deployed-build check
  were each watched failing against a sabotaged copy of themselves. The
  canary's selftest passes — it accepts a landed nonce and rejects an absent
  one — but has not been sabotage-tested, because that script can only be
  run by a human here (section 1).
- **"Why is the fault catalogue's control necessary?"** Without an entry
  that must *pass*, a runner that printed "detected" unconditionally would
  satisfy every other entry forever. The control pins the other direction.
  `run-fault-catalogue.sh` refuses to report success on a full run when no
  entry declares `outcome=pass`.
- **"Why are known defects left unfixed?"** Section 3. They are recorded,
  none is an open hole, and changing which gate guards agent commits is a
  design decision that should not ride along with the tooling that surfaced
  it.
- **"What would make this system fail silently?"** The honest answers are in
  section 5. The most likely is fake-agent drift.
