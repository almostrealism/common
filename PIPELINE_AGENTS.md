# Pipeline Agents: GitHub Actions Integration with FlowTree Coding Agents

## Overview

This document describes an experimental GitHub Actions workflow that spawns a FlowTree coding agent job as part of the CI pipeline. The long-term vision is for the pipeline to evaluate build results against review criteria and, when met, dispatch an autonomous agent to perform code review, analysis, or follow-up work. For the initial implementation, the agent simply posts a comment on the pull request confirming that the system works end-to-end.

## Architecture

```
GitHub Actions (self-hosted ar-ci runner)
    |
    |  curl POST to FlowTreeApiEndpoint
    |
    v
FlowTreeController (port 7780)
    |
    |  Server.sendTask(factory)
    |
    v
FlowTree Agent (connected Docker container)
    |
    |  ClaudeCodeJob executes prompt
    |  Agent uses ar-github MCP tool to comment on PR
    |
    v
GitHub Pull Request (comment posted)
```

The self-hosted `ar-ci` runners and the slack controller are on the same network, so the runner can reach the controller's HTTP API directly at `http://<controller-host>:7780`.

## Design

### 1. New HTTP Endpoint: `POST /api/workstreams/{id}/submit`

The `FlowTreeApiEndpoint` currently handles status events and messages from agents, but has no endpoint for **submitting** new jobs. We add one:

**Request:**
```
POST /api/workstreams/{wsId}/submit
Content-Type: application/json

{
  "prompt": "Post a comment on PR #42 confirming that the pipeline agent process ran successfully.",
  "targetBranch": "feature/pipeline-agents",
  "baseBranch": "develop",
  "maxTurns": 30,
  "maxBudgetUsd": 5.0
}
```

**Response (success):**
```json
{
  "ok": true,
  "jobId": "a1b2c3d4-...",
  "workstreamId": "ws-pipeline"
}
```

**Response (no agents connected):**
```json
{
  "ok": false,
  "error": "No agents connected"
}
```

**Implementation:** Add a `handleSubmit` method to `FlowTreeApiEndpoint` that:

1. Parses the JSON body for `prompt` and optional overrides (`targetBranch`, `baseBranch`, `maxTurns`, `maxBudgetUsd`)
2. Looks up the `SlackWorkstream` for the given workstream ID
3. Creates a `ClaudeCodeJob.Factory` with the prompt and workstream defaults (overridden by any request-level values)
4. Delegates to the same `Server.sendTask()` path that `SlackListener.submitJob()` uses
5. Returns the job ID

This keeps the existing Slack-based submission path untouched and adds a parallel HTTP-based entry point that reuses the same job creation logic. The `FlowTreeApiEndpoint` needs a reference to the `Server` and `SlackListener` (or a shared submission interface) to create and dispatch jobs.

**Why not use ClaudeCodeClient from the runner?** The `ClaudeCodeClient` connects directly to agent FlowTree ports and bypasses the controller entirely. Using the controller's HTTP API is simpler (a single `curl` call), doesn't require Java on the runner, and lets the controller handle agent selection, MCP config injection, and status tracking.

**Why not post a Slack message to trigger the bot?** This would work but adds a Slack API dependency to the CI pipeline, requires bot tokens in the runner's environment, and is fragile (the bot mention format, channel routing, etc.). A direct HTTP POST to the controller is more reliable and explicit.

### 2. Workstream Configuration for Pipeline Jobs

A dedicated workstream entry in the controller's YAML config handles pipeline-triggered jobs:

```yaml
workstreams:
  # ... existing workstreams ...

  - channelId: "pipeline"
    channelName: "#pipeline-agents"
    workstreamId: "ws-pipeline"
    defaultBranch: null            # Overridden per-request from GH Actions
    baseBranch: "develop"
    pushToOrigin: false            # Agent should not push; it only comments
    allowedTools: "Read,Glob,Grep"
    maxTurns: 30
    maxBudgetUsd: 5.0
    env:
      GITHUB_TOKEN: "${GITHUB_TOKEN}"   # Resolved from controller environment
```

Notes:
- `channelId: "pipeline"` is a synthetic channel ID (no real Slack channel). The `SlackNotifier` will skip posting to Slack when the channel ID doesn't resolve to a real channel, but status events are still tracked internally.
- `pushToOrigin: false` because the initial use case only posts a PR comment, not code changes.
- `allowedTools` is minimal for safety. The agent only needs to read code and use the `ar-github` MCP tool (which is appended automatically).

### 3. GitHub Actions Workflow

A new workflow file `.github/workflows/pipeline-agents.yaml`:

