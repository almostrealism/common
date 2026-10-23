# Fault catalogue

Each fault is a real defect, expressed as code in a test class, paired with
the end-to-end scenario that must fail when it is installed.

`AgentJobFaultCatalogue` (in `flowtree/runtime`'s tests) has one method per
fault. Each method builds the fixture of `AgentJobEndToEndTest`, installs a
deliberately broken collaborator on every job that fixture creates, and runs
one of its scenarios through a method reference. `run-fault-catalogue.sh`
runs the whole class in one Maven invocation and requires each method to
record the outcome its directory here declares — almost always **fail**. If
one passes, the scenario has stopped covering what it claims to cover, and
the build fails.

This exists because a green test is not evidence. During the outage this
tooling was built after, an end-to-end test named for a specific defect passed
while that defect was live, because the code path it named was unreachable
from the job it constructed. It was green and it covered nothing, and nothing
in the pipeline could tell.

## Why the faults are code, not patches

The first version of this catalogue expressed each defect as a `fault.patch`
against a production class, applied to a scratch worktree before the test
ran. It was dropped, for a structural reason rather than a cosmetic one.

The catalogue is a required check. A patch pins the exact lines it edits, so
any pull request that refactored one of those lines — in `CodingAgentJob`,
`CodingAgentJobEvent`, `CommitMessageBuilder`, `EnforcementRunner`,
`JobWorkOutcome`, `McpConfigBuilder` or `TestMethodProtection` — turned the
check red until someone re-cut the patch. And because the patches lived under
`tools/ci/`, only a `ci/...` branch could re-cut them. Seven job-system
classes had quietly become protected files that ordinary work could not
touch. One patch had in fact stopped applying after an unrelated refactor
by the time this was noticed.

As code, a fault is a subclass of the collaborator it breaks. When that
class changes shape, the catalogue stops compiling, and whoever changed it
fixes the fault in the same pull request, with the same permissions. When a
class changes without changing shape, the fault keeps working untouched.
Running every fault in one invocation also replaced one module rebuild per
fault, which cost roughly twenty minutes of CI time.

## Where a fault may live

**Preferred: a test-side double behind a real seam.** The defect is a
subclass or alternative implementation of a collaborator the job already
uses, installed through a seam the job exposes for it:

| Seam | Used by |
|---|---|
| `CodingAgentJob.setMcpConfigBuilder` | `required-mcp-server-kills-job`, the control |
| `CodingAgentJob.setCommitMessageBuilder` | `missing-commit-message-drops-work`, the control |
| `GitManagedJob.setWorkOutcome` | `dropped-work-reported-as-success`, `all-changes-dropped-not-reported`, `reverted-work-not-reported`, the control |
| `RestartGovernor.setMaxTotalSessions` (an existing setting) | `tampering-restart-skipped` |

Each seam is package-private and documented as a test seam; production code
never calls it and carries no malfunction logic. Where a defect sits behind
no seam, add the smallest honest one — constructor or setter injection of
the collaborator — rather than putting the defect in production code.

**Fallback only: a fault switch.** If a defect genuinely cannot be reached
through a seam, a switch in production code is acceptable only if it is
settable from test code alone (package-private; no system property, no
environment variable, no job or wire field), is named so it is obviously
test-only, is covered by a test proving it is off by default, and is listed
in `../seam.txt`, where `check-seam.sh` holds it to the "every substitution
is declared" rule. There are none today.

## Format

One directory per fault:

```
faults/
  <fault-name>/
    expect.txt       key=value: the catalogue method and its required outcome
    why.md           what the defect is, the real failure it models, and why
                     no unit test already catches it
```

`expect.txt` fields:

| key | meaning |
|---|---|
| `module` | Maven module to run, e.g. `flowtree/runtime` |
| `test` | The catalogue class, e.g. `AgentJobFaultCatalogue` |
| `method` | The catalogue method whose result is read |
| `outcome` | `fail` (default) or `pass` — see below |

The verdict comes from the Surefire XML, never Maven's exit code — Maven
exits non-zero on every healthy run here, because the faults are supposed to
fail. A method counts as detecting its fault only when it records a
`<failure>`: the scenario's own assertion tripped. An `<error>` — the double
threw, the scenario timed out — means the defect broke the machinery before
any assertion was reached, and is reported as unproven.

## What earns a place

Keep a fault only if it models an outage class that has actually happened,
or one the unit tests demonstrably cannot catch. A defect a unit test already
watches failing adds CI time and maintenance without adding evidence. Say
which in the fault's `why.md`, with the evidence: the outage, or the unit
tests that were run with the defect applied and still passed.

## The control

Almost every entry declares `outcome=fail`: install the defect, and the
scenario that exists to catch it must catch it.

At least one entry must declare `outcome=pass`. `control-no-defect` installs
the production collaborators through every seam the faults use, and its
scenario must still pass. Without it, a runner that printed `detected`
unconditionally would satisfy every other entry forever while verifying
nothing — the same "green and covering nothing" failure the catalogue exists
to detect, one level up in the tool doing the detecting. It also proves the
seams themselves are inert.

With both kinds present the runner is pinned from both sides: stuck on
`detected` fails the control, stuck on `passed` fails everything else.
`run-fault-catalogue.sh` refuses to report success on a full run if no entry
declares `outcome=pass`, so the control cannot be quietly deleted, and its
`selftest` proves the verdict reader against synthetic reports offline.

## Running it

```
tools/ci/agent-e2e/run-fault-catalogue.sh              # every entry
tools/ci/agent-e2e/run-fault-catalogue.sh <name> ...   # just these (debugging)
tools/ci/agent-e2e/run-fault-catalogue.sh selftest     # the verdict reader alone
```

The modules `flowtree/runtime` depends on must already be installed in the
local Maven repository. Set `MVN_OFFLINE=` to allow Maven to reach the
network when `~/.m2` is not primed.

CI runs it in the `agent-e2e-faults` job, on `build`'s shared artifacts,
alongside the selftests of the post-deploy canary, the deployed-build check
and the seam check.

## MANIFEST

`MANIFEST` names every fault that must exist. The runner requires each name
to have a directory and each directory to be named, and refuses to run
anything when the two disagree. On a full run it also requires every method
of the catalogue class to be claimed by a directory, so a fault cannot be
added to the code without saying what it models.

It is here because deleting a fault directory would otherwise be a silent
way to make the catalogue pass — fewer entries, all green, nothing in the
output saying something used to be checked. That is the same move as
deleting a failing test, and cheaper: a fault has no obvious owner, and its
absence looks like it was never there.

Removing a fault stays allowed. A defect can genuinely stop being
expressible, or turn out to be covered by unit tests. It just takes an edit
to `MANIFEST` in the same change, where a reviewer sees the name and its
`why.md` disappearing together.

## Adding a fault

Add one whenever a defect reaches production. The question to answer is not
"how do I stop this recurring" but "**what test would have caught this, and
can I prove it would have?**" The fault is that proof.

1. Add a method to `AgentJobFaultCatalogue` that installs the defect and
   runs the scenario that must catch it.
2. Add `faults/<name>/expect.txt` naming that method, and `why.md`.
3. Add the name to `MANIFEST`.

A fault should model a plausible defect — a condition inverted, a check
skipped, a value dropped on the way through — not a crash. Two things learned
from writing the ones that are here:

**Fail an assertion, do not throw.** A double that throws reaches no
assertion and is reported ERRORED, which proves nothing about the scenario.
Return the wrong answer instead, as the defect would.

**A fault must remove every layer that supplies the property.** More than
once here, breaking the obvious implementation changed nothing observable
because a second one covered for it. NOT DETECTED in that situation is
information about the system — usually that the code you were reading is not
the code doing the work — and never a reason to weaken the test until it
notices.
