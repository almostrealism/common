# AR CI Tools

Scripts used by the GitHub Actions CI pipeline (`.github/workflows/analysis.yaml`)
to build prompts, parse test results, and submit agent jobs to the FlowTree controller.

## Directory Structure

| Directory | Purpose |
|---|---|
| `agent-protection/` | Anti-deception enforcement scripts (test write locks, audit, circuit breaker) |
| `docker/` | Self-hosted GitHub Actions runner fleet configuration |
| `macos/` | macOS self-hosted runner configuration |
| `monitor/` | Host monitoring tools for CI infrastructure |
| `prompts/` | Prompt builders and templates for agent jobs |

## Root Scripts

| Script | Purpose |
|---|---|
| `parse-surefire-failures.sh` | Extract failing tests from Surefire XML reports |
| `register-workstream.sh` | Register a workstream with the FlowTree controller |
| `submit-agent-job.sh` | Submit an agent job to the FlowTree controller |

## Agent Protection (`agent-protection/`)

| Script | Purpose |
|---|---|
| `check-quality-gates.sh` | Evaluate quality gate pass/fail from job outputs |
| `deception-audit.sh` | Cross-session deception pattern detection |
| `detect-test-hiding.sh` | Detect modifications to base-branch tests that hide failures |
| `escalation-tracker.sh` | Circuit breaker for repeated auto-resolve dispatch |
| `validate-agent-commit.sh` | Block agent commits that modify base-branch tests or CI files |
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
