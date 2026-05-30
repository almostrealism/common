# Self-Improvement Phase (Retrospective)

**Status:** Planning
**Branch:** `feature/self-improvement-phase`
**Document version:** 1

---

## Motivation

We have observed significant behavioral differences between different runner/model combinations, and these differences are not visible from the final output alone. For example, opencode + MiniMax can produce valid code, but reports work progress via `send_message` far less frequently than Claude. We suspect there are also invisible differences in tool use and context efficiency — which cannot be discovered without studying actual session transcripts.

opencode transcripts are now stored on a persistent volume after job completion, and MCP tools already exist to parse them. We want to close this feedback loop: after a job's primary work is done, optionally launch a reflection session that studies the primary phase transcript and extracts concrete opportunities to improve how agents work.

---

## 1. Phase Identity

### 1.1 Name and Wire Identifier

| Candidate | Wire name | Notes |
|-----------|-----------|-------|
| `RETROSPECTIVE` | `"retrospective"` | Best fit — implies looking back at completed work |
| `SELF_IMPROVEMENT` | `"self-improvement"` | Accurate but verbose; clashes with branch naming |
| `REFLECTION` | `"reflection"` | Good, but overloaded in general usage |

**Decision:** `RETROSPECTIVE` / `"retrospective"`.

`retrospective` aligns with the wire-name vocabulary of existing phases (`primary`, `review`, `deduplication`, `organizational-placement`) and clearly signals "after the fact." It is distinct from `review`, which is about code quality. This phase is about process quality.

### 1.2 Position in Phase Ordering

The retrospective phase runs **last**, after all enforcement rules and all correction phases. Its position relative to existing phases:

```
PRIMARY → [correction loops] → REVIEW → DEDUPLICATION → ORGANIZATIONAL_PLACEMENT → POST_COMPLETION → COMMIT_MESSAGE → [RETROSPECTIVE]
```

<!-- TODO(review): Contradicts Section 3.2 — Section 3.2 says phase runs OUTSIDE the rule loop via doWork(), not as an EnforcementRule in buildActiveRules(); clarify which is authoritative (Section 3.2/3.3 appear to be the deliberate design) -->
Practically, it is added to `EnforcementRunner.buildActiveRules()` as the final entry, controlled by a boolean flag `reflectionEnabled` (analogous to `reviewEnabled`).

### 1.3 Activation Mechanism

Mirrors the pattern of `reviewEnabled` and `enforceOrganizationalPlacement`:

- **Job-level boolean** on `CodingAgentJob`: `reflectionEnabled` (default `false`)
- **Submission parameter** in `workstream_submit_task`: `reflectionEnabled: bool = False`
- **Per-phase config** applies to the retrospective phase via the standard `phase_configs` / `default_phase_config` system

The phase is **disabled by default**. Operators opt in per-job.

### 1.4 Phase Enum Addition

A new `Phase` enum value in `flowtree/agents/src/main/java/io/flowtree/jobs/agent/Phase.java`:

```java
RETROSPECTIVE("retrospective", "Retrospective session — analyzes the primary phase transcript for improvement opportunities."),
```

And `fromRuleName()` should return `RETROSPECTIVE` for `"retrospective"` (currently returns `null`).

---

## 2. Transcript Access

### 2.1 Transcript Location

Transcripts are written by `OpencodeTranscriptWriter` (`flowtree/agents/src/main/java/io/flowtree/jobs/agent/OpencodeTranscriptWriter.java`) to the **well-known directory** `/agent-transcripts` (the persistent per-agent volume mount), or `$OPENCODE_TRANSCRIPT_DIR` if set.

Filename format (line 319):
```
<yyyyMMdd-HHmmss>-<jobId>-<phase>.jsonl
```

The primary phase's transcript will have `phase = "primary"` in the filename and header.

### 2.2 Locating the Primary Transcript

The retrospective agent (a fresh agent session running after primary work) needs to find the primary transcript for the **same job**. The following are available in `CodingAgentJob` context at the time the retrospective phase runs:

- `job.getTaskId()` — the job ID
- `job.getWorkstreamId()` — available from `CodingAgentJob.getWorkstreamId()`
- Phase = `"primary"`

The retrospective prompt should instruct the agent to:
1. Use `list_transcripts(directory="/agent-transcripts", job_id="<jobId>")` to find the primary transcript file
2. Filter by `phase="primary"` (from the transcript header's `phase` field)
3. Use `get_transcript_summary()`, `get_tool_usage()`, `get_timeline_summary()`, `get_errors()` to analyze it

Alternatively, the prompt can construct the expected filename directly: `<timestamp>-<jobId>-primary.jsonl` and use `get_transcript_summary(path=...)`. However, using `list_transcripts` is more robust since the timestamp is unknown.

### 2.3 MCP Tools Available to the Retrospective Agent

The `ar-transcript-analyzer` MCP server (`tools/mcp/transcript-analyzer/server.py`) provides these tools:

| Tool | Returns | Use for |
|------|---------|---------|
| `list_transcripts(directory, job_id)` | List of transcript files with metadata | Locating the right transcript |
| `get_transcript_summary(path)` | Session metadata, timing, counts, tool use, errors | Overview of the session |
| `get_transcript_metadata(path)` | Header fields: job_id, workstream_id, phase, model, provider, etc. | Confirming correct transcript |
| `get_transcript_timing(path)` | Duration, cost, turns, exit code | Timing analysis |
| `get_tool_usage(path, limit)` | Per-tool counts, denied tools, tool event samples | Tool use quality |
| `get_timeline_summary(path)` | Compact event timeline | Step-by-step walkthrough |
| `get_errors(path)` | Error and corruption events | Error recovery analysis |

The retrospective agent also has full access to the standard agent tools (`Read`, `Grep`, etc.) and the `ar-manager` tools including `memory_store`.

### 2.4 Handling Missing Transcripts

The retrospective phase must degrade gracefully when no transcript is found:

**When to expect no transcript:**
- Primary ran on Claude Code (not opencode) — no transcript is written
- `OPENCODE_TRANSCRIPT_DIR` was not set and `/agent-transcripts` doesn't exist
- Transcript write failed (e.g., disk full) — `OpencodeTranscriptWriter.write()` returns `null` on failure

**Graceful degradation:**
The retrospective prompt instructs the agent: if `list_transcripts` returns zero matching transcripts (or an error), emit a single memory noting "No transcript available for job {jobId} — cannot perform retrospective" and exit cleanly (commit no changes).

The phase always terminates with exit 0 regardless. The enforcement loop is not entered for this phase (see Section 3).

---

## 3. Design Risk: Non-Code-Producing Phase

### 3.1 The Problem

Every existing phase that runs inside `EnforcementRunner`'s loop (`ReviewRule`, `DeduplicationRule`, `OrganizationalPlacementRule`, `PostCompletionCommandRule`, `MavenDependencyProtectionRule`) **produces code changes** or **must signal when it is satisfied**. The loop structure:

```java
// EnforcementRunner.run() — simplified
for (EnforcementRule rule : rules) {
    if (!rule.isViolated(job)) continue;
    while (attempts < ruleCap && rule.isViolated(job) && !job.hasAgentCommitted()) {
        job.runCorrectionSession(correctionPrompt, ruleName);
        rule.onCorrectionAttempted(job);
    }
}
```

If a rule's `isViolated()` returns `true` perpetually, the loop retries indefinitely. The retrospective phase must not trigger this behavior.

### 3.2 Chosen Approach: Separate Post-Enforcement Hook

The retrospective phase runs **outside the enforcement rule loop**, as a final step after all enforcement rules have completed. This is the cleanest solution because:

- It cannot cause retry loops
- It has no concept of "violation" — it always runs once when enabled
- It produces memories, not code changes
- It does not interfere with commit message or other post-commit rules

Implementation: Add a `runReflectionPhase()` method on `CodingAgentJob` that is called at the end of `doWork()`, after `runEnforcementRules()`. It is gated by `reflectionEnabled`:

```java
// CodingAgentJob.doWork() — simplified
@Override
protected void doWork() {
    if (sessionStartedAt == null) sessionStartedAt = Instant.now();
    executeSingleRun();           // primary run
    if (!hasAgentCommitted()) {
        runEnforcementRules();    // correction loops
    }
    if (reflectionEnabled) {
        runReflectionPhase();      // retrospective — runs even if no commit
    }
}
```

Note: `hasAgentCommitted()` is `false` when the agent produced no code changes. `runEnforcementRules()` still runs in this case (to give `EnforceChangesRule` a chance). The retrospective phase runs after that, regardless.

### 3.3 `runReflectionPhase()` Implementation

```java
void runReflectionPhase() {
    String originalPrompt = this.prompt;
    String previousActivity = this.currentActivity;
    this.currentActivity = Phase.RETROSPECTIVE.wireName();
    try {
        this.prompt = buildReflectionPrompt();
        executeSingleRun();  // runs one retrospective session
    } finally {
        this.prompt = originalPrompt;
        this.currentActivity = previousActivity;
    }
}
```

`buildReflectionPrompt()` constructs the analysis prompt (Section 3.4). The session runs with `currentActivity = "retrospective"`, so `resolveCurrentPhase()` returns `Phase.RETROSPECTIVE`. The per-phase `PhaseConfig` (runner, model, effort, provider) applies automatically — no changes needed to `resolveRunner()` or `resolveEffectivePhaseConfig()`.

Since `executeSingleRun()` checks `hasAgentCommitted()` only at the **start** of the enforcement loop (not after), the retrospective session does not trigger additional enforcement runs after it completes. It is the terminal phase.

### 3.4 The Reflection Prompt

The prompt is built by a `RetrospectivePromptBuilder` (new class, analogous to `ReviewPromptBuilder`). The prompt instructs the retrospective agent:

**Role:** You are reviewing a transcript of another agent's working session to find opportunities for improvement. You are NOT here to redo the work.

**What to study:**
1. The primary phase transcript (located via `list_transcripts` and analyzed via the transcript analyzer tools)
2. The context: job ID, workstream ID, model that ran the primary phase

**v1 Focus Areas** (bounded scope):

1. **Tool Use Quality**
   - Did the agent use all relevant tools available to it?
   - Did it miss tools that would have helped (e.g., `send_message` for status updates, `memory_recall` for context)?
   - Did it use a tool inefficiently (e.g., reading an entire file when a targeted `Grep` would do)?
   - Did it call `send_message` to surface important state or decisions?
   - Were tools denied (`denied_tool_names` in the transcript footer)?

2. **Context Efficiency**
   - Did the agent waste context on redundant reads or re-reading files it already had?
   - Did it explore irrelevant areas of the codebase?
   - Did it lose track of earlier findings in a long session?
   - Did it manage the session well overall (turn count vs. work done)?

**NOT in v1 scope** (defer to keep bounded):
- Code quality or correctness (that's the review phase's job)
- Architectural decisions
- Cost efficiency (hard to infer from transcript alone without knowing task complexity)
- Error recovery behavior (would require comparing error events to subsequent actions)

**Output format:** The retrospective agent stores findings as memories (one per finding) using `memory_store`. Each memory has:

```
Namespace: self-improvement
Tags: ["retrospective", "tool-use"|"context-efficiency", "workstream:<workstreamId>", "model:<modelThatRanPrimary>", "job:<jobId>"]
Content: STRUCTURED:
  OBSERVED: <what was seen in the transcript>
  WHY_SUBOPTIMAL: <why this is a problem or missed opportunity>
  SUGGESTED_IMPROVEMENT: <concrete action the agent could have taken>
```

If no transcript is available, store one memory with:
```
Namespace: self-improvement
Tags: ["retrospective", "no-transcript", "workstream:<workstreamId>", "job:<jobId>"]
Content: No transcript available for retrospective analysis. Job ID: <jobId>. Possible causes: primary ran on Claude Code, transcript recording failed, or session was very short.
```

**Completion:** The retrospective session exits after storing memories. No `commit.txt` is written. `hasAgentCommitted()` returns `false`, but since the retrospective phase runs after the enforcement loop, this does not re-trigger enforcement.

---

## 4. Output Mechanism: Memories, Not Automated Changes

The retrospective phase emits its findings as **memories only**, not as code changes, not as prompt modifications, not as configuration changes. This is a deliberate safety measure: self-modifying systems that act on un-reviewed self-analysis are dangerous.

### 4.1 Memory Tag Conventions

Downstream aggregation depends on consistent tagging. The convention for retrospective findings:

| Tag | Meaning | Example |
|-----|---------|---------|
| `retrospective` | Marks all retrospective-phase memories | `retrospective` |
| `tool-use` | Focus area: tool use quality | `tool-use` |
| `context-efficiency` | Focus area: context management | `context-efficiency` |
| `workstream:<id>` | Workstream the primary job ran on | `workstream:ws-common` |
| `model:<model>` | Model used in the primary phase | `model:openai/gpt-4.1` or `model:qwen3-coder` |
| `job:<id>` | Job ID of the primary session | `job:7d45c15a-8628-4d8f-b307-942393fbc09d` |
| `no-transcript` | Signal that no transcript was found | `no-transcript` |

### 4.2 Memory Structure

Each finding is a structured string with three fields (OBSERVED / WHY_SUBOPTIMAL / SUGGESTED_IMPROVEMENT). This structure makes aggregation and parsing straightforward.

### 4.3 Example Memory

```
Namespace: self-improvement
Tags: ["retrospective", "tool-use", "workstream:ws-common", "model:openai/gpt-4.1", "job:7d45c15a"]
Content:
  OBSERVED: The agent made 47 Read tool calls, including re-reading the same file 6 times
    across different turns (ModelRegistry.java, lines 1-50, 100-150, 200-250).
  WHY_SUBOPTIMAL: Re-reading the same sections wastes context tokens and increases
    latency. The agent could have retained the content from the first read.
  SUGGESTED_IMPROVEMENT: After reading a file, note its key content in working memory
    before moving on. Avoid re-reading unless the file has changed.
```

---

## 5. Runner and Model Configuration

### 5.1 Per-Phase Config Applies

The retrospective phase uses the standard per-phase config resolution:
1. `phase_configs["retrospective"]` from the job submission
2. Falls back to `default_phase_config`
3. Falls back to `phaseConfigBundle.defaultPhaseConfig()`

The `PhaseConfig` fields (`runner`, `model`, `effort`, `provider`) all apply.

### 5.2 Recommended Default

Since retrospective analysis benefits from strong reasoning (reading a long transcript, identifying patterns, producing structured findings), the recommended default for the retrospective phase is:

```json
{
  "runner": "claude",
  "model": "claude-sonnet-4-7",
  "effort": "medium",
  "provider": "anthropic"
}
```

This is **stronger than the primary model** in many cases (e.g., when primary ran `opencode + qwen3-coder`). A smarter model analyzing a weaker model's transcript yields better insights.

However, the phase is fully configurable, and operators can override it to use `opencode` with a cheap model if cost is the primary concern.

### 5.3 Telemetry

The retrospective phase's cost and runtime are accumulated into the job's `costByRunner` and `costByModel` via the existing `absorbResult()` call in `executeSingleRun()`. Additional telemetry captured:

| Field | Source | Description |
|-------|--------|-------------|
| `reflectionRan` | `CodingAgentJob` flag | Whether the phase ran |
| `reflectionTranscriptFound` | Retrospective agent memory | Whether a transcript was found |
| `reflectionFindingsCount` | Number of memories stored | How many findings were emitted |
| `reflectionCostUsd` | `finalResult.costUsd()` | Cost of the reflection session |

These are surfaced in `CodingAgentJobEvent` (analogous to `reviewInfo`).

---

## 6. Tests

### 6.1 Phase Activation

- `reflectionEnabled=true` on `CodingAgentJob` → retrospective phase runs
- `reflectionEnabled=false` (default) → retrospective phase skipped
- Boolean submitted via `workstream_submit_task(reflectionEnabled=True)` → phase runs

### 6.2 Transcript-Not-Found Path

- When `list_transcripts` returns no matching files → agent stores one "no transcript" memory and exits cleanly
- Phase completes with exit 0
- No enforcement loop triggered

### 6.3 Findings Are Emitted as Memories with Correct Tags

- `memory_store` calls observed with correct tags (`retrospective`, `tool-use` or `context-efficiency`, `workstream:<id>`, `model:<model>`, `job:<id>`)
- Content follows the OBSERVED / WHY_SUBOPTIMAL / SUGGESTED_IMPROVEMENT structure

### 6.4 Non-Code-Producing Phase Completes Correctly

- No `commit.txt` written by retrospective agent
- `hasAgentCommitted()` returns `false` (same as before the retrospective phase)
- No enforcement re-entry after retrospective completes

### 6.5 Phase Ordering

<!-- TODO(review): Second instance of the buildActiveRules() contradiction — per Section 3.2/3.3 the retrospective phase is NOT in buildActiveRules() at all; verification should check that doWork() calls runReflectionPhase() after runEnforcementRules(), not buildActiveRules() order -->
- Retrospective runs after all other phases (including `commit-message`)
- Verified by checking `buildActiveRules()` order: retrospective is added last

### 6.6 Per-Phase Config Routing

- `phase_configs={"retrospective": {"runner": "claude", "model": "claude-opus-4-7"}}` → retrospective uses those settings
- Default applies when no per-phase override set

### 6.7 Implementation Location

Tests should be in `flowtree/runtime/src/test/java/io/flowtree/jobs/CodingAgentJobRetrospectiveTest.java` (new file), mirroring the pattern of `CodingAgentJobDeduplicationTest`, `CodingAgentJobReviewTest`, etc.

---

## 7. Future Expansion

These are out of scope for v1 but should be documented for future work:

### 7.1 Studying Transcripts from Non-Primary Phases

The retrospective phase targets the primary phase transcript. Future work could:
- Also study `review`, `deduplication`, or `organizational-placement` phase transcripts
- Compare tool use patterns across phases (e.g., did the review agent use different tools than primary?)

### 7.2 Aggregating Findings Across Many Jobs

Individual finding memories can be aggregated to spot patterns:
- "MiniMax consistently fails to use `send_message`"
- "opencode + qwen3-coder has higher re-read rates than Claude Sonnet"
- "Context efficiency degrades after turn 30 across all models"

This would require a periodic aggregation job that queries memories by tag and produces trend reports.

### 7.3 Human-in-the-Loop from Findings to Prompt Improvements

Findings are memories, not automated changes. A future workflow:
1. Human reviews retrospective findings periodically
2. Approved improvements are encoded in prompt templates or `InstructionPromptBuilder`
3. Changes are validated in a test job before full rollout

### 7.4 Broadening Focus Areas

v1 focuses on tool-use quality and context efficiency. Future focus areas:
- **Cost efficiency**: Did the agent use an expensive model when a cheaper one would suffice?
- **Error recovery**: After an error event, did the agent recover quickly or spiral?
- **Tool denial patterns**: Are specific tools consistently denied? Is the denial correct (security) or overly restrictive?
- **Memory usage**: Did the agent use `memory_recall`/`memory_store` effectively?

---

## 8. Open Questions

### 8.1 Exact Phase Name

`RETROSPECTIVE` / `"retrospective"` is the recommendation. Alternative: `SELF_IMPROVEMENT` / `"self-improvement"` if the team prefers alignment with the branch name. We should confirm before implementation.

### 8.2 Transcript Scope: opencode Only or Always?

**Recommendation:** Run when enabled and attempt to find a transcript; if none exists, no-op gracefully. This means:
- The phase runs even when primary ran on Claude Code (no transcript available → "no transcript" memory stored)
- The phase produces a finding either way (transcript found → tool-use/context findings; not found → "no transcript" finding)

Alternative: Only run when the primary used opencode. This would require checking `runner` in the transcript header or the job's runner configuration. The phase would skip entirely when primary ran on Claude Code.

### 8.3 Default Runner/Model for the Phase

The recommendation is `claude` with `claude-sonnet-4-7` as the default model. We should confirm this is acceptable, or decide that the default should match the primary model's runner (e.g., if primary used `opencode`, retrospective also uses `opencode`).

### 8.4 Memory Tag Namespace

The recommendation uses namespace `self-improvement` with tags `retrospective`, `tool-use`, `context-efficiency`, `workstream:<id>`, `model:<model>`, `job:<id>`. Should we also include a version tag (e.g., `v1`) to distinguish findings format across versions?

### 8.5 Transcript Directory for the Reflection Agent

The reflection agent runs on the **same node** as the primary agent (same job, same container). Therefore `/agent-transcripts` is accessible. However, in Docker environments where volumes are not shared between containers, if the reflection agent ever runs on a different node, it would not have access to the transcript. This is currently not a concern since the retrospective phase runs in the same container. Document this assumption.

---

## 9. Implementation Sketch

### 9.1 Files to Create

| File | Purpose |
|------|---------|
| `flowtree/runtime/src/main/java/io/flowtree/jobs/RetrospectivePromptBuilder.java` | Builds the retrospective analysis prompt |
| `flowtree/runtime/src/test/java/io/flowtree/jobs/CodingAgentJobRetrospectiveTest.java` | Tests |

### 9.2 Files to Modify

| File | Change |
|------|--------|
| `flowtree/agents/src/main/java/io/flowtree/jobs/agent/Phase.java` | Add `RETROSPECTIVE` enum value; update `fromRuleName()` |
| `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java` | Add `reflectionEnabled` field; add `runReflectionPhase()`; update `doWork()` to call it |
| `flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJobFactory.java` | Support `reflectionEnabled` in factory creation (if applicable) |
| `flowtree/runtime/src/main/java/io/flowtree/jobs/EnforcementRunner.java` | (No changes needed — retrospective runs outside the rule loop) |
| `tools/mcp/manager/server.py` | Add `reflectionEnabled` parameter to `workstream_submit_task` |

### 9.3 Key Invariant

The retrospective phase must **not** use `job.runCorrectionSession()` because that method saves/restores `commit.txt` and has semantics oriented around producing commits. The retrospective phase uses `executeSingleRun()` directly with a modified prompt, which is the correct approach.

---

## 10. Related Code References

| Class/File | Relevant for |
|------------|-------------|
| `OpencodeTranscriptWriter` | Transcript storage location and filename format |
| `tools/mcp/transcript-analyzer/server.py` | MCP tools for transcript analysis |
| `tools/mcp/transcript-analyzer/transcript_parser.py` | Transcript data structures (header, footer, events) |
| `Phase` enum | Wire name addition |
| `PhaseConfig`, `PhaseConfigBundle` | Per-phase configuration |
| `EnforcementRunner` | Phase ordering (retrospective is last) |
| `CodingAgentJob.doWork()` | Where to insert retrospective hook |
| `CodingAgentJob.runCorrectionSession()` | Reference for how correction sessions are run |
| `ReviewRule` | Reference for a phase that manages its own exit condition |
| `ReviewPromptBuilder` | Reference pattern for prompt builder |

---

*This plan was produced by studying the transcript-recording infrastructure (`feature/opencode-transcript-recording`), the phase framework, and the enforcement runner. The main design risk — that retrospective produces analysis, not code — is addressed by running it outside the enforcement rule loop as a terminal post-processing step.*