```yaml
name: "Pipeline Agents (Experimental)"

on:
  workflow_dispatch:
    inputs:
      prompt:
        description: "Prompt to send to the coding agent"
        required: false
        default: ""
  pull_request:
    branches:
      - develop
      - master

jobs:
  agent-review:
    # Only run on self-hosted runners (same network as controller)
    runs-on: [self-hosted, linux, ar-ci]

    # Gate: only run after the build succeeds
    # For now this job is standalone; future iterations will add
    # `needs: [build]` to depend on the main build job.

    steps:
      - name: Checkout Code
        uses: actions/checkout@v4
        with:
          ref: ${{ github.event.pull_request.head.sha || github.sha }}
          fetch-depth: 0

      - name: Determine PR context
        id: pr
        run: |
          if [ "${{ github.event_name }}" = "pull_request" ]; then
            echo "number=${{ github.event.pull_request.number }}" >> "$GITHUB_OUTPUT"
            echo "branch=${{ github.head_ref }}" >> "$GITHUB_OUTPUT"
            echo "base=${{ github.base_ref }}" >> "$GITHUB_OUTPUT"
          else
            echo "number=" >> "$GITHUB_OUTPUT"
            echo "branch=${{ github.ref_name }}" >> "$GITHUB_OUTPUT"
            echo "base=develop" >> "$GITHUB_OUTPUT"
          fi

      - name: Build prompt
        id: prompt
        run: |
          if [ -n "${{ github.event.inputs.prompt }}" ]; then
            PROMPT="${{ github.event.inputs.prompt }}"
          elif [ -n "${{ steps.pr.outputs.number }}" ]; then
            PROMPT="This is an automated pipeline agent run. Write a comment on pull request #${{ steps.pr.outputs.number }} in the almostrealism/common repository confirming that the pipeline agent process executed successfully. Include the commit SHA ${{ github.event.pull_request.head.sha }} and branch name ${{ steps.pr.outputs.branch }} in your comment."
          else
            echo "skip=true" >> "$GITHUB_OUTPUT"
            exit 0
          fi
          # Write prompt to file to avoid shell escaping issues
          echo "$PROMPT" > /tmp/agent-prompt.txt
          echo "skip=false" >> "$GITHUB_OUTPUT"

      - name: Submit agent job
        if: steps.prompt.outputs.skip != 'true'
        env:
          CONTROLLER_HOST: ${{ secrets.FLOWTREE_CONTROLLER_HOST || 'localhost' }}
          CONTROLLER_PORT: ${{ secrets.FLOWTREE_CONTROLLER_PORT || '7780' }}
          WORKSTREAM_ID: "ws-pipeline"
        run: |
          PROMPT=$(cat /tmp/agent-prompt.txt)

          RESPONSE=$(curl -s -w "\n%{http_code}" \
            -X POST \
            -H "Content-Type: application/json" \
            -d "$(jq -n \
              --arg prompt "$PROMPT" \
              --arg branch "${{ steps.pr.outputs.branch }}" \
              --arg base "${{ steps.pr.outputs.base }}" \
              '{
                prompt: $prompt,
                targetBranch: $branch,
                baseBranch: $base,
                maxTurns: 30,
                maxBudgetUsd: 5.0
              }')" \
            "http://${CONTROLLER_HOST}:${CONTROLLER_PORT}/api/workstreams/${WORKSTREAM_ID}/submit")

          HTTP_CODE=$(echo "$RESPONSE" | tail -1)
          BODY=$(echo "$RESPONSE" | head -n -1)

          echo "Response ($HTTP_CODE): $BODY"

          if [ "$HTTP_CODE" != "200" ]; then
            echo "::warning::Agent job submission failed (HTTP $HTTP_CODE): $BODY"
            # Don't fail the workflow - this is experimental
            exit 0
          fi

          JOB_ID=$(echo "$BODY" | jq -r '.jobId // empty')
          echo "Agent job submitted: $JOB_ID"

      - name: Summary
        if: always()
        run: |
          echo "## Pipeline Agent" >> "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          echo "An experimental coding agent job was submitted to the FlowTree controller." >> "$GITHUB_STEP_SUMMARY"
          echo "The agent will post a comment on this PR when it completes." >> "$GITHUB_STEP_SUMMARY"
```

Key design choices:
- **`workflow_dispatch` support** allows manual testing with custom prompts.
- **`pull_request` trigger** is the production path -- runs on PRs to `develop` or `master`.
- **Non-blocking failure** -- if the controller is unreachable or no agents are connected, the workflow logs a warning but does not fail. This is experimental infrastructure and must not block merges.
- **No wait-for-completion** -- the workflow fires-and-forgets. The agent posts its PR comment asynchronously. A future iteration could poll for job completion.
- **`jq`** is used for safe JSON construction, avoiding shell injection through prompt text.

### 4. Controller-Side Changes

Summary of changes needed in the flowtree module:

| File | Change |
|------|--------|
| `FlowTreeApiEndpoint.java` | Add route for `POST /api/workstreams/{id}/submit`, add `handleSubmit()` method |
| `FlowTreeApiEndpoint.java` | Add `setServer(Server)` and `setSlackListener(SlackListener)` (or a shared submission interface) |
| `FlowTreeController.java` | Wire the server and listener references into the API endpoint during startup |

The `handleSubmit` implementation:

