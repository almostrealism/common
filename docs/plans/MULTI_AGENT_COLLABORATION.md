# Multi-Agent Collaboration over FlowTree Agent Jobs

## Goal

Two (or more) agent sessions, running on different machines in the same
cluster, should be able to hold a conversation while both are working.

The concrete scenario this plan is written against:

> I would like this run on the AMD machine, not the one I am on. Let me spawn a
> job that requires the label `hostname:halo`, telling it to get set up and then
> wait for me. When it tells me it is ready, I give it its first real
> instruction. We go back and forth. Eventually I tell it we are done. Most
> important of all: by then it has started long-running work on that machine
> that outlives its own session, and I can come back to that work later by
> submitting another job targeted at the same host.

Nothing in that scenario is exotic. What is missing today is a single
primitive: **an agent cannot receive anything.**

---

## Current State — verified, with evidence

### What already works

**Host targeting is built and needs no work.**
`flowtree/runtime/src/main/java/io/flowtree/node/AutomaticLabel.java` defines
two self-assigning labels: `PLATFORM` (`macos`/`linux`) and `HOSTNAME` (the
short, lower-case host name; `localhost` and unresolved addresses are rejected
so that a label meant for one machine cannot be satisfied by every machine).
`NodeLabelMatcher.satisfies(labels, requirements)` gates execution on the
worker side, and `workstream_submit_task(required_labels="hostname:halo")`
(`tools/mcp/manager/workstream_submit_tools.py:508-511`) forwards
`requiredLabels` in the submission payload. Targeting the AMD machine — and
coming back to it later — is a parameter, not a feature request.

**Message archival and human notification work.**
`send_message` (`tools/mcp/manager/messaging_tools.py`) POSTs to
`/api/workstreams/{ws}[/jobs/{job}]/messages`, which
`io.flowtree.api.MessageEndpointHandler.handle` turns into (a) a memory in the
ar-memory `messages` namespace and (b) a Slack post, threaded under the job when
`SlackNotifier.getThreadTs(jobId)` resolves.

### What does not

**There is no receive primitive.** No tool, endpoint, or class in the
repository lets an agent read messages addressed to it, let alone block until
one arrives. `send_message` is a fire-and-forget write to two sinks that only a
human reads.

**The agent subprocess cannot be fed mid-run.**
`ClaudeCodeRunner.buildCommandLine` emits `claude -p <prompt> --output-format
json …`, and `AgentProcessRunner.applyRequestToProcessBuilder` ends with
`pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))`. The
session is one-shot and its stdin is closed before it starts.

**Waiting is what kills jobs.** `AgentRunner.DEFAULT_INACTIVITY_TIMEOUT_MILLIS`
is 35 minutes (`OpencodeRunner` overrides to 45), and `AgentInactivityMonitor`
destroys the whole process tree when stdout has been silent that long. This is
the "issues around timing out due to inactivity" in the brief, and it is the one
constraint that shapes the entire design: **a blocking call emits no stdout, so
every wait must be bounded well under the watchdog and must end by producing
output.**

### Relationship to `docs/plans/JOB_MESSAGE_INBOX.md`

That plan solves a narrower problem — a human redirecting one running job — and
pays a high price for it: a per-job inbox, a polling daemon thread inside
`ClaudeCodeJob`, and a rewrite of the Claude invocation to
`--input-format stream-json` so new user turns can be pushed onto stdin. The
riskiest part (item 10 of its implementation order) changes how *every* job is
launched.

This plan supersedes it, for three reasons:

1. **Duplex, not one-way.** Collaboration needs the spawned agent to speak
   first ("I am ready"), which a delivery-only inbox cannot express.
2. **No subprocess surgery.** An agent that *asks* for its messages needs no
   stdin, no stream-json, and no CLI version probe. The delivery mechanism is a
   tool call, which every runner already supports.
3. **The conversation, not the delivery, is the durable object.** Keying on the
   workstream rather than a job id means a follow-up job submitted tomorrow
   reads the same history — which is precisely the "come back to it later"
   requirement.

The stdin/stream-json work in `JOB_MESSAGE_INBOX.md` remains a legitimate future
optimisation (it would let a message *interrupt* an agent mid-task instead of
waiting for it to ask). It is not needed for collaboration and is not in scope
here.

