# AR CI Tools

Scripts used by the GitHub Actions CI pipeline (`.github/workflows/analysis.yaml`)
to build prompts, parse test results, and submit agent jobs to the FlowTree controller.

## Directory Structure

| Directory | Purpose |
|---|---|
| `agent-protection/` | Anti-deception enforcement scripts (test write locks, audit) |
| `docker/` | Linux CPU runner fleet (`ar-ci`), Docker Compose |
| `macos/` | macOS GPU runner configuration (`ar-ci`) |
| `rocm/` | AMD/ROCm OpenCL runner fleet (`ar-ci-cl`), Docker Compose |
| `monitor/` | Host monitoring tools for CI infrastructure |
| `prompts/` | Prompt builders and templates for agent jobs |

## Root Scripts

| Script | Purpose |
|---|---|
| `parse-surefire-failures.sh` | Extract failing tests from Surefire XML reports |
| `register-workstream.sh` | Register a workstream with the FlowTree controller |
| `submit-agent-job.sh` | Submit an agent job to the FlowTree controller, creating the workstream for the repository and branch when none is registered |
| `sync-music-samples.sh` | Seed the curated audio sample library onto a runner (any fleet) |

## Agent Protection (`agent-protection/`)

| Script | Purpose |
|---|---|
| `check-quality-gates.sh` | Evaluate quality gate pass/fail from job outputs |
| `deception-audit.sh` | Cross-session deception pattern detection |
| `detect-test-hiding.sh` | Detect modifications to base-branch tests that hide failures |
| `validate-agent-commit.sh` | Block agent commits that modify base-branch tests or CI files |
| `verify-exfiltration-guard.sh` | Fail CI if the exfiltration guard hook (`.claude/hooks/block-exfiltration.sh`, its core, tests, allowlist) is missing from HEAD, not registered for `Artifact`/`SendUserFile`/`Bash`, or modified on a PR branch — see `docs/plans/EXFILTRATION_GUARD_HOOK.md` |
| `test-verify-exfiltration-guard.sh` | Regression tests for the above |
| `verify-memory-claim.sh` | Cross-reference "no changes needed" claims against git diff |

## Prompts (`prompts/`)

| Script/Template | Purpose |
|---|---|
| `build-build-failure-prompt.sh` | Build prompt for agent when compilation fails |
| `build-planning-prompt.sh` | Build prompt for planning workflow |
| `build-policy-violation-prompt.sh` | Build prompt for agent when code policy enforcement fails |
| `build-quality-gate-prompt.sh` | Build prompt for agent when quality gates fail |
| `build-resolve-prompt.sh` | Build prompt for agent when tests fail |
| `build-review-prompt.sh` | Build prompt for general code review |
| `build-verify-prompt.sh` | Build prompt for verify-completion workflow |
| `general-review.txt` | Template for general code review prompt |
| `verify-completion.txt` | Template for verify-completion prompt |
