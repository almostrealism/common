# Mid-Run Message Delivery to FlowTree Jobs

## Goal

Let an operator (human via ar-manager, or another tool) send a new instruction
to a Claude Code session *while it is running* inside a `ClaudeCodeJob`, so the
agent can react to it on its next turn. The mechanism must work even when the
agent runs behind a firewall or NAT — the controller cannot push to the agent
directly; the agent must pull.

Concrete use case: the user sees the agent heading in the wrong direction
(chasing the wrong file, overlooking a constraint, about to revert something
important) and wants to redirect it without killing the job and re-submitting
from scratch.

---

## Current State

`ClaudeCodeJob` invokes the Claude CLI with `claude -p "<prompt>"
--output-format json ...`. That is one-shot mode: stdin is closed after the
initial prompt, and the subprocess processes exactly that prompt before
exiting. There is no channel for new turns once the job has started.

Related existing pieces:

- `ar-manager` already has `send_message(workstream_id, job_id, text)` which
  POSTs a notification to the workstream's Slack channel (and stores it as a
  memory). That is a *status update to humans*, not an instruction to the
  agent — the running Claude never sees it.
- The controller exposes `/api/workstreams/{ws}/jobs/{job}/messages` (POST
  text, routed to Slack). Again: human-facing.
- Agents carry a short-lived HMAC temp-token (`armt_tmp_…`) that identifies
  their `(workstream_id, job_id)` when they call ar-manager MCP tools.

---

## Design

Three moving pieces: a per-job inbox on the controller, a polling thread in
the agent wrapper, and a streaming-stdin mode on the Claude subprocess. The
delivery model is *pull from the agent, at-least-once, deduplicated by
sequence number on the agent side*.

### Wire model

Each in-flight job has a logical inbox keyed by `(workstreamId, jobId)`. An
inbox message is:

```json
{
  "seq": 7,
  "createdAt": "2026-04-21T04:12:33.129Z",
  "sender": "human:ashesfall",
  "text": "Skip the spatial module refactor — we're merging that separately."
}
```

`seq` is a monotonically-increasing 64-bit integer assigned by the controller
when the message is accepted. Agents track a `lastDeliveredSeq` cursor locally
and poll for `seq > lastDeliveredSeq`. The controller does not need to know
per-agent cursors; each poll carries `since=<seq>`.

### Controller: the inbox store

**New class:** `flowtree/src/main/java/io/flowtree/slack/JobMessageInbox.java`.

**Storage:** on-disk append-only log rooted at `dataDir/job-messages/`, one
file per job: `<jobId>.ndjson`. Each line is one message JSON object.
Rationale:

- Controller restarts mid-job must not lose undelivered messages.
- `<jobId>.ndjson` isolates job-specific state from the global stats DB; the
  file can be deleted the moment the job terminates with no concurrency
  concerns.
- NDJSON is append-friendly and trivially tailable; no schema migrations.

**Concurrency:** a `ConcurrentHashMap<String, Inbox>` keyed by jobId. Each
`Inbox` holds:
  - A `ReentrantLock` for write coordination.
  - The in-memory message list (loaded from file on first touch).
  - The next `seq` counter.

Reads are unlocked snapshots over the list (CopyOnWriteArrayList or explicit
copy under a shared read lock).

**API:**

```
POST   /api/workstreams/{wsId}/jobs/{jobId}/inbox
        body: {"text": "...", "sender": "..."}
        -> {"ok": true, "seq": 7, "createdAt": "..."}

GET    /api/workstreams/{wsId}/jobs/{jobId}/inbox?since=<seq>
        -> {"ok": true, "messages": [...], "nextSince": <highestSeq>}
          (returns all messages with seq > since; never blocks;
           empty list + unchanged nextSince when nothing new)

DELETE /api/workstreams/{wsId}/jobs/{jobId}/inbox
        -> {"ok": true}
           (called by the agent wrapper after the job terminates,
            so the controller can purge the file)
```