---

## Design

### The one idea: the workstream is the conversation

A workstream already names a shared piece of work with a branch, a channel, and
a memory scope. Give it one more thing — an **ordered, durable, low-latency
message log** — and every collaboration question answers itself:

- *How do I reach the other agent?* Post to the workstream.
- *How does it reach me?* It posts to the same workstream.
- *How do we both avoid missing anything?* Each reader keeps its own cursor
  (`since`); the log is append-only and totally ordered by `seq`.
- *How does a job tomorrow pick up where today left off?* It reads from `seq`
  0.
- *How do humans see it?* Every post still archives to memory and to Slack —
  unchanged.

This is what the brief asks for in its own words: *"subscribe to messages in
some rational way on our own work stream."*

### Wire model

```json
{
  "seq": 7,
  "createdAt": "2026-09-08T19:12:33.129Z",
  "sender": "job:b6e8f404",
  "jobId": "b6e8f404-…",
  "activity": "",
  "text": "Ready. vLLM is up on halo, rungs bf16 and rtn-w4 are healthy."
}
```

`workstreamId` is not itself a field on the message: the mailbox is already
scoped to one workstream by the URL path, so it would be redundant on every
line.

`seq` is a monotonically increasing 64-bit integer assigned by the controller
under the mailbox's lock, so ordering is total within a workstream. Readers are
stateless on the server: every read carries `since=<seq>` and gets back
`nextSince`.

`sender` is the reader's only means of not hearing its own echo. It is derived,
never supplied by the agent: the job id when the controller knows one from the
URL, otherwise the caller's token label.

### Endpoints

Added to `FlowTreeApiEndpoint`, alongside the existing `/messages` suffix:

```
GET /api/workstreams/{ws}/mailbox?since=<seq>&wait=<seconds>&exclude=<sender>
      -> {"ok":true,"messages":[…],"nextSince":<seq>}
```

`wait` is a **long poll**, not a client poll: the request blocks on the
mailbox's monitor and returns the instant a message arrives, so end-to-end
latency is a round trip rather than half a polling interval. `wait=0` (the
default) returns immediately with whatever is already there. The server caps a
single hold at `MAX_WAIT_SECONDS` (120) so that no HTTP request is long enough
for an intermediary to time out; the *tool* does the looping to reach longer
waits.

There is deliberately **no new POST endpoint.** The existing
`POST /api/workstreams/{ws}[/jobs/{job}]/messages` gains one line: after
archiving and notifying, it appends to the mailbox. Every `send_message` that
has ever been written becomes readable, which is the augmentation the brief
asked for, and there is exactly one way to send.

### Storage

`io.flowtree.workstream.WorkstreamMailbox`, backed by one append-only NDJSON
file per workstream under the controller's data directory
(`<dataDir>/mailbox/<workstreamId>.ndjson`), loaded lazily on first touch and
held in memory thereafter. Rationale is the same as the inbox plan's: a
controller restart must not lose an undelivered instruction, NDJSON needs no
migrations, and per-workstream files make retention a `File.delete`.

Concurrency is a `ConcurrentHashMap<String, Mailbox>`; each `Mailbox` guards its
list and `seq` counter with its own monitor and uses `wait`/`notifyAll` for the
long poll. Waiters are capped per mailbox so a pathological client cannot pin
the NanoHTTPD thread pool.

Retention: messages older than `MAX_RETENTION` (7 days) are dropped when a
mailbox is loaded or appended to. A conversation is not an archive — memory
already is one.

### The tool

One new ar-manager tool, `await_message`:

```python
await_message(workstream_id="", since=-1, timeout_seconds=300,
              include_own=False) -> dict
```

- `since=-1` (the default) means *"only messages from now on"* — it resolves to
  the current head, so a first call does not replay the whole history. An
  explicit `since=0` replays everything, which is how a follow-up job catches
  up.
- `timeout_seconds` defaults to 300 and is capped at `MAX_AWAIT_SECONDS`
  (1500 = 25 minutes). **The cap exists because of the 35-minute inactivity
  watchdog**, and the tool says so in its docstring and in the `hint` it returns
  on timeout. Internally it issues successive ≤120s long polls against the
  controller until it has a message or has spent its budget.
