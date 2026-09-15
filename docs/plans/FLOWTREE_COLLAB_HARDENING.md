# FlowTree Collaboration Hardening

Follow-up to `docs/plans/MULTI_AGENT_COLLABORATION.md`, written against the
field report in `docs/plans/FLOWTREE_COLLAB_REVIEW.md`. That report describes one
coordinating session driving a collaborative job through ar-manager and lists
eight kinds of friction. This document traces each one to its cause in the code,
decides what to change, and orders the work.

The short version: most of the friction is one bug. The rest is the harness not
telling the participants things it already knows.

---

## Findings — verified, with evidence

### F1. A Claude Code session is silent until it ends, so the watchdog cannot see it

`ClaudeCodeRunner.buildCommandLine` emits `claude -p … --output-format json`.
Run against the installed CLI (2.1.270), that form writes **one line to stdout,
at the very end of the session** — nothing during it, not even with
`--verbose`. Only `--output-format stream-json --verbose` produces per-event
NDJSON (`system/init`, `assistant` with `tool_use`, `user` with `tool_result`,
`result`).

`AgentInactivityMonitor` resets its clock on every stdout line
(`AgentProcessRunner.pumpOutput`). For a Claude Code session there are no such
lines, so the "inactivity" timeout (`AgentRunner.DEFAULT_INACTIVITY_TIMEOUT_MILLIS`,
35 minutes) is a hard per-session wall clock. It fires whether the agent is
hung in a shell loop, downloading weights, running a 165-second test, or
sitting in `await_message`. The review's item 2 ("the watchdog counts long tool
calls as silence") is a special case of this; so is item 1 (the relaunches that
lost the coordinator's messages were this same clock), and so is item 8b (the
session "ended" by inactivity rather than by the coordinator's finish).

The earlier plan's claim that "each `await_message` return writes a line to the
agent's stdout, which resets the watchdog" — repeated in `flowtree/CLAUDE.md` —
is not true for the Claude runner. The tool's 25-minute cap has only ever
worked because 25 < 35.

`ClaudeCodeRunner.parseClaudeNdjson` already reads NDJSON: it finds the last
line whose `type` is `result` (`JsonFieldExtractor.extractLastJsonObject`), so
switching output formats needs no parser change.

### F2. A relaunched session is handed the task again, not the conversation

`InstructionPromptBuilder.build` prepends an "INACTIVITY TIMEOUT" preamble when
`inactivityRestartAttempt > 0`. It tells the agent to check
`workstream_context` — the memory narrative — and says nothing about the
workstream conversation. A collaborative job that is relaunched reads its own
earlier memories and the user request, and starts the preparatory steps over,
which is exactly the "second 2.3 GB download" in the report.

The conversation is already durable and addressable (`WorkstreamMailbox`,
`GET /api/workstreams/{ws}/mailbox?since=0`). The job has the workstream URL
(`GitManagedJob.resolveWorkstreamUrl`) and posts to it already; it just never
reads it.

### F3. `await_message` is bounded by the caller's MCP transport, not by the server

`messaging_tools.await_message` defaults to 300 seconds and composes ≤120-second
controller long-polls. From an interactive session the MCP client's own
per-call timeout is the binding limit (the report observed ~60 seconds), so any
`timeout_seconds` above it returns a transport error instead of an empty
result — and the caller cannot tell that the wait was simply too long.

Inside a job the same tool works because the `claude` subprocess is the MCP
client and the job environment can raise its tool timeout — but nothing sets
`MCP_TOOL_TIMEOUT` today, so a job is relying on the CLI default.

### F4. A timed-out `send_message` cannot be safely retried

The tool's controller POST usually lands even when the MCP round-trip times
out; a retry posts the same text twice (the duplicated final report in item 8a).
`MessageEndpointHandler.handle` has no notion of a message identity — every
POST is a new `seq`, a new memory, and a new Slack post.

### F5. Build output under `target/` is treated as binary litter

`InvalidFileDetector.scanRepo` walks the whole working tree, deliberately
bypassing `.gitignore` ("an ignored file is still litter"), and exempts only
`.bin` files present on the base branch. Maven's `target/` directory is build
output: it is created by the build, wiped by `mvn clean`, ignored by
`**/target/**`, and is precisely where the reference-dump tests write their
`.bin` fixtures so that nothing reaches source control. The detector cannot tell
it from a stray `model.bin` in `src/`, so the job is restarted with an order to
delete its own test fixtures.