The POST endpoint enforces the existing workstream-scope gate (writers must
have write scope on the workstream's workspace). The GET/DELETE endpoints are
open inside the private network — agents authenticate with their temp-token
via the existing `Authorization: Bearer armt_tmp_…` flow, and the temp-token
already encodes `(workstreamId, jobId)`; the handler rejects polls where the
URL's jobId does not match the token's jobId.

**Lifecycle:**

1. First POST for a job creates `<jobId>.ndjson` and the in-memory Inbox.
2. Agent wrapper polls via GET.
3. Agent wrapper DELETEs on job termination (success, failure, cancel).
4. Controller also periodically reaps inboxes older than 24h with no
   corresponding active job (cleanup in case the agent died before DELETE).

### Agent wrapper: the poller

**Where it lives:** `flowtree/src/main/java/io/flowtree/jobs/JobInboxPoller.java`.
Owned and lifecycle-managed by `ClaudeCodeJob`. One poller per job.

**Thread model:** a dedicated daemon thread started immediately after the
Claude subprocess is spawned. Runs in a tight loop:

```
sleep(POLL_INTERVAL)     // default 10s, tunable via env
if (claudeProcess.isAlive()) {
    messages = GET .../inbox?since=lastSeq
    for each message: write to claude stdin as stream-json user turn
    lastSeq = messages.last.seq
} else {
    break
}
```

Join before the job completes; on exit, issue the DELETE call (best-effort,
failure logged and ignored — the controller's reaper will eventually handle
it).

**Why 10s default:** small enough that human operators experience "seconds,
not minutes" latency; large enough that 100 concurrent agents generate only
10 req/s against the controller; large enough to stay in-cache on the
controller side. Tunable via `FLOWTREE_JOB_INBOX_POLL_SECS` because a demo
scenario may want 2s.

**Backoff:** on controller error or network failure, exponential backoff up
to 5 minutes. Resume at base interval on first successful poll.

**Delivery semantics:** at-least-once. The cursor is advanced *only after* a
successful stdin write, so a wrapper crash between POST/stream-write means
the message is re-delivered. Duplicate delivery is an acceptable trade for
implementation simplicity; the downstream user turn just restates the
instruction, which the agent handles gracefully.

### Claude subprocess: switch to streaming input

**Current invocation:**

```
claude -p "<initial prompt>" --output-format json
   --allowedTools ... --max-turns N --max-budget-usd X
   --mcp-config {...}
```

**New invocation (for jobs that may receive mid-run messages):**

```
claude --input-format stream-json --output-format stream-json
   --allowedTools ... --max-turns N --max-budget-usd X
   --mcp-config {...}
```

First line written to stdin is the initial prompt as:

```json
{"type":"user","message":{"role":"user","content":[{"type":"text","text":"<prompt>"}]}}
```

Each inbox message becomes an identical user turn on stdin. Claude emits
stream-json result events on stdout as it works; `ClaudeCodeJob` already
parses JSON output, so the switch to stream-json on stdout just means
processing events incrementally instead of one final object at EOF.

**Open verification points** (to check before writing the actual code):

1. Does `stream-json` input accept additional turns mid-run, or does Claude
   only read stdin before the first response? Documentation says it accepts
   multiple turns; need to confirm at the current Claude CLI version.
2. Behavior when a new turn arrives during a tool call: does Claude interrupt
   the tool, queue the turn for after the tool completes, or ignore it?
   Expected: queue for after. Worth a manual test before committing to the
   design.
3. Error mode when max-turns is hit mid-stream: does Claude exit immediately
   on next turn attempt? Expected: yes. The wrapper detects via
   `claudeProcess.isAlive()` and stops polling.

### ar-manager: the authoring tool

**New MCP tool:**

```python
@mcp.tool()
def workstream_send_job_message(
    workstream_id: str,
    job_id: str,
    text: str,
) -> dict:
    """Send a message to a running Claude Code job. The message is
    delivered to the agent on its next polling cycle (default: within
    10 seconds) and becomes a new user turn in the session."""
```

Scope: requires `write` and the workstream's workspace in scope (or
unscoped). Calls `POST /api/workstreams/{ws}/jobs/{job}/inbox` on the
controller. Returns `{"ok": true, "seq": …, "delivery": "queued"}` —
"delivery" is explicitly *queued*, not "received", because the tool cannot
guarantee the agent picks it up (the poller might have just started its
sleep, or the job might terminate before the next poll).

Distinct from the existing `send_message`, which posts to Slack + memory for
*humans*. The two are unrelated by design; naming them differently keeps the
confusion surface small.

---

## Failure Modes

| Scenario | Behavior |
|---|---|
| Controller unreachable from agent | Wrapper exponential-backs off; messages accumulate on controller until reachable; eventually delivered at-least-once. Job continues working on its current state of knowledge. |
| Agent wrapper dies with pending messages in buffer | Controller still holds them; next agent resumption (not in this phase — see Phase 3 below) would pick them up. For now, discarded on DELETE. |
| Claude subprocess exits before next poll | Wrapper sees `!isAlive()`, breaks the loop, issues DELETE. Messages posted after that point are accepted by the controller but never read; they're purged by the 24h reaper. The POST response already said "queued", not "received". |
| User posts a message to a job that doesn't exist | Controller returns 404. |
| Two simultaneous POSTs to the same inbox | Serialised under the Inbox's lock; both get unique sequential `seq` values. |
| Temp-token expiry while poller is running | Wrapper re-fetches a fresh token from `ClaudeCodeJob` (already handled for other ar-manager calls). |
| Claude CLI version too old for stream-json | Detected at job start via a probe command; falls back to one-shot mode with a log-line saying mid-run messaging is disabled for this job. |

