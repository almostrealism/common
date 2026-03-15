# DEVTOOLS_QA — Development Tools and CI/CD Infrastructure Improvements

This plan covers five improvements to the Almost Realism development tooling,
CI pipeline, and FlowTree controller infrastructure.

---

## 1. Skip CI Tests for Docs-Only PRs

### Current Behavior

The GitHub Actions pipeline (`.github/workflows/analysis.yaml`) runs the full
test suite on every pull request to `master`, regardless of which files were
changed. A PR that only modifies Markdown files under `docs/` still triggers
7 Linux test groups, 3 macOS test groups, code policy checks, and all
validation gates. This wastes CI compute (hours of self-hosted runner time)
and delays agent feedback loops for documentation-only work.

The auto-resolve job already detects docs-only changes *after* all tests pass
(line ~1225, `steps.docs-check`), but by that point the entire test suite has
already run.

### Files to Modify

- `.github/workflows/analysis.yaml`

### Implementation Steps

1. **Add a `changes` job** at the top of the workflow, before `build`, that
   uses `dorny/paths-filter@v3` (or raw `git diff`) to classify the changed
   files:

   ```yaml
   changes:
     runs-on: ubuntu-latest
     outputs:
       code_changed: ${{ steps.filter.outputs.code }}
     steps:
       - uses: actions/checkout@v4
         with:
           fetch-depth: 0
       - id: filter
         run: |
           # Compare against base branch for PRs, parent for pushes
           if [ "${{ github.event_name }}" = "pull_request" ]; then
             BASE="${{ github.event.pull_request.base.sha }}"
           else
             BASE="${{ github.event.before }}"
           fi
           CODE_FILES=$(git diff --name-only "$BASE" HEAD -- \
             '*.java' '*.xml' '*.yaml' '*.yml' '*.sh' '*.py' '*.groovy' \
             | grep -v '^docs/' | grep -v '\.md$' || true)
           if [ -z "$CODE_FILES" ]; then
             echo "code=false" >> "$GITHUB_OUTPUT"
           else
             echo "code=true" >> "$GITHUB_OUTPUT"
           fi
   ```

2. **Gate the `build` job** on `changes.outputs.code_changed == 'true'` (or
   always run on `push` to master). All downstream jobs already depend on
   `build`, so they will be skipped transitively.

3. **Keep the auto-resolve job running** for docs-only PRs by adjusting its
   `needs` condition to use `if: always()` or `!cancelled()` and check for
   the docs-only case explicitly. This preserves the existing docs-only
   verify path (line ~1240) that submits a verify agent.

4. **Preserve workflow_dispatch behavior**: Manual runs should always execute
   the full pipeline regardless of file changes.

### Risks and Considerations

- The `changes` job must handle the initial PR commit where `github.event.before`
  may be `0000000`. Use `github.event.pull_request.base.sha` for PRs.
- If a PR starts as docs-only but later adds code, re-running the pipeline must
  re-evaluate. The filter runs on every push, so this is handled naturally.
- The `dorny/paths-filter` action is widely used but adds a third-party
  dependency. A raw `git diff` approach avoids this.

---

## 2. Remove workstream_id Requirement from project_create_branch

### Current Behavior

The `project_create_branch` MCP tool (`tools/mcp/manager/server.py:1062-1150`)
requires a `workstream_id` parameter. It uses the resolved workstream's
`repoUrl` and `baseBranch` to dispatch the `project-manager.yaml` workflow.

This is semantically wrong: `project_create_branch` creates *new* workstreams
and planning branches. Requiring an existing workstream creates a chicken-and-egg
problem — you need a workstream to create a workstream. In practice, callers
pass the master workstream ID as a proxy to get the repo URL, but this is
fragile and confusing.

### Files to Modify

- `tools/mcp/manager/server.py` — `project_create_branch` function (lines 1062-1150)

### Implementation Steps

1. **Add optional `repo_url` parameter** to `project_create_branch`:

   ```python
   def project_create_branch(
       workstream_id: str = "",   # Now optional
       repo_url: str = "",        # New: direct repo URL
       plan_title: str = "",
       plan_content: str = "",
   ) -> dict:
   ```

