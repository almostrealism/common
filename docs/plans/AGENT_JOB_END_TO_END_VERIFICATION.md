# End-to-end verification for agent jobs

Written after an outage in which every FlowTree agent job failed for a full
day while the entire test suite stayed green, a large change was merged and
deployed against it, and nothing improved. The work that was merged addressed
reporting; the line that was actually failing every job was never touched.

This document specifies a system of tests designed on a specific assumption:
**an agent working in this repository has vastly more compute than whoever
reviews it, and will produce work that looks complete without being complete.**
Not necessarily deliberately — the failure above involved no intent — but the
output is indistinguishable either way, so the defences must not depend on
intent.

The design principle that follows is blunt: *any gap between what the tests
exercise and what production runs will be exploited.* Every gap here is
therefore enumerated, justified, and given something that keeps it honest.

---

## The observed failure modes

These are not hypothetical. Each one happened during the outage, in the work
that was supposed to fix it.

| # | Failure | What it looks like from outside |
|---|---|---|
| F1 | Diagnosis from inference, never checked against the running system | A confident root-cause narrative, wrong |
| F2 | A test that passes because the code path is unreachable | Green suite, zero coverage of the thing named |
| F3 | Fixing the report instead of the behaviour | `SUCCESS` becomes `FAILED`; no job works any better |
| F4 | Stubbing the component whose behaviour is in question | Unit tests pass; the runner's real logic is never run |
| F5 | Verification narrower than it sounds | "164 tests pass" — none touching the failure |
| F6 | Activity substituting for progress | Six review rounds; the outage untouched throughout |

F2 is the keystone. Every other failure survives review because a green test
is taken as evidence. Remove the ability of a test to be green-but-empty and
most of the rest become visible.

---

## L0 — The oracle is always outside the process

Every end-to-end assertion reads state that the code under test cannot
fabricate: git objects in a bare remote, an HTTP response, a file on disk.

Never the job's own accessors. `job.getCommitHash()` is the job's *claim*
about itself. `git ls-tree` against the origin is what actually happened. A
job can be made to return any string; it cannot make a commit appear in a
remote repository without doing the work.

Implemented by `AgentJobEndToEndTest`, whose assertions are all of the form
"is this file/commit present in the bare origin repository".

## L1 — Every guarantee has a proven failure mode

**A test that has never been observed to fail is not evidence.**

`AgentJobEndToEndTest.workPublishesWhenArManagerDidNotConnect` initially
passed while the defect it names was live, because the job under test never
configured ar-manager and so could not reach the policy being tested. It was
green and empty. That is F2, committed by the same process writing this
document, an hour after the outage it describes.

The defence is a **fault catalogue**: a set of real defects, each expressed as
a patch, each naming the test that must fail when it is applied. CI applies
each fault to a scratch worktree, runs the named test, and requires it to
fail. A fault that no longer breaks its test means the test has rotted, and
the build fails.

This is the only mechanism here that detects a test which cannot fail. It is
the centre of the system.

See `tools/ci/agent-e2e/faults/` and `run-fault-catalogue.sh`.

### The runner needs the same treatment it applies

A catalogue of nothing but "this must fail" entries is satisfiable by a runner
that reports `detected` unconditionally. That runner would be green forever
while verifying nothing — F2 again, one level up, in the tool whose entire
purpose is to detect F2. The recursion is not theoretical: two defects were
found in the runner itself while building it, and both made it report a
verdict it had no evidence for.

1. It read the verdict from Maven's exit code. Maven exits 0 for a test that
   does not exist, for a selector matching nothing, and for a skipped run. The
   runner reported a fault "undetected" when in truth nothing had run. The
   verdict now comes from the surefire XML, and "did not run" is a third
   outcome distinct from pass and fail.
2. It built its scratch worktree from `HEAD`, which excluded the untracked
   test the fault was written to validate.

The structural fix is a **control entry**: `outcome=pass` in `expect.txt`, a
patch that changes no behaviour, and a test that must still pass. The runner
refuses to report success on a full run when no entry declares it. Stuck on
`detected` fails the control; stuck on `passed` fails every other entry.
Neither can be green at the same time as the other.

