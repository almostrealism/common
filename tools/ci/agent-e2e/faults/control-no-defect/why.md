# Control: a fault that must not break anything

## What this entry is

Not a defect. This is the catalogue's control.
`AgentJobFaultCatalogue.controlNoDefect` installs, through every seam the
other faults use, the production collaborator that seam normally holds — a
plain `McpConfigBuilder`, a plain `JobWorkOutcome`, a plain
`CommitMessageBuilder` — and runs `agentWorkReachesTheRemote`. That scenario
must still **pass**, and `run-fault-catalogue.sh` fails if it does not.

## Why it exists

Every other entry asserts a negative result — "the scenario failed, therefore
it covers the defect." A runner that reported `detected` unconditionally
would satisfy all of them, forever, while verifying nothing. That is exactly
the failure the catalogue was built to catch, occurring one level up in the
tool that does the catching.

This entry closes that. The two directions are both pinned:

- a runner stuck on `detected` fails **this** entry;
- a runner stuck on `passed` fails **every other** entry.

Neither can be green at the same time as the other, so a fully green
catalogue is evidence that the runner discriminates rather than evidence that
it is easy to please.

The runner refuses to report success on a full run if no entry declares
`outcome=pass`, so deleting this directory does not quietly remove the check.

## The second thing it measures

The seams are only honest if replacing a collaborator with an identical one
changes nothing. A seam that dropped configuration on the way through — a
replaced MCP builder that lost the job's ar-manager settings, say — would
make every fault that uses it "detected" for a reason that has nothing to do
with its defect. Installing the production collaborators through the same
seams, and requiring the happy path to survive, is what rules that out.

Any failure here is therefore either a seam that is not inert or flakiness
in the test. Both are reported as `CONTROL BROKEN` rather than as a pass,
because while the most important scenario in the suite is unstable, a
`detected` verdict from this runner is not worth much either.

## Choosing the scenario

It runs `agentWorkReachesTheRemote` deliberately: the plain happy path, an
agent's edit arriving in a bare remote. If the catalogue is going to hold one
scenario to a stability standard, that is the one.