2. **Implement resolution logic** (repo URL can come from three sources):

   ```python
   effective_repo = None
   effective_base = "master"

   if repo_url:
       effective_repo = repo_url
   elif workstream_id:
       ws = _find_workstream(workstream_id)
       if ws:
           effective_repo = ws.get("repoUrl")
           effective_base = ws.get("baseBranch", "master")

   if not effective_repo:
       # Default: almostrealism/common on master
       effective_repo = "https://github.com/almostrealism/common"
   ```

3. **Extract owner/repo from the resolved URL** and dispatch the workflow as
   before, using `effective_base` as the ref.

4. **Update the MCP tool description** to reflect that `workstream_id` is
   optional and `repo_url` can be provided directly.

5. **Update the duplicate registration** in the `claude_ai_ar-manager` server
   (if it has a separate copy of this tool — check `server.py` for a second
   registration block).

### Risks and Considerations

- The MCP tool schema registers parameters as required/optional. Changing
  `workstream_id` from required to optional is a non-breaking change for
  callers — existing calls with `workstream_id` continue to work.
- The default repo URL (`almostrealism/common`) should be the SSH URL
  (`git@github.com:almostrealism/common.git`) or HTTPS URL
  (`https://github.com/almostrealism/common`) depending on what
  `_extract_owner_repo()` expects. Check both formats are handled.

---

## 3. Add Checkstyle to GitHub Actions Pipeline

### Current Behavior

Checkstyle is configured in the Maven build (`pom.xml` lines ~73-97) with
`maven-checkstyle-plugin 3.3.1` bound to the `validate` phase. The main
`checkstyle.xml` enforces two rules:

1. No `var` keyword usage
2. No `@SuppressWarnings` annotations

A separate `checkstyle-javadoc.xml` (activated via `-Pjavadoc-check` profile)
enforces Javadoc on public methods >100 lines.

**The problem**: The CI pipeline runs `mvn install -DskipTests` in the `build`
job, which *does* execute the `validate` phase and therefore runs checkstyle.
However, if checkstyle is failing, the build job fails with a generic "build
failure" and the auto-resolve job submits a "build failure" prompt — not a
checkstyle-specific prompt. There is no dedicated checkstyle step or check in
the pipeline, so:

- Failures are not surfaced as checkstyle violations in the PR check summary
- The auto-resolve agent gets a build-failure prompt instead of a targeted
  checkstyle prompt
- There is no separate reporting of style violations vs. compilation errors

The `javadoc-check` job exists (line ~780 in analysis.yaml) but runs
`mvn checkstyle:check -Pjavadoc-check` only for Javadoc rules, and uses
`continue-on-error: true` so it never blocks.

### Files to Modify

- `.github/workflows/analysis.yaml`

### Implementation Steps

1. **Add a `checkstyle` job** that runs after `build` and before tests:

   ```yaml
   checkstyle:
     needs: build
     runs-on: ubuntu-latest
     outputs:
       passed: ${{ steps.check.outcome == 'success' }}
     steps:
       - name: Checkout Code
         uses: actions/checkout@v4
       - name: Set up JDK
         uses: actions/setup-java@v4
         with:
           distribution: 'temurin'
           java-version: '21'
       - name: Run Checkstyle
         id: check
         continue-on-error: true
         run: |
           mvn checkstyle:check -B 2>&1 | tee /tmp/checkstyle-output.txt
           exit ${PIPESTATUS[0]}
       - name: Summary
         if: failure()
         run: |
           echo "## Checkstyle Violations" >> "$GITHUB_STEP_SUMMARY"
           grep -E '\[ERROR\]' /tmp/checkstyle-output.txt >> "$GITHUB_STEP_SUMMARY" || true
   ```

2. **Add checkstyle to the auto-resolve quality gate checks**: In the
   auto-resolve job's "quality gate" step (~line 1177), add the checkstyle
   job result to the quality gate evaluation. When checkstyle fails, the
   quality-gate prompt should include the specific violations.

3. **Decide on blocking behavior**: Initially, make checkstyle non-blocking
   (`continue-on-error: true`) to avoid breaking existing branches. After
   existing violations are cleaned up, switch to blocking.