- Returning on timeout is not a failure. It returns `ok=true`,
  `timed_out=true`, and an unchanged `next_since`, so the agent can call again.
  Each return writes a line to the agent's stdout, which resets the watchdog —
  the loop is the mechanism that keeps a waiting agent alive.

`send_message` is unchanged in signature. Its docstring gains the other half of
the story: messages are now also delivered to peers awaiting on the workstream.

### The protocol

`workstream_submit_task` gains `collaborative: bool = False`, which becomes a
property of the job itself — set on the factory, carried over the wire, and
reported in the job record — rather than text spliced into the prompt. What it
changes at run time is that `InstructionPromptBuilder` emits a **Collaboration
Protocol** section stating the four things an agent cannot infer:

1. Announce readiness with `send_message` when the preparatory steps in the
   prompt are done.
2. Then call `await_message` and act on what comes back.
3. On `timed_out=true`, call `await_message` again. Do not exit because nothing
   arrived, and do not busy-work to stay alive.
4. Long-running work must **outlive this session** — start it detached (`tmux
   new-session -d`, or `nohup … </dev/null >log 2>&1 &`), record where its logs
   and markers live with `memory_store`, and report the location with
   `send_message`. A later job targeting the same `hostname:` label will pick it
   up from there.

Point 4 is what makes the last requirement in the brief work, and it is
convention rather than mechanism on purpose: the detached-process patterns are
already field-tested (see `FLEET.md`), and encoding them as a supervisor would
be the "new `Job` type plus routing layer" that FlowTree is explicitly not.

### Why a blocking tool call rather than pushed stdin

| | blocking tool call | stdin / stream-json |
|---|---|---|
| Runner changes | none | rewrite launch for every job |
| Works with opencode | yes | unknown; separate CLI |
| Direction | duplex | agent-inbound only |
| Message arrives mid-tool-call | at next await | immediately |
| Failure mode | agent waits a bit longer | wedged subprocess, no stdout |

The one column stdin wins is interruption. That is a real capability, and the
path to it stays open: a mailbox that already exists is exactly what a future
pusher would read from.

---

## Failure Modes

| Scenario | Behavior |
|---|---|
| Controller unreachable during `await_message` | The tool retries across its budget with backoff; returns `ok=false` with the error if it never succeeds. The agent decides whether to continue alone. |
| Peer never replies | `await_message` returns `timed_out=true`. The agent loops or gives up per its prompt. Nothing is killed. |
| Agent waits longer than the watchdog | Cannot happen through the tool: `MAX_AWAIT_SECONDS` (25 min) is below the 35-minute window, and every return emits stdout. A caller passing a larger value is clamped, and told so in the response. |
| Controller restart mid-conversation | Messages are on disk; the next `await_message` with the reader's `since` returns everything it missed. |
| Two agents post simultaneously | Serialised under the mailbox monitor; both get distinct `seq` values, total order preserved. |
| Agent hears its own message | Excluded by `sender` unless `include_own=True`. |
| Unknown workstream | 404 from the controller, surfaced as `ok=false`. |
| Slack or ar-memory down | Unchanged from today for the archive/notify path; mailbox append is independent, so collaboration survives a Slack outage. |
| Job ends with unread messages | They stay in the mailbox for the retention window. A follow-up job reads them with `since=0`. |

---

## Security

Unchanged from the rest of ar-manager. `await_message` requires `read` scope and
`_require_workstream_in_scope`, the same gate `send_message` applies for writes;
the controller stays thin and private-network-only. A conversation is readable
by anything already authorised to read that workstream's memories, which is the
correct blast radius: the mailbox holds the same class of content the `messages`
namespace already holds.

---

## What This Is Not

- **Not an interrupt.** A message is seen when the agent next awaits, not
  mid-tool-call. Sending "stop" to an agent in the middle of a 20-minute build
  does not stop the build.
- **Not a supervisor.** Detached work started on a host is the host's business.
  FlowTree dispatches; it does not keep a vLLM server alive.
- **Not a replacement for `send_alert`.** Reaching a *human* out of band is
  still `send_alert`.
- **Not cross-workstream.** Agents on different workstreams do not share a
  mailbox. Collaborators are submitted onto the same workstream, which is also
  what puts them in the same Slack channel and the same memory scope.

