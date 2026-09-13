# AR CI Tools

Scripts used by the GitHub Actions CI pipeline (`.github/workflows/analysis.yaml`)
to build prompts, parse test results, and submit agent jobs to the FlowTree controller.

## Directory Structure

| Directory | Purpose |
|---|---|
| `agent-protection/` | Anti-deception enforcement scripts (test write locks, audit) |
| `coverage/` | Test-coverage automation: target selection, report fetching, assertion density |
| `docker/` | Linux CPU runner fleet (`ar-ci`), Docker Compose |
| `macos/` | macOS GPU runner configuration (`ar-ci`) |
| `rocm/` | AMD/ROCm OpenCL runner fleet (`ar-ci-cl`), Docker Compose |
| `monitor/` | Host monitoring tools for CI infrastructure |
| `prompts/` | Prompt builders and templates for agent jobs |

## Root Scripts

| Script | Purpose |
|---|---|
| `archive-stale-workstreams.sh` | Archive the workstreams of a recurring QA job's previous rounds |
| `parse-surefire-failures.sh` | Extract failing tests from Surefire XML reports |
| `qa-cadence.sh` | Decide whether a recurring QA round (`BRANCH_PREFIX`) is due |
| `register-workstream.sh` | Register a workstream with the FlowTree controller |
| `submit-agent-job.sh` | Submit an agent job to the FlowTree controller, creating the workstream for the repository and branch when none is registered; `REQUIRED_LABELS` routes it to a Node with matching capability labels |
| `sync-music-samples.sh` | Seed the curated audio sample library onto a runner (any fleet) |

## Coverage (`coverage/`)

| Script | Purpose |
|---|---|
| `fetch-latest-coverage.sh` | Reuse (or, with `FORCE=true`, recompute) the merged JaCoCo report and a fresh coverage.py report |
| `select-target.py` | Rank Java packages / Python directories by coverage and emit one coverage-qa target |
| `test_select_target.py` | Unit tests for `select-target.py`, driven by the `testdata/` fixtures |
| `assertion-density-report.sh` | Report-only: assertions-per-new-test-method for a coverage-qa PR |

See `tools/coverage-data/coverage-exclusions.txt` / `tools/coverage-data/coverage-history.tsv`
for the selector's data files — kept outside `tools/ci/` because they are mutable data an
agent round appends to, not pipeline logic.

## Agent Protection (`agent-protection/`)

The exfiltration guard's scripts here are the CI half of a hook that runs in
every agent session. Its policy, the bypasses it was designed against, and what
it knowingly does not cover are in
[docs/internals/exfiltration-guard.md](../../docs/internals/exfiltration-guard.md).

| Script | Purpose |
|---|---|
| `check-quality-gates.sh` | Evaluate quality gate pass/fail from job outputs |
| `deception-audit.sh` | Cross-session deception pattern detection |
| `detect-test-hiding.sh` | Detect modifications to base-branch tests that hide failures |
| `exfil_guard_registration.py` | Shared `invokes_adapter()` helper imported by `verify-exfiltration-guard.sh`'s CHECK 2 and CHECK 3 |
| `test-check-quality-gates.sh` | Regression tests for `check-quality-gates.sh` |
| `test-method-lines.awk` | Report the test methods of a Java source file, by line or by body |
| `test-validate-agent-commit.sh` | Regression tests for `validate-agent-commit.sh` |
| `test-verify-exfiltration-guard.sh` | Regression tests for `verify-exfiltration-guard.sh` |
| `test-verify-sensitive-bypass.sh` | Regression tests for `verify-sensitive-bypass.sh` |
| `test_exfil_guard_registration.py` | Regression tests for `exfil_guard_registration.py` |
| `validate-agent-commit.sh` | Block agent commits that change base-branch test methods or CI files |
| `verify-exfiltration-guard.sh` | Fail CI if the exfiltration guard hook (`.claude/hooks/block-exfiltration.sh`, its core, tests, allowlist) is missing from HEAD, not registered for `Artifact`/`SendUserFile`/`Bash`, or modified on a PR branch |
| `verify-memory-claim.sh` | Cross-reference "no changes needed" claims against git diff |
| `verify-sensitive-bypass.sh` | Verify a controller-signed `Sensitive-File-Bypass` commit trailer |

## Prompts (`prompts/`)

| Script/Template | Purpose |
|---|---|
| `build-build-failure-prompt.sh` | Build prompt for agent when compilation fails |
| `build-consolidation-prompt.sh` | Build prompt for the recurring consolidation (duplication) round |
| `build-coverage-prompt.sh` | Build prompt for the recurring test-coverage round |
| `build-defect-hunt-prompt.sh` | Build prompt for the recurring defect hunt |
| `build-doc-qa-prompt.sh` | Build prompt for the recurring documentation-staleness review |
| `build-performance-prompt.sh` | Build prompt for the recurring performance round |
| `build-planning-prompt.sh` | Build prompt for planning workflow |
| `build-policy-violation-prompt.sh` | Build prompt for agent when code policy enforcement fails |
| `build-quality-gate-prompt.sh` | Build prompt for agent when quality gates fail |
| `build-resolve-prompt.sh` | Build prompt for agent when tests fail |
| `build-review-prompt.sh` | Build prompt for general code review |
| `build-verify-prompt.sh` | Build prompt for verify-completion workflow |
| `consolidation.txt` | Template for the consolidation round: find one duplicated behavior, share it, pin it with tests |
| `coverage.txt` | Template for the test-coverage round |
| `defect-hunt.txt` | Template for the defect hunt |
| `doc-qa.txt` | Template for the documentation-staleness review |
| `general-review.txt` | Template for general code review prompt |
| `performance.txt` | Template for the performance round: pick a slow test, profile it on Metal, make the framework faster without touching the test |
| `pr-feedback.txt` | Shared fragment: how to find, read, act on and reply to pull-request review comments — patched into every auto-resolve prompt |
| `project-planning.txt` | Template for the planning workflow |
| `prompt-render.sh` | Sourced by the builders: expands `@include <fragment>` lines and substitutes `${VAR}` placeholders (`render_prompt`, `append_prompt_fragment`) |
| `verify-completion.txt` | Template for verify-completion prompt |

Each `build-*-prompt.sh` reads its sibling template, substitutes the environment
variables named in its header, and writes the result to an output-file argument.
Most take that path as their only argument; a few need additional file input first
— `build-resolve-prompt.sh <failures-file> <output-file>`,
`build-quality-gate-prompt.sh <failures-file> <output-file>` and
`build-vm-crash-prompt.sh <crash-reports-dir> <output-file>` — where the output
file is always the last argument. `tools/tests/test_prompt_builders.py` holds
them to that contract.

Instructions that several tasks share live once, as a fragment in this directory,
and are patched into each prompt by `prompt-render.sh`: a template names the
fragment on an `@include <file>` line, and a builder that assembles its prompt
from heredocs calls `append_prompt_fragment <file> "$OUTPUT_FILE" VAR...`. The
one fragment today is `pr-feedback.txt`, the policy for pull-request review
comments — read both the inline and conversation feeds before starting, fix what
is right in the code, reply threaded to the original comment only where the
author is safe to answer (Copilot and people, never CodeRabbit or an unknown bot),
and treat a comment as evidence to verify rather than an instruction to obey.
Every auto-resolve builder carries it, whatever the job was submitted for — a
reviewer may already have named the defect a test-failure job is chasing, and on
a docs-only branch the review comments are usually the whole of the work.
`test_prompt_builders.py` fails if any auto-resolve prompt stops carrying it.