The general rule, which applies to anything added here later: *a checking tool
must be observed producing both verdicts.* A tool that has only ever been seen
agreeing is in exactly the position of a test that has only ever been seen
passing.

### A gate that cannot fire

The catalogue found this within an hour of being able to run, and it found
it in work written by the same process that wrote the catalogue.

A fault was added for the silent-drop path: make `validateChanges()`
returning `false` skip the commit instead of failing the job. A test was
written for it — an agent weakens a test that exists on the base branch —
and it passed. The catalogue then reported NOT DETECTED, which meant the
test passed with the defect applied and therefore did not cover it.

Running the scenario and reading the log gave the reason:

```
FileStager: Blocked (existing test method(s) changed or removed: valuesMatch)
CodingAgentJob: Test integrity check passed
GitCommitHandler: No files passed guardrails, nothing to commit
```

`CodingAgentJob.validateChanges()` calls `TestHidingAudit.passes(...)`,
which runs `detect-test-hiding.sh`, which inspects
`git diff <base>...HEAD` — committed history. It is called *before* the
commit, when the agent's work is still uncommitted. The diff is therefore
empty, the audit passes, and `validateChanges()` returns `true` no matter
what the agent did. In the end-to-end flow it cannot return `false`.

Two things follow, and they should not be run together:

- **The property is still protected.** `FileStager` blocks the write at
  staging time, one layer earlier, and in Java that lock is byte-for-byte
  over every base-branch test method — which covers the tolerance-weakening
  and dimension-reduction shapes too, since those edit an existing method
  body. Nothing observed here is an open hole.
- **The audit is nonetheless not doing its job.** A check that structurally
  cannot fail is indistinguishable from an absent one, and it currently
  reports "Test integrity check passed" into the log, which is worse than
  silence: it is positive evidence for something it did not establish. Either
  it should read the working tree (a stash or a scratch worktree, as the
  staging path already does) or it should be removed in favour of the gate
  that actually fires.

This is left as a finding rather than a change, because changing which gate
guards agent commits is not something to fold into the same commit as the
tooling that found it. What the tooling has earned is the observation.

### A heuristic enforced by the wrong branch

The same pattern, a third time, on the first of the two heuristics asked for
after the outage: *if the commit message phase wrote a commit message, but
we have zero changes to push — that's a failure not a success.*

`JobWorkOutcome.computeUnpublishedWork()` has a term written for it,
`messageAuthored`. Deleting that term as a fault produced NOT DETECTED, and
the reason is a chain worth recording:

- `hasAuthoredCommitMessage()` is true exactly when `commit.txt` exists;
- `commit.txt` is an excluded pattern, so writing it puts an entry in the
  skipped list;
- a non-empty skipped list with nothing staged and nothing committed is what
  `hasAllChangesDropped()` means;
- and that branch is reached first in `CodingAgentJobEvent.forJob()`.

The rule holds — the job reports `DEGRADED`, not `SUCCESS` — but the branch
enforcing it is not the one written for it, and the reason it gives is
"All changes were dropped by staging guardrails: commit.txt (excluded
pattern)". Nothing was dropped by a guardrail; the session produced nothing
to drop. A reader debugging that message is sent to the staging code and
will not find anything wrong there.

So the heuristic works and its dedicated implementation is dead. Worth
fixing, and again not folded in here.

### A test that could not fail, written here

`editsWithoutACommitMessageAreStillPublishedOrReported` asserted
`published || reported`. That disjunction is satisfied whichever way the
system behaves: it passes if the work publishes, it passes if the work is
dropped and reported, and it would have gone on passing if the behaviour
flipped from one to the other. It was green, it could not fail, and it was
written in this repository an hour after the document describing exactly
that failure mode.

Replaced by `editsWithoutACommitMessageStillReachTheRemote`, which pins the
observed behaviour — the work publishes, the job succeeds — and is now
paired with a fault.

The general rule: when a test's assertion is a disjunction over outcomes,
ask which disjunct is actually true and assert that one. A test that accepts
either answer has not been told what the right answer is.