---

## Implementation Order

### Phase 1 — the mailbox (controller)

1. `io.flowtree.workstream.WorkstreamMailbox` — append, read-since, long-poll
   wait, NDJSON persistence, retention.
2. `MessageEndpointHandler` appends to the mailbox after archive/notify.
3. `FlowTreeApiEndpoint`: `/mailbox` suffix on `WORKSTREAM_PATH`, GET handler,
   `setMailbox` injection; `FlowTreeController` constructs it against `dataDir`.
4. Java tests: ordering, `since` filtering, sender exclusion, persistence across
   reload, long-poll wakeup, `wait` cap, retention.

Landing here alone is inert and safe: messages accumulate and nothing reads
them.

### Phase 2 — the tool (ar-manager)

5. `_controller_get` gains a caller-supplied timeout (the existing default of
   10s is shorter than a long poll).
6. `await_message` in `messaging_tools.py`; register in
   `tool_capabilities.GRANTED_TOOLS` and
   `McpConfigBuilder.AR_MANAGER_TOOL_NAMES` (both are enforced by
   `allowlistCoversEveryArManagerTool`).
7. `send_message` docstring updated to describe peer delivery.
8. Python tests: cap clamping, `since=-1` resolution, self-exclusion, timeout
   returns `ok=true`, budget looping, scope gate.

### Phase 3 — `collaborative` as a job property

`collaborative` is not prompt decoration; it describes what the job *is*, so it
lives on the job and travels with it. The full path, all of it in this change:

9.  `collaborative` parameter on `workstream_submit_task`, forwarded as
    `payload["collaborative"]`. The submitter's prompt is never rewritten.
10. `FlowTreeApiEndpoint#handleSubmit` reads the field onto the factory, and the
    submit response reports it back so a caller can confirm what it got.
11. `CodingAgentJobFactory` field, accessors, and decode case;
    `CodingAgentJobConfigurer` propagates it to the job; `CodingAgentJob` field
    and accessors; `CodingAgentJobCodec` encode/decode.
12. `InstructionPromptBuilder#setCollaborative` emits the Collaboration Protocol
    section — the four things an agent cannot infer.
13. Tests: default, toggle, factory→job propagation, **wire round trip in both
    directions**, protocol present only when set, and the user request left
    untouched.

**Making room, rather than working around the cap.**
`CodingAgentJobFactory` was 1591 lines against a 1600-line Checkstyle
`FileLength` limit — no space for a field. Squeezing the property out of the job
record to avoid the refactor would have made the feature invisible to the job
listing, to later jobs, and to anyone debugging a run; a flag that behaves
correctly while recording nothing is the expensive kind of shortcut.

The room was made where the codebase already pointed. `PhaseRunnerConfig` had
been extracted from `CodingAgentJob` for exactly this reason, and
`CodingAgentJobFactory` carried a **second implementation of the same runner /
phase-bundle consistency logic** — the two differing only in that the factory
also mirrors changes into its serialized property store. Giving
`PhaseRunnerConfig` an optional *property sink* (a `BiConsumer<String,String>`
receiving the wire keys a mutation changed, and `apply*` methods for the decode
direction that publish nothing) let the factory delegate to it instead. That
removed the duplication and took the factory from 1591 to 1503 lines, which is
where the new property fits.

This is now written down as a rule in `flowtree/CLAUDE.md`: a job property
belongs on the job, "the file is near its cap" is a reason to refactor rather
than to relocate the property, and that refactoring is in scope for the change
that needed the room.

### Phase 4 — deferred, not in this pass

11. A `mailbox_peek` tool for humans debugging a stalled conversation.
12. Per-job inactivity-window override, for sessions that want to wait longer
    than 25 minutes in one call.
13. Interruption via `--input-format stream-json`, reading from this same
    mailbox (the surviving half of `JOB_MESSAGE_INBOX.md`).

---

## Open Questions

1. **Retention of 7 days** is a guess. Long enough that a weekend does not lose
   a conversation; short enough that a workstream's mailbox does not grow
   without bound. Tune after use.
2. **`since=-1` as the default** trades "never miss anything" for "do not
   replay a week of history on first call". A collaborator that wants the
   history asks for `since=0`. Is the default right?