---

## Security

Same model as the rest of ar-manager: the sender must be authorised to act on
the target workstream. The controller remains thin and trusting, because the
controller is only reachable on the private network; ar-manager enforces the
scope check before forwarding.

The temp-token held by the agent is the only credential that can poll a
specific job's inbox. A compromised temp-token would let an attacker read
instructions destined for that one job — the existing risk surface for temp
tokens, unchanged by this feature.

---

## What This Feature Is NOT

- **Not synchronous.** The sender gets `seq` + `queued`, not "received". If
  you need synchronous interaction, submit a new job.
- **Not cross-job.** Messages are scoped to one `(workstream, job)` pair. A
  message sent to a completed job is discarded; a message sent to the
  *workstream* (rather than a specific job) is a separate feature that isn't
  in scope here.
- **Not a Slack integration.** The two message paths (Slack via
  `send_message`, agent-inbox via `workstream_send_job_message`) are
  independent. A human using Slack still only talks to Slack.
- **Not reliable against controller disk loss.** If the controller's
  `dataDir` is destroyed before the agent polls, messages are lost. This is
  the same fate as every other piece of controller state.

---

## Implementation Order

### Phase 1 — Controller inbox (no consumers yet)

1. `JobMessageInbox` class with on-disk NDJSON backing.
2. `FlowTreeApiEndpoint` handlers for POST/GET/DELETE `/inbox`.
3. Workstream-scope gate on POST (reuse existing notifier-aware resolution).
4. Java unit tests: append, read-since, persistence across restart, DELETE.
5. The inbox is inert at this point — nothing reads it. Low risk to land.

### Phase 2 — ar-manager authoring tool

6. `workstream_send_job_message` MCP tool with scope gate.
7. Python tests: scope accept/reject, forwards to controller, returns seq.

### Phase 3 — Agent wrapper polling + stream-json delivery

8. Probe Claude CLI for stream-json support at job startup.
9. `JobInboxPoller` class, owned by `ClaudeCodeJob`.
10. Switch `ClaudeCodeJob`'s `claude` invocation to stream-json mode (with a
    fallback to `-p` for old Claude versions or jobs that opted out).
11. Wire poller start/stop to job lifecycle; DELETE inbox on job completion.
12. Integration test: spawn a mock process that echoes stdin; verify
    messages posted via the controller arrive on stdin.

### Phase 4 — Reaper + cleanup

13. Periodic sweeper on the controller that deletes inboxes older than 24h
    with no matching active job.
14. Log + metric for undelivered message counts at job completion.

---

## Rollout

Phase 1 and 2 are independently shippable — they add an inbox that nobody
reads, and a tool that queues messages into it. Until Phase 3 lands, the
feature is dormant but observable (you can send a message and see it sit in
the file). That makes the Phase 3 change low-stakes: if the streaming-stdin
rewrite turns out to misbehave, revert just Phase 3 and the rest of the
system keeps working.

The Phase 3 change is the riskiest — it modifies how Claude is invoked for
every job. Introduce a `FLOWTREE_JOB_INBOX_ENABLED=false` escape hatch
initially so a problem with stream-json can be defused without a code change.

---

## Open Questions

1. **Default poll interval.** 10s is a guess. Want to start at 15s and tune
   after observing real usage?
2. **Message size cap.** Slack caps outbound messages at ~40k chars; agent
   inbox messages have no such constraint technically, but stuffing 100kB of
   instruction into a user turn is unhelpful. Propose 10kB cap with a clear
   error message. Same `MAX_CONTENT_LEN` pattern we already use elsewhere.
3. **Ordering guarantee when multiple senders post concurrently.** The `seq`
   assignment under the per-inbox lock is total-order within a job. Is that
   enough, or do we need any cross-job ordering? I believe no.
4. **Interaction with `deduplicationMode=spawn`.** When a job spawns a
   follow-up dedup job, the follow-up has a new jobId → new inbox. Messages
   posted during the original run are NOT carried over. Is that the desired
   behavior, or should the second job inherit the first's undelivered
   messages? Needs a decision before Phase 3.
5. **Visibility of queued-but-undelivered messages to humans.** Should ar-
   manager expose a "peek at this job's inbox" tool? Useful for debugging.
   Probably yes, but defer to Phase 4.
