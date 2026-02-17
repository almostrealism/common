# Auto-Resolution of Failing Tests via FlowTree Coding Agent

## Overview

This document proposes adding an automatic test-failure resolution step to the
`analysis.yaml` CI workflow. When tests fail, the pipeline will collect failure
details from Maven Surefire XML reports, build a prompt describing the failures,
and submit a FlowTree coding-agent job to attempt a fix.

---

## Current State

### Workflow structure (`analysis.yaml`)

```
build  ─> test (matrix 0-6) ─> test-ml  ─> test-music ─> analysis
                              ─> test-audio ─┘
```

- Each test job uploads **coverage data** (`jacoco.exec`) as artifacts.
- The `analysis` job runs with `if: always()` and `needs: [test-ml, test-audio, test-music]`,
  so it executes even when tests fail.
- Test jobs do **not** currently upload Surefire XML reports.

### FlowTree agent submission (`pipeline-agents.yaml`)

The experimental `pipeline-agents.yaml` workflow demonstrates how to submit a
job to the FlowTree controller via `curl`. Key parameters:

| Parameter       | Purpose                                |
|-----------------|----------------------------------------|
| `prompt`        | Natural-language task for the agent    |
| `targetBranch`  | Branch the agent should work on        |
| `baseBranch`    | Base branch for comparison             |
| `maxTurns`      | Agent turn budget                      |
| `maxBudgetUsd`  | Dollar budget cap                      |

---

## Proposed Changes

### 1. Upload Surefire reports from every test job

Each test job (`test`, `test-ml`, `test-audio`, `test-music`) needs a new step
that uploads the Surefire XML reports as artifacts. This step must use
`if: always()` so reports are captured even when tests fail.

**Add to each test job (after the test run step, before the coverage collection step):**

```yaml
- name: Upload Surefire Reports
  if: always()
  uses: actions/upload-artifact@v4
  with:
    name: surefire-<job-name>       # e.g. surefire-test-group-${{ matrix.group }}
    path: |
      **/target/surefire-reports/TEST-*.xml
    retention-days: 7
    if-no-files-found: ignore
```

For the matrix `test` job, use `name: surefire-utils-group-${{ matrix.group }}`.
For `test-ml`, use `name: surefire-ml`. For `test-audio`, use
`name: surefire-audio`. For `test-music`, use `name: surefire-music-compose`.

### 2. Add an `auto-resolve` job after `analysis`

A new job called `auto-resolve` runs after the `analysis` job. It downloads
the Surefire reports, parses them for failures, builds a prompt, and submits
a FlowTree agent job.