4. **Avoid double-running**: Since checkstyle runs in the `validate` phase
   during `mvn install`, the build job already catches violations. The
   dedicated checkstyle job provides *clearer reporting*. To avoid running
   it twice, either:
   - Skip checkstyle in the build job: `mvn install -DskipTests -Dcheckstyle.skip=true`
   - Or accept the double-run (it's fast, ~10s)

### Risks and Considerations

- If there are existing checkstyle violations on master, a blocking checkstyle
  job would fail immediately on all PRs. Run `mvn checkstyle:check` on master
  first to verify.
- The checkstyle plugin is already in `pom.xml`, so no dependency changes are
  needed.
- Since `checkstyle.xml` is intentionally minimal (2 rules), the risk of
  false positives is low.

---

## 4. Include Commit List in Branch Context

### Current Behavior

The `memory_branch_context` MCP tool (`tools/mcp/manager/server.py:1682-1772`)
returns only stored memories for a branch. It does not include the git commit
history, which is arguably the most important branch context: it shows what
code changes have been made, by whom, and in what order.

The `branch_catchup` tool in `ar-consultant` does include commits (via
`git log`), but `memory_branch_context` is used independently by the manager
MCP server and external systems that don't have access to a local git clone.

### Files to Modify

- `tools/mcp/manager/server.py` — `memory_branch_context` function (lines 1682-1772)
- `tools/mcp/manager/server.py` — may need a new helper to fetch commits via
  the GitHub API

### Implementation Steps

1. **Add an `include_commits` parameter** (default `True`) to
   `memory_branch_context`:

   ```python
   def memory_branch_context(
       workstream_id: str = "",
       repo_url: str = "",
       branch: str = "",
       namespace: str = "default",
       limit: int = 20,
       include_messages: bool = True,
       include_commits: bool = True,   # New parameter
       commit_limit: int = 30,         # New parameter
   ) -> dict:
   ```

2. **Fetch commits via GitHub API** when `include_commits=True` and a repo URL
   is available. Use the existing `_github_request` helper:

   ```python
   if include_commits and effective_repo:
       owner_repo = _extract_owner_repo(effective_repo)
       if owner_repo:
           owner, repo = owner_repo
           # Get commits on branch relative to base
           ws = _find_workstream(workstream_id) if workstream_id else None
           base = ws.get("baseBranch", "master") if ws else "master"

           # GitHub compare API returns commits between base and branch
           compare = _github_request(
               "GET",
               f"/repos/{owner}/{repo}/compare/{base}...{effective_branch}",
           )
           if compare.get("ok") is not False:
               commits = []
               for c in compare.get("commits", [])[:commit_limit]:
                   commits.append({
                       "sha": c.get("sha", "")[:10],
                       "author": c.get("commit", {}).get("author", {}).get("name", ""),
                       "date": c.get("commit", {}).get("author", {}).get("date", ""),
                       "message": c.get("commit", {}).get("message", "").split("\n")[0],
                   })
   ```

3. **Include the commits in the response**:

   ```python
   result = {
       "ok": True,
       "repo_url": effective_repo,
       "branch": effective_branch,
       "memories": memories,
       "count": len(memories),
   }
   if commits is not None:
       result["commits"] = commits
       result["commit_count"] = len(commits)
   ```

4. **Handle the case where GitHub API is unavailable** (no token, private
   repo, rate limit): return memories without commits and include a note
   in `next_steps`.

5. **Update the duplicate tool registration** if the tool is registered in
   multiple MCP server configurations.

### Risks and Considerations

- The GitHub Compare API has a limit of 250 commits. For branches with very
  long histories, the response will be truncated. The `commit_limit` parameter
  (default 30) mitigates this.
- GitHub API rate limits apply. The `_github_request` helper should already
  handle authentication via tokens.
- The `_github_request` helper routes through the FlowTree controller's
  GitHub proxy (`/api/github/proxy`). Verify it supports the compare endpoint.
- Adding commits increases the response payload. Keep `commit_limit`
  reasonable (30 is fine for branch context).

---

## 5. Prevent Job Collisions via Timestamp Guard

### Current Behavior

The CI pipeline runs tests for 2-4 hours, then the auto-resolve job submits
an agent task to fix failures. During those hours, a user (or another
pipeline run) may have already submitted a job on the same workstream. When
the auto-resolve job's submission arrives, it creates a *second* concurrent
job that operates on stale context — it doesn't know about the work already
in progress.

The existing concurrency guard in `analysis.yaml` (line ~816,
`group: auto-resolve-${{ github.ref }}`) only prevents duplicate *workflow*
runs; it doesn't prevent the agent job from colliding with manually-submitted
jobs on the same workstream.

### Files to Modify

- `flowtree/src/main/java/io/flowtree/slack/FlowTreeApiEndpoint.java` —
  `handleSubmit` method (lines 514-710)
- `flowtree/src/main/java/io/flowtree/slack/SlackNotifier.java` — job
  tracking (needs method to check recent job timestamps)
- `tools/ci/submit-agent-job.sh` — pass `started_after` timestamp
- `.github/workflows/analysis.yaml` — record pipeline start time, pass it
  to submit script
- `tools/mcp/manager/server.py` — `workstream_submit_task` function
  (lines 826-908) — pass through `started_after`

### Implementation Steps

#### Controller Side (Java)

1. **Add `started_after` field parsing** in `handleSubmit`:

   ```java
   String startedAfterStr = extractJsonField(body, "startedAfter");
   ```

2. **Implement the collision check** in `FlowTreeApiEndpoint` or
   `SlackNotifier`. The controller already tracks job submissions via
   `JobCompletionEvent.started()` (line 696). Add a method to check
   if any job on the workstream was started after a given timestamp:

   ```java
   if (startedAfterStr != null && !startedAfterStr.isEmpty()) {
       long startedAfter = Long.parseLong(startedAfterStr);
       if (notifier.hasJobStartedAfter(workstreamId, startedAfter)) {
           String json = "{\"ok\":true,\"skipped\":true,"
               + "\"reason\":\"Newer job exists on this workstream\"}";
           return newFixedLengthResponse(Response.Status.OK,
                   "application/json", json);
       }
   }
   ```

3. **Track job start timestamps** in `SlackNotifier`. Add a map:

   ```java
   // workstreamId -> most recent job start epoch millis
   private final Map<String, Long> lastJobStartTime = new ConcurrentHashMap<>();
   ```

   Update in `onJobSubmitted()`:

   ```java
   lastJobStartTime.put(workstreamId, System.currentTimeMillis());
   ```

   Query method:

   ```java
   public boolean hasJobStartedAfter(String workstreamId, long epochMillis) {
       Long last = lastJobStartTime.get(workstreamId);
       return last != null && last > epochMillis;
   }
   ```

#### CI Pipeline Side (Shell/YAML)

4. **Record the pipeline start time** in `analysis.yaml`. Add an output to
   the first job or use a top-level step:

   ```yaml
   - name: Record start time
     id: timing
     run: echo "started_at=$(date +%s000)" >> "$GITHUB_OUTPUT"
   ```

5. **Pass `started_after` to submit-agent-job.sh** via environment variable:

   ```yaml
   - name: Submit agent job (test failures)
     env:
       STARTED_AFTER: ${{ steps.timing.outputs.started_at }}
       # ... existing env vars
   ```

6. **Update `submit-agent-job.sh`** to include `startedAfter` in the payload:

   ```bash
   if [ -n "${STARTED_AFTER:-}" ]; then
       PAYLOAD=$(echo "$PAYLOAD" | jq --arg t "$STARTED_AFTER" '. + {startedAfter: $t}')
   fi
   ```

#### MCP Tool Side (Python)

7. **Add `started_after` parameter** to `workstream_submit_task`:

   ```python
   def workstream_submit_task(
       prompt: str,
       # ... existing params
       started_after: str = "",  # epoch millis; skip if newer job exists
   ) -> dict:
   ```

   Pass through to the controller payload:

   ```python
   if started_after:
       payload["startedAfter"] = started_after
   ```

### Risks and Considerations

- **Epoch precision**: Use milliseconds (Java `System.currentTimeMillis()`)
  consistently. The shell `date +%s000` produces milliseconds.
- **In-memory tracking**: The `lastJobStartTime` map is in-memory and lost on
  controller restart. This is acceptable — a controller restart resets the
  guard, which is safe (no false positives, only missed guards).
- **Returning `ok: true` with `skipped: true`**: The submit script should
  treat this as success (exit 0). Log it as a notice, not an error.
- **Race window**: There's a small window between checking the timestamp and
  actually submitting the job. This is acceptable — the guard eliminates the
  multi-hour race, not sub-second races.
- **The response must be distinguishable from a real submission** so the CI
  pipeline can log that it was skipped without treating it as an error.

---

## Suggested Implementation Order

The items are largely independent, but a logical order considering
dependencies and risk:

| Priority | Item | Rationale |
|----------|------|-----------|
| 1 | **Skip CI for docs-only PRs** | Pure YAML change, no controller code, immediate CI time savings |
| 2 | **Add checkstyle to CI** | Pure YAML change, surfaces existing enforcement more clearly |
| 3 | **Remove workstream_id from project_create_branch** | Python-only change, low risk, improves UX immediately |
| 4 | **Include commits in branch context** | Python change with GitHub API integration, moderate complexity |
| 5 | **Timestamp guard for job collisions** | Cross-cutting (Java + shell + YAML + Python), highest complexity |

Items 1-2 are CI-only changes that can be merged together. Items 3-4 are
MCP tool changes that can be merged together. Item 5 is a cross-cutting
change that should be implemented and tested independently.

---

## 6. Default Slack Channel Fallback

### Current Behavior

When the controller publishes a Slack message for a workstream, it uses the
workstream's configured `channelId`. If the workstream has no channel
configured (e.g., it was registered via the API without a channel), or the
channel no longer exists, the message is silently dropped.

### Implementation

Add a global `defaultChannel` field to the YAML configuration
(`WorkstreamConfig`). When a message cannot be delivered to the workstream's
configured channel (because it is null, empty, or the Slack API returns an
error), the `SlackNotifier` falls back to this default channel.

### Files Modified

- `flowtree/.../WorkstreamConfig.java` — Added `defaultChannel` field
- `flowtree/.../SlackNotifier.java` — Added `defaultChannelId` field,
  `resolveChannel()` and `postToFallbackChannel()` helper methods,
  fallback logic in `postMessage()` and `postMessageInThread()`
- `flowtree/.../FlowTreeController.java` — Wired `defaultChannel` from
  config to notifier during `loadConfig()`
- `flowtree/src/main/resources/workstreams-example.yaml` — Documented
  the new `defaultChannel` config option

### Behavior

1. If `channelId` is null/empty, use `defaultChannelId` instead
2. If posting to `channelId` fails (Slack API error), retry on
   `defaultChannelId` (if different from the failed channel)
3. If `defaultChannelId` is also not configured, messages are dropped
   (same as before — purely additive)

---

## 7. Shorten Auto-Generated Slack Channel Names

### Current Behavior

When `register-workstream.sh` generates a Slack channel name from a branch
name, it converts the full branch name:
- `feature/xyz` → `w-feature-xyz`
- `project/plan-20260308` → `w-project-plan-20260308`

This produces unnecessarily long channel names since the prefix before the
slash (e.g., `feature/`, `project/`) is redundant — it's a git convention,
not meaningful context for a Slack channel.

### Implementation

Updated `register-workstream.sh` to strip the prefix before the first
slash, so only the meaningful suffix is used:
- `feature/xyz` → `w-xyz`
- `project/plan-20260308` → `w-plan-20260308`
- `some-branch` (no slash) → `w-some-branch` (unchanged)

Added explicit enforcement of the Slack 80-character channel name limit.

### Files Modified

- `tools/ci/register-workstream.sh` — Updated channel name derivation logic

---

## Summary of Changes by File

| File | Items |
|------|-------|
| `.github/workflows/analysis.yaml` | 1, 3, 5 |
| `tools/mcp/manager/server.py` | 2, 4, 5 |
| `tools/ci/submit-agent-job.sh` | 5 |
| `flowtree/.../FlowTreeApiEndpoint.java` | 5 |
| `flowtree/.../SlackNotifier.java` | 5, 6 |
| `flowtree/.../WorkstreamConfig.java` | 6 |
| `flowtree/.../FlowTreeController.java` | 6 |
| `flowtree/src/main/resources/workstreams-example.yaml` | 6 |
| `tools/ci/register-workstream.sh` | 7 |
