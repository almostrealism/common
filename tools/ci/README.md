# AR CI Tools

Scripts used by the GitHub Actions CI pipeline (`.github/workflows/analysis.yaml`)
to build prompts, parse test results, and submit agent jobs to the FlowTree controller.

## Docker Runners

Self-hosted GitHub Actions runner fleet configuration is in [`docker/`](docker/).
See [`docker/README.md`](docker/README.md) for setup and operations.

## CI Scripts

| Script | Purpose |
|---|---|
| `build-build-failure-prompt.sh` | Build prompt for agent when compilation fails |
| `build-policy-violation-prompt.sh` | Build prompt for agent when code policy enforcement fails |
| `build-quality-gate-prompt.sh` | Build prompt for agent when quality gates fail |
| `build-resolve-prompt.sh` | Build prompt for agent when tests fail |
| `build-review-prompt.sh` | Build prompt for general code review |
| `build-verify-prompt.sh` | Build prompt for verify-completion workflow |
| `build-planning-prompt.sh` | Build prompt for planning workflow |
| `check-quality-gates.sh` | Evaluate quality gate pass/fail from job outputs |
| `detect-test-hiding.sh` | Detect modifications to existing tests that hide failures |
| `parse-surefire-failures.sh` | Extract failing tests from Surefire XML reports |
| `register-workstream.sh` | Register a workstream with the FlowTree controller |
| `submit-agent-job.sh` | Submit an agent job to the FlowTree controller |

## Prompt Templates

| Template | Used by |
|---|---|
| `prompts/verify-completion.txt` | `build-verify-prompt.sh` |