```java
private Response handleSubmit(IHTTPSession session, String workstreamId) {
    String body = readBody(session);
    if (body == null) {
        return errorResponse("Failed to read request body");
    }

    String prompt = extractJsonField(body, "prompt");
    if (prompt == null || prompt.isEmpty()) {
        return errorResponse("Missing required field: prompt");
    }

    SlackWorkstream workstream = notifier.getWorkstream(workstreamId);
    if (workstream == null) {
        return errorResponse("Unknown workstream: " + workstreamId);
    }

    // Delegate to listener for job creation (reuses existing logic)
    // or directly create factory + sendTask here
    // ...

    return newFixedLengthResponse(Response.Status.OK,
            "application/json",
            "{\"ok\":true,\"jobId\":\"" + jobId + "\",\"workstreamId\":\"" + workstreamId + "\"}");
}
```

The exact factoring (whether `SlackListener.submitJob()` is made public, or the submission logic is extracted into a shared helper) is an implementation detail. The key point is that the HTTP endpoint reuses the same `ClaudeCodeJob.Factory` creation and `Server.sendTask()` dispatch path.

### 5. Security Considerations

- **No authentication on the submit endpoint (initial version).** The controller is on a private network behind a firewall. Only self-hosted runners and internal services can reach it. A future iteration should add a shared secret or API key header.

- **Budget cap per job.** The workstream config enforces `maxBudgetUsd: 5.0` and `maxTurns: 30`, preventing runaway agent costs even if the endpoint is misused.
- **Minimal tool allowlist.** The agent can only read code and use `ar-github` (for PR comments). It cannot edit files, run bash commands, or push code.
- **GITHUB_TOKEN scoping.** The token used by the `ar-github` pushed tool should have `pull_requests: write` permission on the target repo, and nothing else.

### 6. Design Gap: Branch-to-Workstream Resolution

The current design uses a single hardcoded workstream ID (`ws-pipeline`) for all pipeline-triggered jobs. The branch name is passed as `targetBranch` to tell the agent which branch to check out, but it does **not** influence which workstream context (working directory, env vars, MCP tools, allowed tools, budget) the agent receives.

This means that if a Slack workstream `ws-rings` is actively configured for `feature/new-decoder` with specific env vars and MCP tools, a pipeline agent triggered by a PR on that same branch will run with the generic `ws-pipeline` context instead of the richer `ws-rings` context.

**Proposed solution: branch-to-workstream resolution.** When a pipeline job arrives, the submit endpoint should:

1. Accept an optional `workstreamId` field in the request body (in addition to the URL path parameter)
2. If no explicit workstream ID is provided in the body, search registered workstreams for one whose `defaultBranch` matches the `targetBranch`
3. Fall back to the workstream ID from the URL path (e.g., `ws-pipeline`) if no branch match is found

This keeps the current behavior as the default while allowing pipeline jobs to automatically inherit the context of an active workstream when one exists for the target branch.

**Workflow-side change:** The GitHub Actions workflow could also be enhanced to omit the workstream ID from the URL (or use a generic `/api/submit` endpoint) and let the controller resolve it from the branch. Alternatively, it could pass both `targetBranch` and let the controller decide.

**Edge cases:**
- Multiple workstreams configured for the same branch: use the first match or require explicit disambiguation
- Workstream's `defaultBranch` is null: skip it during branch matching
- Branch matching should be exact-match only (no prefix/glob matching) to avoid false positives

### 7. Future Iterations

Once the basic end-to-end flow is validated:

1. **Branch-to-workstream resolution.** Implement the branch matching described in section 6 so pipeline agents inherit workstream context automatically.
2. **Conditional triggering.** Add `needs: [build, test]` and gate the agent job on build/test outcomes. For example, only spawn the agent if tests pass but static analysis flags issues.
3. **Richer prompts.** Include build logs, test results, or Qodana findings in the prompt so the agent can perform meaningful code review.
4. **Wait-for-completion.** Poll the controller's status endpoint and include the agent's result in the workflow summary.
5. **Authentication.** Add an API key check to the submit endpoint.
6. **Agent job as a reusable workflow.** Extract the submit step into a composite action so other repos can use it.

## Implementation Plan

### Phase 1: Proof of Concept (this PR)

1. Add `POST /api/workstreams/{id}/submit` endpoint to `FlowTreeApiEndpoint`
2. Wire the endpoint to the server/listener in `FlowTreeController`
3. Add the `pipeline-agents.yaml` workflow file
4. Add a pipeline workstream entry to the example YAML config
5. Test manually via `workflow_dispatch`

### Phase 2: Production Hardening

1. Add API key authentication to the submit endpoint
2. Add `needs: [build]` dependency to the workflow
3. Add job completion polling and workflow summary output
4. Add conditional trigger logic (only run when review criteria are met)

## Testing

The proof-of-concept can be validated with these steps:

1. **Unit test the new endpoint.** Extend `SlackIntegrationTest` to test `POST /api/workstreams/{id}/submit` with a mock server, verifying that a `ClaudeCodeJob.Factory` is created with the correct prompt and settings.
2. **Manual workflow_dispatch.** Trigger the workflow manually from the GitHub Actions UI with a custom prompt and verify:
   - The curl request reaches the controller (check controller logs)
   - The job is dispatched to an agent (check FlowTree logs)
   - The agent posts a comment on the PR (check GitHub)
3. **PR trigger.** Open a test PR to `develop` and verify the workflow runs and the agent comments on the PR.
