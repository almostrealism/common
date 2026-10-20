# analysis.yaml staging blocker (PR #541 / ci/commit-validation-reorg)

## Status: needs human intervention

A review-round change to `.github/workflows/analysis.yaml` **cannot be committed
by an agent job on this branch**. The exact intended change is saved next to this
note as [`analysis-yaml-trusted-copy.patch`](analysis-yaml-trusted-copy.patch)
and can be applied with:

```
git apply docs/plans/analysis-yaml-trusted-copy.patch
```

## What the change does

Two CI steps (`changes` -> "Check the CI file lock", and the python-test-hiding
detector step) currently **skip their check with `exit 0`** when the PR head
predates the enforcement script. The change replaces that bypass with running a
**trusted `origin/master` copy** of the script: `git archive origin/master
tools/ci/agent-protection/` is extracted into a temp dir and the script is run
from there (the working tree is left untouched), failing closed if `origin/master`
also lacks it. This closes the "a branch based before the script existed skips the
check entirely" gap raised in the PR review.

## Why an agent cannot commit it

The deployed FlowTree harness (`FileStager`) protects `.github/workflows/**`
whole-file: any edit to a workflow file that exists on the base branch is dropped
at staging with `protected - exists on base branch`. On this run only
`analysis.yaml` was rejected — the `tools/ci/*.sh` edits staged normally — which
shows the deployed harness protects `.github/workflows/**` but not `tools/ci/**`,
and has **no `ci/...` branch exemption** for workflow files.

This branch is precisely the one that *adds* that exemption
(`GitCommitHandler.buildStagingConfig`: `protectCiFiles =
isSensitiveFileProtectionEnabled(job) && !isCiBranch(targetBranch)`), but that
code is not yet deployed. It is a bootstrap problem: the fix that would let the
edit through is the change this branch introduces.

No sub-file workaround exists — unlike a protected test file (where a new test
method can be added), a whole-file-protected YAML workflow has no partial-edit
escape, the logic is inline `run:` shell that cannot be relocated to a branch-new
script without still editing `analysis.yaml`, and the `Sensitive-File-Bypass`
trailer needs a controller signature an agent cannot produce.

## Note on prior sessions

A prior session recorded this fix as landed, but the harness had silently dropped
it every time. `HEAD:.github/workflows/analysis.yaml` still contains the old
`exit 0` bypass (the CI-lock step and the python-detector step). The corresponding
review thread is therefore **not** actually addressed in the committed branch
until a human applies the patch above (a human commit bypasses the agent-only
harness protection), or the updated harness is deployed and the job re-run.

## Working-tree state (why the edit is not sitting un-staged)

Earlier sessions left the un-committable edit live in the working tree "to
preserve intent." The harness drops it every commit, so it never lands — but
its continued presence makes each commit report *"changes will NOT be
committed"*, which re-dispatches this same job, and the next session
re-confirms the identical blocker. That is the loop.

This session broke the loop by restoring
`.github/workflows/analysis.yaml` to its `HEAD` content. **No intent is lost:**
the exact change lives in [`analysis-yaml-trusted-copy.patch`](analysis-yaml-trusted-copy.patch)
(verified byte-identical to the reverted edit, and `git apply --check` passes
cleanly against `HEAD`). Reverting the working-tree edit and dropping it at
staging produce the *identical* commit; the only difference is that the working
tree is now honest about what can actually be committed, so the job stops
re-triggering on a file it can never stage.

The human action is unchanged: `git apply docs/plans/analysis-yaml-trusted-copy.patch`
and commit it directly, or deploy the updated harness and re-run.