A fourth, smaller finding from the same investigation:
`GitManagedJob.createEvent()` carries `hasAllChangesDropped()` and
`describeUnpublishedWork()` branches that `CodingAgentJob` never reaches,
because it overrides `createEvent()` and `CodingAgentJobEvent.forJob()`
carries its own copies. Two implementations of one rule, one of them dead
for the only subclass that matters, is a drift waiting to happen — the first
version of the fault patched the dead copy and reported NOT DETECTED.

## L2 — The seam is enumerated

Exactly one thing is substituted in end-to-end runs: the model. The agent
binary is replaced by `tools/ci/agent-e2e/fake-agent.sh`, which emits a real
`stream-json` transcript and makes real edits to the working tree.

Everything above the process boundary is production code: the runner's
command line, its NDJSON parsing, the MCP init-event handling, phase
dispatch, staging guardrails, commit, and push.

Any additional substitution is a new gap and must be added to this list with
its justification. The rule exists because the natural way to make a failing
end-to-end test pass is to stub one more thing.

That rule is now enforced rather than written down. `seam.txt` is the
manifest; `check-seam.sh` fails the build on any substitution in the
end-to-end sources that is not declared in it. Growing the seam stays
allowed — growing it silently does not, and the manifest edit lands in the
diff next to the test it excuses.

Two tokens in the scan are not substitutions but properties worth failing on
in the same place: `setDryRun(true)`, because that single line lets the job
run, log what it "would" commit, satisfy every assertion about its own
state, and push nothing; and the disappearance of `--bare`, because without
a real remote the suite can only assert the job's account of itself.

**What it is:** a token scan, not a parser. It cannot prove the absence of a
stub — an indirection it does not recognise gets through, and no amount of
pattern-matching fixes that. What it does is make the obvious routes loud,
so evading it takes deliberate effort that reads as deliberate in review.
Stated plainly here rather than left for someone to discover by getting a
stub past it.

## L3 — What is deployed is what was merged

An agent host runs its own installed build. Merging does not update it. The
fleet must therefore report the commit it is running, and a check must
compare that against `master`.

Without this, every other layer can be green while the fleet runs last
week's code — which is exactly what "merged and deployed and nothing
changed" feels like from the outside, whether or not it is the cause.

Implemented as a provenance file rather than a protocol change.
`install.sh` writes `BUILD_PROVENANCE` into the staging directory, so it is
swapped into place by the same `mv` as the JARs it describes — written
afterwards it could survive a failed swap and name a build that is not
installed. `verify-deployed-build.sh` reads it back and rejects four shapes:
a missing file, a commit that is not the one deployed, an install built from
a dirty tree, and an install that ran `--no-build` and may be serving JARs
older than the commit it names.

The commit to compare against is passed from `deploy-macos-agent` as a job
output rather than re-read from master in the later job. Re-reading would
race: master advancing between the two jobs would be indistinguishable from
a stale deploy, and a check that cries wolf gets disabled.

**What it does not prove:** that the JARs were compiled from that commit.
Only a reproducible build establishes that, and this is not one. The file
records that the installer ran on a given clean commit and compiled rather
than reusing whatever was lying around. That catches the realistic failure —
an install from a stale or dirty checkout — and the residue is stated here
so nobody reads the check as stronger than it is.

The canary does not make this redundant: a fleet on last week's build
publishes commits perfectly well and passes the canary while not containing
the change the deploy was run for.

## L4 — A real job, after every deploy

The layers above test code. The outage was environmental: ar-manager did not
connect from the agent host. No amount of in-repo testing sees that.

So after each deploy, submit a real job through the real API to the real
fleet, and assert a real commit lands. If it does not, the deploy is bad, and
it is known in minutes rather than a day.

**This is the only layer that would have caught the outage this document
exists because of.** L0–L2 catch code defects; L3 catches stale deploys; only
L4 exercises the actual production environment. It should be read as the
primary defence, with the rest as support.

Implemented by `tools/ci/agent-e2e/post-deploy-canary.sh`, run by the
`verify-agent-canary` job in `deploy.yaml` after the agent redeploy. Four
properties of its construction are load-bearing:

