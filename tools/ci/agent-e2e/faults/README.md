# Fault catalogue

Each fault is a real defect, expressed as a patch, paired with the test that
must fail when it is applied.

`run-fault-catalogue.sh` applies each one to a scratch git worktree, runs the
named test, and requires that test to **fail**. If it passes, the test has
stopped covering what it claims to cover, and the build fails.

This exists because a green test is not evidence. During the outage this
tooling was built after, an end-to-end test named for a specific defect passed
while that defect was live, because the code path it named was unreachable
from the job it constructed. It was green and it covered nothing, and nothing
in the pipeline could tell.

## Format

One directory per fault:

```
faults/
  <fault-name>/
    fault.patch      applied with `git apply` at the repository root
    expect.txt       key=value: the module and test that must fail
    why.md           what the defect is, and the real failure it models
```

`expect.txt` fields:

| key | meaning |
|---|---|
| `module` | Maven module to run, e.g. `flowtree/runtime` |
| `test` | Test class to run |
| `method` | The specific method whose result is read |
| `outcome` | `fail` (default) or `pass` — see below |

Naming the method matters. A fault that breaks compilation, or breaks some
unrelated test, would otherwise satisfy "something failed" without proving the
named test covers anything.

## The control

Almost every entry declares `outcome=fail`: reintroduce the defect, and the
test that exists to catch it must catch it.

At least one entry must declare `outcome=pass`. That entry's patch changes no
behaviour, and its test must still pass. Without it, a runner that printed
`detected` unconditionally would satisfy every other entry forever while
verifying nothing — the same "green and covering nothing" failure the
catalogue exists to detect, one level up in the tool doing the detecting.

With both kinds present the runner is pinned from both sides: stuck on
`detected` fails the control, stuck on `passed` fails everything else.
`run-fault-catalogue.sh` refuses to report success on a full run if no entry
declares `outcome=pass`, so the control cannot be quietly deleted. See
`control-no-defect`.

## Running it

```
tools/ci/agent-e2e/run-fault-catalogue.sh              # every entry
tools/ci/agent-e2e/run-fault-catalogue.sh <name> ...   # just these (debugging)
```

All entries share one scratch worktree, reset between them, so the first
entry pays for the build and the rest are incremental. Set `MVN_OFFLINE=` to
allow Maven to reach the network when `~/.m2` is not primed.

CI runs it in the `agent-e2e-faults` job, which also runs the post-deploy
canary's verifier selftest.

## MANIFEST

`MANIFEST` names every fault that must exist. The runner requires each name
to have a directory and each directory to be named, and refuses to run
anything when the two disagree.

It is here because deleting a fault directory would otherwise be a silent
way to make the catalogue pass — fewer entries, all green, nothing in the
output saying something used to be checked. That is the same move as
deleting a failing test, and cheaper: a fault has no obvious owner, and its
absence looks like it was never there.

Removing a fault stays allowed. A defect can genuinely stop being
expressible. It just takes an edit to `MANIFEST` in the same change, where a
reviewer sees the name and its `why.md` disappearing together.

## Adding a fault

Add one whenever a defect reaches production. The question to answer is not
"how do I stop this recurring" but "**what test would have caught this, and
can I prove it would have?**" The patch is that proof.

A fault should model a plausible defect — a condition inverted, a check
removed, a value dropped on the way through — not a syntax error.

Add the name to `MANIFEST` in the same change.

Two things learned from writing the ones that are here:

**Keep every symbol referenced.** A patch that deletes code can leave an
import or a private helper unused, and if that fails the build the runner
reports NO EVIDENCE — which reads like the fault proving nothing rather than
like a broken patch. `missing-commit-message-drops-work` guards the removed
write behind an always-false condition instead of deleting it, so the
imports and the helper stay in use.

**A fault must remove every layer that supplies the property.** More than
once here, deleting the obvious implementation changed nothing observable
because a second one covered for it. NOT DETECTED in that situation is
information about the system — usually that the code you were reading is not
the code doing the work — and never a reason to weaken the test until it
notices.