### F6. `workspace_secret_render_file` writes on the ar-manager host and does not say so

`McpConfigBuilder.EXCLUDED_AR_MANAGER_TOOLS` withholds both `workspace_secret_*`
tools from agent jobs on purpose: the tool renders the file on the machine that
runs ar-manager, and a job needs it in its own filesystem, which is what the
in-container `ar-secrets` server is for. Two things are missing: the tool's
result (`{"ok":true,"output_path":…}`) does not name the host that received the
file, so an operator at an interactive session cannot tell it went elsewhere;
and the job prompt never mentions `ar-secrets`, so a job that reaches for the
ar-manager tool gets a permission denial with no pointer to the right one.

### F7. The job knows where it landed; nobody else does

Node labels are held on the worker (`Node.getLabels`, `AutomaticLabel`) and a
job is relayed until a node whose labels satisfy it accepts it
(`Node` worker thread, `NodeGroup.findNodeForJob`). The controller never sees a
peer's labels, so `agent_options` cannot list them and `workstream_submit_task`
cannot say which host will run the job. What *is* cheap is for the job itself to
say where it is: `HarnessStatusReporter.phaseEntry` already posts the first
message of every job, and the host name, the automatic labels, and the checked
out `HEAD` are all local facts at that moment.

### F8. The job record has no phase

`GET /api/jobs/{id}` (`JobQueryHandler.getJob`) returns the last status event,
which is `STARTED` from submission until the terminal event. The phase
transitions (`primary` → `review` → …) are posted as `harness_status` messages
to the channel and the mailbox but recorded nowhere a poller can read.
`JobStatsStore` already has a per-job `heartbeat_at` column that every status
event refreshes (`StatsQueryHandler.recordHeartbeat`); a phase is the same shape
of fact.

---

## Design

### D1. Liveness comes from the event stream, and an in-flight MCP call is not silence

`ClaudeCodeRunner.buildCommandLine` emits `--output-format stream-json --verbose`.
Every event is a stdout line, so the existing monitor sees the agent think, call
tools, and receive results. Nothing else about the run changes: the same
`AgentProcessRunner`, the same output capture, the same parser.

That alone makes the watchdog mean what its name says. It does not yet cover a
tool call that is *itself* longer than the window — a multi-gigabyte download
through an MCP tool emits `tool_use` and then nothing until `tool_result`. The
monitor therefore learns one more thing: whether a tool call is in flight, and
which kind. `AgentProcessRunner` lets the runner supply an **activity
classifier** (`Function<String, AgentActivity>`) alongside the loop-signature
extractor it already accepts; `ClaudeCodeRunner` classifies `assistant`
`tool_use` blocks as opening a call and `user` `tool_result` blocks as closing
it, keyed by `tool_use_id`. While an **MCP** tool call (`mcp__*`) is open, the
monitor grants a longer window, `AgentInactivityMonitor.IN_FLIGHT_MCP_CALL_MILLIS`
(60 minutes), because those calls are bounded by their own server-side timeouts
and are where the long legitimate waits live (test runs, build validation,
downloads, `await_message`). A `Bash` call in flight grants nothing: an
unterminated shell loop is the hang the watchdog exists to catch, and the CLI's
own Bash timeout is the bound for well-behaved commands.

`flowtree/CLAUDE.md`'s "silence budget" section is corrected to describe this.

### D2. A relaunched collaborative session replays the conversation

A new collaborator, `io.flowtree.jobs.ConversationCatchUp`, reads the
workstream mailbox through the job's workstream URL
(`GET …/mailbox?since=0`) and renders the messages that followed the job's own
last message as a prompt block. `CodingAgentJob.buildRunRequest` attaches it
through `InstructionPromptBuilder.setConversationCatchUp(String)` when the job is
collaborative and this is not its first session (any restart path — inactivity,
enforcement, violation — since all of them lose the conversation the same way).