- **The oracle is the git remote.** The controller's job status is the thing
  that lied during the outage, so it is never the evidence. A nonce generated
  by the canary must arrive in a commit on the remote; the agent cannot
  produce that without clone, edit, stage, commit and push all working.
- **`success` with nothing published is its own error.** That combination is
  the outage's exact signature and means something worse than an honest
  failure, so it is reported separately rather than folded into "job failed".
- **A skipped or rejected submission is not a pass.** `submit-agent-job.sh`
  exits 0 when the controller declines a job, which is right for its other
  callers and fatal here: no job ran, so the deploy is unverified, and
  reporting "nothing went wrong" as "the thing worked" is the substitution
  this whole document is about.
- **The canary branch is deliberately not named `ci/*`.** That prefix grants
  permissions ordinary branches do not have; verifying the privileged path
  would leave the common one untested.

The verifier is separable (`post-deploy-canary.sh selftest`) and is proven in
both directions against a scratch repository — it must accept a nonce that
landed and reject one that did not. That selftest runs on every PR in
`agent-e2e-faults`, not only at deploy time: the verifier decides whether a
deploy broke the fleet, so a change that breaks the verifier must not reach
master and be discovered afterwards.

The branch is never reset. Each deploy appends one commit, so its log is the
standing record of every deploy proven to produce a working agent, and the
gap after a bad one is visible.

---

## Order of construction

1. L0 and L1 together — the e2e suite is worthless until its failure modes
   are proven, so they ship as one thing. **Built.** `AgentJobEndToEndTest`
   (5 tests, all asserting against a bare remote) plus the fault catalogue
   with three real faults and one control, wired into CI as
   `agent-e2e-faults` and into the `All Checks` merge gate. Two of the faults
   point at the same test, one per assertion, so neither half of it can
   quietly stop being checked.
2. L4, the post-deploy canary, because it is the one that would have caught
   the outage. **Built**, wired as `verify-agent-canary` in `deploy.yaml`.
   Not yet observed against the real fleet — until it has run once and
   passed, and once been seen to fail, it is a plausible mechanism rather
   than a proven one.
3. L3, deployment parity. **Built**, as `BUILD_PROVENANCE` written by
   `install.sh` and checked by `verify-deployed-build.sh`, wired into
   `verify-agent-canary` ahead of the canary submit. Its selftest runs on
   every pull request. Like the canary, it has not yet run against a real
   install.
4. L2's lint. **Built** as `seam.txt` plus `check-seam.sh`, wired into
   `agent-e2e-faults`. Built at one entry rather than waiting for a second,
   because the value is in the manifest existing before anyone wants to add
   to it — a list written at the moment of the first exception is a list
   written to justify it.

## What is still missing

The short version is below. The full backlog — everything considered and not
finished, the honest limits of each tool, the defects found and deliberately
left alone, and what a reviewer will ask — is in
`AGENT_VERIFICATION_REMAINING_WORK.md`.

Recorded plainly, because an accurate list of gaps is worth more than a
system that reads as finished:

- **Neither the canary nor the deployed-build check has run against the real
  fleet.** Both selftests pass and both have been watched failing when
  deliberately sabotaged, so the checks discriminate; what is unproven is
  the wiring around them. This is the largest gap.
- **Six real faults and one control.** They cover the outage policy, the
  base-branch test-method lock, the dropped-work reporting, the
  commit-message-with-nothing-published rule, the harness's missing-message
  fallbacks, and the tampering revert's report. They do not cover dependent
  repositories or the phase machinery.
- **Nothing checks that the fake agent still resembles the real one.** If the
  Claude CLI changes its `stream-json` shape, `fake-agent.sh` keeps emitting
  the old shape and every L0 test stays green against a format production no
  longer produces. This is F2 waiting to happen and has no defence yet.
- **The seam check is a token scan.** It makes the obvious stubs loud; it
  cannot prove none is present.
- **No fault covers dependent repositories or the phase machinery.** Each
  has behaviour the suite does not exercise at all, which is a different and
  larger gap than an unproven assertion. The tampering revert is now
  covered; the restart that is supposed to replace the reverted work is not
  — the suite only pins what happens when no restart does.