3. **Cap of 25 minutes** is derived from the 35-minute Claude watchdog. When
   phase 4 item 12 lands, the cap should follow the job's actual configured
   window rather than a constant.
4. ~~**Should `collaborative` also raise `max_turns`?**~~ **Resolved: no.**
   Each await costs a turn, so a long conversation does spend turns on waiting —
   but `max_turns` is already a per-job submission parameter, and the agent
   submitting a collaborative job is in the best position to judge how long the
   conversation will run. Raising it implicitly would override a value the
   submitter chose deliberately.

---

## Extension — `ShellCommandJob` as a wait/resume primitive

This extension closes the loop the Goal scenario leaves manual: *"by then it
has started long-running work on that machine that outlives its own session,
and I can come back to that work later by submitting another job targeted at
the same host."* Today "coming back to it later" means a human (or another
agent) remembering to poll. `ShellCommandJob` — the command-execution
counterpart of `CodingAgentJob`, see `flowtree/runtime/.../jobs/ShellCommandJob.java`
— is the natural vehicle for "run this long thing and tell me when it's done,"
but it has two gaps that block that use case. Both are fixed here.

### Problem 1 — working directory does not match the coding-agent job's

Evidence, read side by side in `FlowTreeApiEndpoint#handleSubmit`:

- Coding-agent path (~line 819): `if (listener != null && listener.getDefaultWorkspacePath() != null) factory.setDefaultWorkspacePath(listener.getDefaultWorkspacePath());`
- Shell path, `submitShellCommandJob` (~line 1073): no equivalent call exists at
  all, and `ShellCommandJob.Factory` does not even expose
  `getDefaultWorkspacePath()` / `setDefaultWorkspacePath()` — only
  `workingDirectory`, `repoUrl`, `branch`, and `workstreamUrl` are threaded from
  the workstream onto the factory.

`GitManagedJob` (the shared base of both job types) already has a
`defaultWorkspacePath` field with full wire support in `GitManagedJobCodec`
(`defaultWsPath` key) — that part of the pipe was never the problem. The break
is upstream: nothing ever calls `setDefaultWorkspacePath` on the *factory*
before it builds the shell job, so the job's own field is always null at
dispatch time regardless of what the operator configured.

