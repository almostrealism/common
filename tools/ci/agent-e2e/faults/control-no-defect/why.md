# Control: a patch that must not break anything

## What this entry is

Not a defect. This is the catalogue's control: a patch that inserts one
comment line into `McpConfigBuilder.requiredServerNames()` and changes no
behaviour whatsoever. `agentWorkReachesTheRemote` must still **pass** with it
applied, and `run-fault-catalogue.sh` fails if it does not.

## Why it exists

Every other entry asserts a negative result — "the test failed, therefore it
covers the defect." A runner that reported `detected` unconditionally would
satisfy all of them, forever, while verifying nothing. That is exactly the
failure the catalogue was built to catch, occurring one level up in the tool
that does the catching.

This entry closes that. The two directions are now both pinned:

- a runner stuck on `detected` fails **this** entry;
- a runner stuck on `passed` fails **every other** entry.

Neither can be green at the same time as the other, so a fully green
catalogue is evidence that the runner discriminates rather than evidence that
it is easy to please.

The runner refuses to report success on a full run if no entry declares
`outcome=pass`, so deleting this directory does not quietly remove the check.

## The second thing it measures

The patch is inert, so any failure of `agentWorkReachesTheRemote` under it is
either flakiness in the test or a patch that does more than it claims. Both
are reported as `CONTROL BROKEN` rather than as a pass, because while the most
important test in the suite is unstable, a `detected` verdict from this runner
is not worth much either — a test that sometimes fails on its own will
sometimes "detect" a fault it does not cover.

## Choosing the method

It names `agentWorkReachesTheRemote` deliberately: the plain happy path, an
agent's edit arriving in a bare remote. If the catalogue is going to hold one
test to a stability standard, that is the one.
