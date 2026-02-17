# Auto-Resolution of Failing Tests via FlowTree Coding Agent

## Status: Implemented

The auto-resolve pipeline described below has been implemented in
`.github/workflows/analysis.yaml` with supporting scripts under `tools/ci/`.

---

## Overview

When tests fail in the `analysis.yaml` CI workflow, the pipeline collects
failure details from Maven Surefire XML reports, builds a prompt describing
the failures, and submits a FlowTree coding-agent job to attempt a fix.

---

## Architecture

### Workflow structure (`analysis.yaml`)

```
build  ─> test (matrix 0-6) ─> test-ml  ─> test-music ─> analysis ─> auto-resolve
                              ─> test-audio ─┘
```

- Each test job uploads **Surefire XML reports** as artifacts (in addition to
  coverage data).
- The `auto-resolve` job runs with `if: always()` and
  `needs: [test, test-ml, test-audio, test-music, analysis]`, so it executes
  after all test and analysis jobs complete, even when tests fail.
- The job is skipped on the `master` branch.

### Scripts (`tools/ci/`)

Reusable logic is delegated to standalone scripts rather than embedded in
workflow YAML:

| Script | Purpose |
|--------|---------|
| `parse-surefire-failures.sh` | Scans a directory of Surefire XML reports and extracts `ClassName#methodName` for each failure |
| `build-resolve-prompt.sh` | Assembles the natural-language prompt for the FlowTree agent |
| `submit-agent-job.sh` | POSTs the prompt to the FlowTree controller's workstream endpoint |

### Information flow

```
test jobs                    analysis.yaml
 ────────                    ─────────────
 ┌──────────┐
 │ test (0-6)│──upload──> surefire-utils-group-N (artifact)
 └──────────┘
 ┌──────────┐
 │ test-ml   │──upload──> surefire-ml (artifact)
 └──────────┘
 ┌──────────┐
 │test-audio │──upload──> surefire-audio (artifact)
 └──────────┘
 ┌──────────┐
 │test-music │──upload──> surefire-music-compose (artifact)
 └──────────┘
       │
       ▼
 ┌──────────────┐
 │ auto-resolve  │
 │               │
 │ 1. download   │◀── all surefire-* artifacts
 │ 2. parse      │──> parse-surefire-failures.sh
 │ 3. prompt     │──> build-resolve-prompt.sh
 │ 4. submit     │──> submit-agent-job.sh
 └──────────────┘
```

---

## Concurrency Control

The `auto-resolve` job uses a GitHub Actions `concurrency` group scoped per
branch:

```yaml
concurrency:
  group: auto-resolve-${{ github.ref }}
  cancel-in-progress: true
```

This ensures only one auto-resolve job runs per branch at a time. If a new
workflow run is triggered while an auto-resolve job is pending (queued but not
yet started), the pending one is cancelled in favor of the newer run. A job
that is already running will complete before the next one starts.

**Limitation:** GitHub Actions does not natively support "skip if already
running" semantics (where a new job would be dropped entirely without queuing).
The `cancel-in-progress` approach is the closest available mechanism. If true
skip-not-queue behavior is needed, it would require a custom pre-check step
that queries the GitHub API for in-progress workflow runs.

---

## Prompt Format

The prompt sent to the coding agent looks like:

```
The following 3 new test failure(s) were discovered on branch
"feature/my-branch" (commit abc1234):

- org.almostrealism.collect.PackedCollectionMapTest#testMapWithOffset
- org.almostrealism.model.OobleckComponentTests#testDecoderBlock
- org.almostrealism.audio.WavFileTest#testStereoOutput

For detailed stack traces and error messages, download the Surefire report
artifacts from this workflow run:
https://github.com/almostrealism/common/actions/runs/12345#artifacts

Please investigate and fix these test failures. Each entry above is
formatted as ClassName#methodName. Look at the test source code and the
classes under test to understand what is expected, then make the minimal
code change needed to make the tests pass.
```

---

## Configuration

| Secret / Variable             | Purpose                                  | Default     |
|-------------------------------|------------------------------------------|-------------|
| `FLOWTREE_CONTROLLER_HOST`    | FlowTree controller hostname             | `localhost` |
| `FLOWTREE_CONTROLLER_PORT`    | FlowTree controller port                 | `7780`      |
| Workstream ID                 | Hardcoded as `ws-pipeline`               | --          |
| `MAX_TURNS`                   | Agent turn budget for auto-resolve       | `50`        |
| `MAX_BUDGET_USD`              | Agent dollar budget for auto-resolve     | `10.0`      |

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Agent introduces new bugs while fixing | Agent works on a branch; human reviews before merge |
| Flaky tests trigger unnecessary agent runs | Future: compare failures against a known-flaky list |
| Agent runs on master | Job-level `if` condition excludes `master` |
| Controller is unreachable | Submission failure is a warning, not a workflow failure |
| Multiple concurrent auto-resolve jobs for same branch | Concurrency group with `cancel-in-progress: true` |

---

## Future Enhancements

1. **Flaky test filtering** -- maintain a list of known-flaky tests and exclude
   them from the failure list before submitting the agent job.
2. **Differential failure detection** -- compare failures against the base
   branch to only report *new* failures introduced by the PR.
3. **Agent result tracking** -- poll the FlowTree controller for job completion
   and post the result as a PR comment or check run.
4. **Structured failure context** -- instead of just class/method names, extract
   the failure message and first few lines of the stack trace into the prompt
   for richer context.
5. **True skip-not-queue concurrency** -- add a pre-check step that queries the
   GitHub API for in-progress runs before starting, enabling the job to be
   genuinely skipped rather than cancelled.