```yaml
auto-resolve:
  needs: [test, test-ml, test-audio, test-music, analysis]
  if: always() && github.ref != 'refs/heads/master'
  runs-on: [self-hosted, linux, ar-ci]
  permissions:
    contents: write
    pull-requests: write

  steps:
    - name: Checkout Code
      uses: actions/checkout@v4
      with:
        ref: ${{ github.event.pull_request.head.sha || github.sha }}
        fetch-depth: 0

    - name: Download Surefire Reports
      uses: actions/download-artifact@v4
      with:
        pattern: surefire-*
        path: all-surefire-reports
        merge-multiple: false

    - name: Parse test failures
      id: failures
      run: |
        # Parse all Surefire XML reports for failures and errors
        FAILURES=""
        FAILURE_COUNT=0

        for xml in $(find all-surefire-reports -name "TEST-*.xml" 2>/dev/null); do
          # Extract test class name from the XML
          CLASS=$(grep -oP 'testsuite[^>]+name="\K[^"]+' "$xml" | head -1)

          # Find failed or errored test cases
          # Surefire XML has <testcase> elements with optional <failure> or <error> children
          while IFS= read -r method; do
            if [ -n "$method" ]; then
              FAILURES="${FAILURES}- ${CLASS}#${method}\n"
              FAILURE_COUNT=$((FAILURE_COUNT + 1))
            fi
          done < <(grep -B1 '<failure\b\|<error\b' "$xml" \
                   | grep -oP 'testcase[^>]+name="\K[^"]+' || true)
        done

        echo "failure_count=$FAILURE_COUNT" >> "$GITHUB_OUTPUT"

        if [ "$FAILURE_COUNT" -gt 0 ]; then
          # Write failures to a file (avoids shell escaping issues)
          printf "$FAILURES" > /tmp/test-failures.txt
          echo "has_failures=true" >> "$GITHUB_OUTPUT"
          echo "::notice::Found $FAILURE_COUNT test failure(s) to auto-resolve"
        else
          echo "has_failures=false" >> "$GITHUB_OUTPUT"
          echo "::notice::No test failures found -- skipping auto-resolve"
        fi

    - name: Upload failure summary
      if: steps.failures.outputs.has_failures == 'true'
      uses: actions/upload-artifact@v4
      with:
        name: test-failure-summary
        path: /tmp/test-failures.txt
        retention-days: 7

    - name: Determine branch context
      if: steps.failures.outputs.has_failures == 'true'
      id: ctx
      run: |
        if [ "${{ github.event_name }}" = "pull_request" ]; then
          echo "branch=${{ github.head_ref }}" >> "$GITHUB_OUTPUT"
          echo "base=${{ github.base_ref }}" >> "$GITHUB_OUTPUT"
        else
          echo "branch=${{ github.ref_name }}" >> "$GITHUB_OUTPUT"
          echo "base=develop" >> "$GITHUB_OUTPUT"
        fi

    - name: Build prompt and submit agent job
      if: steps.failures.outputs.has_failures == 'true'
      env:
        CONTROLLER_HOST: ${{ secrets.FLOWTREE_CONTROLLER_HOST || 'localhost' }}
        CONTROLLER_PORT: ${{ secrets.FLOWTREE_CONTROLLER_PORT || '7780' }}
        WORKSTREAM_ID: "ws-pipeline"
        RUN_URL: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
      run: |
        FAILURE_LIST=$(cat /tmp/test-failures.txt)
        FAILURE_COUNT=${{ steps.failures.outputs.failure_count }}

        SUREFIRE_ARTIFACT_URL="${RUN_URL}#artifacts"

        PROMPT=$(cat <<EOF
        The following ${FAILURE_COUNT} new test failure(s) were discovered on branch \
        "${{ steps.ctx.outputs.branch }}" (commit ${{ github.sha }}):

        ${FAILURE_LIST}
        For detailed stack traces and error messages, download the Surefire report \
        artifacts from this workflow run: ${SUREFIRE_ARTIFACT_URL}

        Please investigate and fix these test failures. Each entry above is formatted \
        as ClassName#methodName. Look at the test source code and the classes under \
        test to understand what is expected, then make the minimal code change needed \
        to make the tests pass.
        EOF
        )

        echo "$PROMPT" > /tmp/agent-prompt.txt

        RESPONSE=$(curl -s -w "\n%{http_code}" \
          -X POST \
          -H "Content-Type: application/json" \
          -d "$(jq -n \
            --arg prompt "$PROMPT" \
            --arg branch "${{ steps.ctx.outputs.branch }}" \
            --arg base "${{ steps.ctx.outputs.base }}" \
            '{
              prompt: $prompt,
              targetBranch: $branch,
              baseBranch: $base,
              maxTurns: 50,
              maxBudgetUsd: 10.0
            }')" \
          "http://${CONTROLLER_HOST}:${CONTROLLER_PORT}/api/workstreams/${WORKSTREAM_ID}/submit")

        HTTP_CODE=$(echo "$RESPONSE" | tail -1)
        BODY=$(echo "$RESPONSE" | head -n -1)

        echo "Response ($HTTP_CODE): $BODY"

        if [ "$HTTP_CODE" != "200" ]; then
          echo "::warning::Auto-resolve agent job submission failed (HTTP $HTTP_CODE): $BODY"
          exit 0
        fi

        JOB_ID=$(echo "$BODY" | jq -r '.jobId // empty')
        echo "Auto-resolve agent job submitted: $JOB_ID"

    - name: Summary
      if: always()
      run: |
        echo "## Auto-Resolve" >> "$GITHUB_STEP_SUMMARY"
        echo "" >> "$GITHUB_STEP_SUMMARY"
        if [ "${{ steps.failures.outputs.has_failures }}" = "true" ]; then
          echo "Found **${{ steps.failures.outputs.failure_count }}** test failure(s)." >> "$GITHUB_STEP_SUMMARY"
          echo "A FlowTree coding agent job was submitted to attempt automatic resolution." >> "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          echo "### Failing tests" >> "$GITHUB_STEP_SUMMARY"
          echo '```' >> "$GITHUB_STEP_SUMMARY"
          cat /tmp/test-failures.txt >> "$GITHUB_STEP_SUMMARY"
          echo '```' >> "$GITHUB_STEP_SUMMARY"
        else
          echo "No test failures detected. Auto-resolve skipped." >> "$GITHUB_STEP_SUMMARY"
        fi
```

### 3. Skip when there are no failures

The job is naturally skipped when no failures exist because:
- The `Parse test failures` step sets `has_failures=false` when no failures are found.
- The `Build prompt and submit agent job` step has
  `if: steps.failures.outputs.has_failures == 'true'`, so it only runs when
  there are actual failures.
- The job also skips for `master` branch pushes via the job-level `if` condition.

### 4. Guard against runs on the master branch

The job-level condition `if: always() && github.ref != 'refs/heads/master'`
prevents auto-resolve from running on `master`. We only want this on feature
branches where the agent can safely push fixes.

---

## How Information Flows

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
 │ 2. parse XML  │──> list of ClassName#methodName
 │ 3. build      │──> prompt with failure list + artifact URL
 │ 4. submit     │──> FlowTree controller
 └──────────────┘
```

---

## Prompt Format

The prompt sent to the coding agent will look like:

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

## Artifact URL for Detailed Reports

The Surefire XML reports are uploaded as artifacts with names matching
`surefire-*`. The prompt includes a link to the workflow run's artifacts
section:

```
${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}#artifacts
```

The agent (or a human) can download these artifacts to get full stack traces,
error messages, and timing information from the Surefire XML files.

---

## Configuration

| Secret / Variable             | Purpose                                  | Default     |
|-------------------------------|------------------------------------------|-------------|
| `FLOWTREE_CONTROLLER_HOST`    | FlowTree controller hostname             | `localhost` |
| `FLOWTREE_CONTROLLER_PORT`    | FlowTree controller port                 | `7780`      |
| Workstream ID                 | Hardcoded as `ws-pipeline`               | --          |
| `maxTurns`                    | Agent turn budget for auto-resolve       | `50`        |
| `maxBudgetUsd`                | Agent dollar budget for auto-resolve     | `10.0`      |

The budget for auto-resolve jobs is set higher than the experimental
`pipeline-agents.yaml` (50 turns / $10 vs 30 turns / $5) because fixing test
failures typically requires more investigation and iteration.

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Agent introduces new bugs while fixing | Agent works on a branch; human reviews before merge |
| Flaky tests trigger unnecessary agent runs | Future: compare failures against a known-flaky list |
| Agent runs on master | Job-level `if` condition excludes `master` |
| Controller is unreachable | Submission failure is a warning, not a workflow failure |
| Multiple concurrent auto-resolve jobs for same branch | Future: check for in-flight jobs before submitting |

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
5. **Deduplication** -- check if an auto-resolve job is already in-flight for
   this branch before submitting another.