The block is a restart preamble, placed with the others, and says the thing the
agent cannot infer: *messages addressed to you arrived while you were down; act
on the newest instruction, and do not redo anything you were told to skip.* The
messages are quoted verbatim, oldest first, with sender and time, so the agent
can also see what it said last.

Reading is best-effort: an unreachable controller yields no block and the
session proceeds as today.

### D3. Waits fit the transport; sends are idempotent

`await_message` gains a transport-aware default. `DEFAULT_AWAIT_SECONDS` drops
to `TRANSPORT_SAFE_AWAIT_SECONDS` (25): an interactive caller that passes
nothing gets a wait that fits under any MCP client's per-call timeout and loops
on `timed_out`. The cap (`MAX_AWAIT_SECONDS`, 1500) stays for callers whose
transport allows it, and the docstring says which is which. Job sessions are
made to be such callers: `McpConfigBuilder.applyAgentEnvironment` sets
`MCP_TOOL_TIMEOUT` (the Claude Code per-tool-call ceiling, in milliseconds) to
cover the cap with margin, and the Collaboration Protocol tells the job it may
wait the full cap.

`send_message` gains `message_id`. When the caller supplies none, the tool
derives one from the sender, activity, and text, so a retry of a timed-out call
carries the same id without the agent having to remember anything. The id
travels as `messageId` in the POST body; `WorkstreamMailbox` keeps it on the
`Message`, and `MessageEndpointHandler.handle` answers a repeat within
`WorkstreamMailbox.DEDUPE_WINDOW_MILLIS` (15 minutes) with the original `seq`
and `duplicate=true` — no second memory, no second Slack post, no second
delivery. Posts with no `messageId` (the harness's own status messages) are
never deduplicated, so a relaunch that legitimately re-announces a phase is
not swallowed.

### D4. Maven build output is not litter

`InvalidFileDetector` skips any `target` directory whose parent holds a
`pom.xml`. That is Maven's build-output directory by definition — never
committed, wiped by `mvn clean` — and it is the only exemption added. A `.bin`
anywhere else in the tree is still litter, `.gitignore` is still bypassed for
it, and the base-branch exemption is unchanged.

### D5. The secrets tool says where the file went; the job prompt says which tool to use

`workspace_secret_render_file` returns `host` (the ar-manager machine's host
name) and a `note` stating that the file was written there, not on the caller's
machine. `InstructionPromptBuilder` adds one sentence to the MCP-tools section:
workspace credentials come from the `ar-secrets` server inside the job, and the
ar-manager `workspace_secret_*` tools are operator tools that are not granted
to jobs. The allowlist itself is unchanged: granting the ar-manager tool to jobs
would give them a file on the wrong machine.

### D6. The job's first message says where it is and what it sees

`HarnessStatusReporter.phaseEntry` takes a placement line that
`CodingAgentJob` composes from local facts: host name, the automatic labels
(`AutomaticLabel`), and the checked-out branch head (`git rev-parse HEAD` in
the working directory). The PRIMARY entry message becomes, in effect, "started
on *halo* (`platform=linux`, `hostname=halo`) at `feature/x@abc1234`". The
coordinator learns the runner from the job instead of guessing, and knows
which commit the job's claims are about.

The Collaboration Protocol gains the other half of item 7: the job sees only
what is on `origin`; when a collaborator says a fix exists locally, quote the
head you checked instead of re-verifying and reporting it "still unfixed".

Advertising peer labels from `agent_options` needs the nodes to publish their
labels to the controller, which is a `NodeGroup` protocol change. It is
deferred (see Open Questions), and D6 is what makes it less urgent.

### D7. The job record carries the current phase

`HarnessStatusReporter.phaseEntry` / `phaseExit` include a `phase` field in the
message body. `MessageEndpointHandler` passes it to a phase recorder
(`StatsQueryHandler.recordPhase` → `JobStatsStore`, a `phase` column on
`job_timing` beside `heartbeat_at`). `JobQueryHandler.getJob` reports `phase`
when one is recorded, so `workstream_get_job` from the coordinator distinguishes
"primary still running" from "primary done, review running".

---

## Implementation Order

Each step lands with its tests and is independently useful.

1. **Event-stream liveness (D1).** `ClaudeCodeRunner` flags; `AgentActivity` +
   classifier in `flowtree/agents`; `AgentProcessRunner` threads it to the
   monitor; `AgentInactivityMonitor` in-flight grace. Tests:
   `ClaudeCodeRunnerTest` command line and classifier; `AgentInactivityMonitorTest`
   grace applies only to an open MCP call and expires. `flowtree/CLAUDE.md`
   corrected.
2. **Conversation catch-up (D2).** `ConversationCatchUp`,
   `InstructionPromptBuilder.setConversationCatchUp`, wiring in
   `CodingAgentJob.buildRunRequest`. Tests: rendering from a mailbox JSON
   document, "own last message" cut, empty when nothing followed,
   prompt placement.
3. **Transport-safe awaits, idempotent sends (D3).** `messaging_tools.py`
   defaults and `message_id`; `WorkstreamMailbox.Message.messageId` +
   `findRecent`; `MessageEndpointHandler` dedupe; `McpConfigBuilder`
   `MCP_TOOL_TIMEOUT`. Tests: Python default/derivation/explicit id; Java
   dedupe within window, distinct ids, harness posts untouched, persistence of
   the id.
4. **Build output exemption (D4).** `InvalidFileDetector`. Test: `.bin` under
   `<module>/target/` ignored, `.bin` under a `target/` with no `pom.xml`
   sibling still flagged.
5. **Secrets host + prompt pointer (D5).** `workspace_tools.py`,
   `InstructionPromptBuilder`. Tests: result carries `host`; prompt names
   `ar-secrets`.
6. **Placement in the first message and protocol text (D6).**
   `HarnessStatusReporter`, `CodingAgentJob`, `InstructionPromptBuilder`.
   Tests: placement line rendered; protocol contains the origin-view rule.
7. **Phase in the job record (D7).** `HarnessStatusReporter` body,
   `MessageEndpointHandler`, `StatsQueryHandler`, `JobStatsStore`,
   `JobQueryHandler`. Tests: store round-trip; `/api/jobs/{id}` reports it.

`ar-build-validator` (checkstyle, code_policy, test_timeouts, duplicate_code)
before declaring done.

### Status

All seven steps are implemented in one change set, each with tests. Two
things a reviewer should know:

- `ClaudeCodeRunnerTest.buildCommandLineIncludesCoreClaudeFlags` asserted
  `--output-format json`; it now asserts `stream-json` and `--verbose`. That is
  an existing test method changed to follow a deliberate production change,
  not a test weakened to pass — the assertion is the behaviour D1 exists to
  change. Every other test change is an added method or a new class.
- `describePlacement` (D6) lives on `GitManagedJob`, not `CodingAgentJob`,
  because it reads only `GitManagedJob` state and because `CodingAgentJob`
  sits within a few lines of the 1600-line cap. `conversationCatchUp` (D2)
  does depend on the collaborative flag and the restart governor, so it is the
  one addition made to `CodingAgentJob`.

---

## What This Is Not

- **Not an interrupt.** A relaunched session reads the conversation; a running
  one still reads it only when it awaits. Pushing a message into a live session
  remains the deferred stream-json *input* work.
- **Not a scheduler.** The controller still does not know which host a job will
  land on before it lands. It learns afterwards, from the job.
- **Not a change to what jobs may do.** No tool is added to or removed from the
  agent allowlist.

---

## Open Questions

1. **Peer labels at the controller.** `agent_options` listing each node's
   labels and busy state needs nodes to advertise labels over the `NodeGroup`
   protocol. Worth doing, but it is a protocol change with its own plan; D6
   covers the immediate need.
2. **In-flight grace of 60 minutes** is a guess sized to the longest MCP-managed
   operation seen in the field (a multi-gigabyte download). If the test runner
   or build validator routinely exceed it, the grace should follow the tool's
   own configured timeout rather than a constant.
3. **Dedupe window of 15 minutes** assumes a retry follows a timeout promptly.
   A deliberate identical message inside the window is reported as
   `duplicate=true` rather than silently dropped, so a caller that means it can
   pass a fresh `message_id`.
4. **Explicit finish.** With D1 a collaborative session no longer ends by wall
   clock, so "told to finish" works as the protocol describes. Whether the
   review phase should be skipped for collaborative jobs is a separate
   question about what the phases are *for*, not about collaboration.