Effect: when a workstream has no explicit `workingDirectory` (the common case
— most workstreams rely on `repoUrl` + the operator's `defaultWorkspacePath`),
`GitRepositorySetup.resolveWorkspacePath()` falls through to
`WorkspaceResolver.resolve(null, repoUrl)`, which resolves to
`/workspace/project/<repo>` (if that directory exists on the node) or the OS
temp directory — *not* the directory every coding-agent job on the same
workstream actually clones into. An agent that submits a shell job expecting
it to run "where my commands execute" (the task's own words) can find it ran
somewhere else entirely, or triggered a redundant clone.

**Fix:** give `ShellCommandJob.Factory` a `defaultWorkspacePath` property,
following the exact pattern already used for `repoUrl` (get/set through the
factory's own base64-encoded property map), and thread it through
`nextJob()`. In `FlowTreeApiEndpoint#submitShellCommandJob`, add the same
`listener.getDefaultWorkspacePath()` propagation the coding-agent path already
has. No change is needed to `GitManagedJob`, `GitManagedJobCodec`, or
`GitRepositorySetup` — they already do the right thing once the factory
supplies the value.

### Problem 2 — a shell job cannot ask to be woken up

There is today no way for an agent to say "launch this long-running command
and resume me when it's done." The only related primitive,
`docs/plans/COMPLETION_LISTENERS.md`, is a **workstream-level, standing**
configuration (`Workstream#completionListeners`) explicitly forbidden from
ever including the workstream itself — `ListenerCycleChecker` rejects
`A -> A` as `self-listing` at config time, because a standing self-listener
would fire on *every future job* that finishes on that workstream, forever.

That is the wrong shape for "I'm about to launch one long-running task and
want to hear back about *that one*." The right primitive is a **per-job,
opt-in** flag: `selfNotify` on `ShellCommandJob`. When set, the job's own
completion — and only that job's completion — fans out a wake-up
`CodingAgentJob` to its own workstream, using the exact same wake-up
construction, prompt shape, and safety ceilings as
`CompletionListenerFanout` already builds for cross-workstream listeners
(`docs/plans/COMPLETION_LISTENERS.md` §2.1.2, §2.2). This also resolves
`COMPLETION_LISTENERS.md`'s open question 6 ("wake-up for shell-command
jobs... a v2 can filter") in the opposite direction it was framed: v2 does not
filter shell jobs out of the *listener* fan-out, it gives shell jobs a
narrower, job-scoped self-wake-up instead.

### Why this does not reopen the self-listing hole

`ListenerCycleChecker` and `selfNotify` are different mechanisms guarding
different things, and the config-time one is untouched:

- **Nothing is added to `Workstream#completionListeners`.** `selfNotify` never
  touches the persisted listener graph, so `ListenerCycleChecker` keeps
  rejecting `A -> A` in workstream config exactly as it does today. A
  workstream that never submits a self-notifying shell job never wakes
  itself, no matter how many other jobs finish on it.
- **The trigger is one job's completion, not "any job finishes here."** A
  standing listener fires forever because it is evaluated on every future
  completion; `selfNotify` is consumed once, by the one job that set it.
- **The wake-up is a real agent turn, not another self-notifying command.**
  `fanoutSelf` (see below) submits an ordinary `CodingAgentJob`. Recursion
  requires that agent to *decide*, on its own initiative, to submit another
  self-notifying shell job — the same judgment-bounded step that stops the
  orchestrator loop in `COMPLETION_LISTENERS.md` §2.1.4.
- **The existing ceilings already cover the worst case.** `fanoutSelf`
  dispatches through `CompletionListenerFanout#dispatchToListener` with
  `listenerId == sourceWorkstreamId`. A workstream that keeps re-arming
  `selfNotify` on every wake-up saturates its own per-listener budget —
  `DEFAULT_MAX_WAKE_UPS_PER_WINDOW` (6 per 600s) and
  `DEFAULT_DEBOUNCE_SECONDS` (300s) — exactly as a runaway multi-workstream
  orchestrator would against a shared listener. No new ceiling logic is
  needed; the per-listener key does not care whether the listener is also the
  source.
- **The kill switch still wins.** `acceptAutomatedJobs=false` stops
  `fanoutSelf` the same way it stops `fanout`, because both call through the
  same `dispatchToListener` gate chain.

### Why this is restricted to `ShellCommandJob`

`selfNotify` is rejected by `FlowTreeApiEndpoint#handleSubmit` unless the job
being submitted is a shell job (`jobType=shell` or `command` present).
Rationale: a `ShellCommandJob` is a single bounded command with no agent
intelligence behind it — a wake-up is the *only* way its completion becomes
actionable to anyone. A `CodingAgentJob`, by contrast, already runs a full
reasoning session that can call `workstream_submit_task` itself as its last
action if it wants a follow-up; letting a coding job set `selfNotify` would
let one agent turn silently re-arm its own automatic continuation, which is a
strictly less visible way to build the same loop `flowtree/CLAUDE.md`'s "the
job is the source of truth" rule exists to prevent (a property that describes
what a job *does* must be visible on the job record, not inferred from
"the agent probably meant to keep going").

### A correctness gap `selfNotify` exposes: exit code is not reflected in status

`GitManagedJob#createEvent(Exception)` reports `Status.SUCCESS` unless
`doWork()` threw. `ShellCommandJob#doWork()` catches every `IOException` /
`InterruptedException` itself and never rethrows — so **every** shell job
completes with `Status.SUCCESS` regardless of the command's actual exit code.
This has always been slightly wrong (the completion *message* already prints
"Note: exit code != 0 indicates command failure" while the completion
*event* says `SUCCESS`), but it becomes actively misleading once a status is
the thing a self-notify wake-up prompt reports to the agent that gets woken:
"did my long-running task actually finish OK?" is exactly the question
`Finished job status:` is supposed to answer. `ShellCommandJob` needs to
override `createEvent(Exception)` so a non-zero `exitCode` (with no thrown
exception) produces a `FAILED` event. This is small and self-contained, but
it is in scope here because it is required for `selfNotify` to tell the truth.

### Wiring checklist (per `flowtree/CLAUDE.md`'s "the job is the source of truth" rule)

| Layer | File | Change |
|---|---|---|
| Submission tool | `tools/mcp/manager/workstream_submit_tools.py` | `self_notify: bool = False` param + docstring; `payload["selfNotify"]`; client-side rejection when not a shell job |
| HTTP submit | `FlowTreeApiEndpoint#handleSubmit` | extract `selfNotify`; reject with a 400-style error when `!shellJob` |
| HTTP submit (shell) | `FlowTreeApiEndpoint#submitShellCommandJob` | `factory.setSelfNotify(selfNotify)`; `factory.setDefaultWorkspacePath(listener.getDefaultWorkspacePath())` (Problem 1's fix); submit response reports `selfNotify` back |
| Factory | `ShellCommandJob.Factory` | `defaultWorkspacePath` and `selfNotify` fields/accessors, mirroring the existing `repoUrl` pattern |
| Factory -> job | `ShellCommandJob.Factory#nextJob` | `job.setDefaultWorkspacePath(...)`, `job.setSelfNotify(...)` |
| Job | `ShellCommandJob` | `selfNotify` field + accessors; `createEvent` override (exit-code fix) |
| Wire | `ShellCommandJob#encode` / `#set` | encode branch + decode case for `selfNotify` |
| Behaviour | `ShellCommandJob#populateEventDetails` (new override) | `event.withSelfNotify(isSelfNotify())` |
| Event model | `JobCompletionEvent` | `selfNotify` field, `isSelfNotify()`, `withSelfNotify(...)`, `toJson()` entry |
| Controller decode | `FlowTreeApiEndpoint#handleStatusEvent` | parse `selfNotify` from the posted JSON, apply to the event |
| Controller dispatch | `FlowTreeApiEndpoint#completeJob` | call `completionListenerFanout.fanoutSelf(...)` when `event.isSelfNotify()`, alongside the existing `fanout(...)` call |
| Fan-out | `CompletionListenerFanout` | new public `fanoutSelf(sourceWorkstreamId, event)`, delegating to the existing private `dispatchToListener` with `listenerId == sourceWorkstreamId` |

### Test plan

- `ShellCommandJobTest`: `defaultWorkspacePath` round-trips on the job and the
  factory (mirrors the existing `repoUrl` round-trip tests);
  `factory.nextJob()` propagates both `defaultWorkspacePath` and `selfNotify`;
  `createEvent` returns `FAILED` for a non-zero exit code with no thrown
  exception, and `SUCCESS` for exit code 0.
- `JobCompletionEventTest` (or nearest equivalent): `withSelfNotify` /
  `isSelfNotify` round-trip; `toJson()` includes `selfNotify`.
- `CompletionListenerFanoutTest`: `fanoutSelf` fires a wake-up to the source
  workstream; respects the kill switch, the per-listener window ceiling, and
  the debounce identically to `fanout`; a source workstream with no
  registered `Workstream` entry is a no-op (mirrors `wakeup_source_missing`).
- An HTTP-level test on `handleSubmit` / `submitShellCommandJob`:
  `selfNotify=true` on a coding-agent job (no `command`) is rejected;
  `selfNotify=true` on a shell job is accepted and reaches the factory.

### Implementation order

1. Working-directory fix (Problem 1): `ShellCommandJob.Factory` gains
   `defaultWorkspacePath`; `submitShellCommandJob` propagates it. Independent
   of everything below; lands and is tested on its own.
2. `createEvent` exit-code fix on `ShellCommandJob`, with its own test —
   independent of `selfNotify`, but a prerequisite for it to be meaningful.
3. `selfNotify` wire threading: `JobCompletionEvent` field, `ShellCommandJob`
   field/accessors/encode/set/`populateEventDetails`, `ShellCommandJob.Factory`
   field/accessors, `nextJob()` propagation.
4. Controller wiring: `handleSubmit` validation, `submitShellCommandJob`
   plumbing, `handleStatusEvent` parsing, `completeJob` dispatch,
   `CompletionListenerFanout#fanoutSelf`.
5. `workstream_submit_task` MCP parameter + docstring.
6. Tests per the plan above; `ar-build-validator` (checkstyle, code_policy,
   test_timeouts, duplicate_code) before declaring done.
