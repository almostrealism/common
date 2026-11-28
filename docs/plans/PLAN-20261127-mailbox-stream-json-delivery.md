---
status: proposed
created: 2026-11-27
closed:
tracker: [5f13d1c7-e94d-43d8-a614-1ec04f7aff93]
---

# PLAN-20261127: Deliver mailbox messages to a running Claude session via stream-json input

Status: design only. This document is the design pass the tracker task asks for; it
changes no production code. It is written against the code on
`feature/mailbox-stream-json-delivery` as of this writing, and every mechanism it names
is cited to a real class and method so the implementation stages can be checked against
it.

Tracker task: "Deliver mailbox messages to a running Claude session via stream-json
input." The task is to let a message sent to a workstream while its job is running reach
the live agent session without the agent polling, behind a per-job flag, with tests for
the runner wiring.

---

## 1. Problem and current behaviour

Collaborating agents talk through a workstream's mailbox. `send_message`
(`tools/mcp/manager/messaging_tools.py`) POSTs to the controller, which appends to the
workstream's `WorkstreamMailbox` and forwards to Slack
(`io.flowtree.api.MessageEndpointHandler#handle`). `await_message` (same Python module)
is the *only* way a running agent receives: it long-polls `GET /mailbox`
(`io.flowtree.api.MailboxEndpointHandler#handle` →
`io.flowtree.workstream.WorkstreamMailbox#read`) and returns when a peer posts or the
wait elapses. This is a **pull** model — a running agent sees a message only on a turn in
which it chose to call `await_message`. There is no way to redirect an agent mid-task
between those calls.

A Claude Code job is launched by `io.flowtree.jobs.CodingAgentJob#executeSingleRun`, which
builds an `AgentRunRequest` (via `AgentRunRequestFactory#buildRunRequest`) and calls
`ClaudeCodeRunner#run`. The runner builds the command line in
`ClaudeCodeRunner#buildCommandLine`:

```
claude -p <prompt> --output-format stream-json --verbose
       --setting-sources project,local --allowedTools <csv>
       [--permission-mode bypassPermissions] --max-turns <n>
       [--max-budget-usd <d>] [--model <m>] [--effort <e>]
       --mcp-config <json>
```

Two facts matter for this task:

1. The prompt is passed **positionally** with `-p`, and there is **no
   `--input-format`** flag today. The session receives one prompt and no further input.
2. Stdin is wired to `/dev/null`:
   `AgentProcessRunner#applyRequestToProcessBuilder` ends with
   `pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))` and
   `pb.redirectErrorStream(true)`. The process therefore reads EOF on stdin immediately,
   which is harmless today because nothing is read.

The only existing bridge between the durable conversation and a session is
`io.flowtree.jobs.ConversationCatchUp`: on a *relaunch* it reads the mailbox
(`…/mailbox?since=0`), cuts at the job's own last message (sender `job:<taskId>`), and
renders the missed messages into the next session's prompt via
`InstructionPromptBuilder#setConversationCatchUp`. That is a **launch-time, one-shot**
injection — it has no effect on a session already running. This feature is, in effect,
the mid-session streaming counterpart of `ConversationCatchUp`, and should reuse its
mailbox-reading and own-sender-filtering logic rather than re-deriving them. **Note on
layering**: `ConversationCatchUp` lives in `flowtree/runtime`, which depends on
`flowtree/agents` (not the reverse — see `flowtree/CLAUDE.md`). An agents-side pump
therefore cannot call into `ConversationCatchUp`; the shared logic must be factored into
a module at or below `flowtree/agents`. `flowtree/base` is the natural home (both
`ConversationCatchUp` and the pump already depend on it, and `JsonFieldExtractor` — the
mailbox JSON helper this feature reuses — already lives there). This constraint is
carried through §5.4, §10 Stage 4 and §12.

---

## 2. What the Claude CLI's streaming input mode is (UNVERIFIED — pin in Stage 1)

The design hinges on the behaviour of `claude -p --input-format stream-json`, which this
plan could **not** verify: an attempt to run the installed `claude` binary in a scratch
directory (no model call, `/dev/null` and malformed-line probes) was permission-denied in
the design session, so the protocol details below are taken from the CLI's `--help` text
and the SDK's documented streaming-input contract, and are marked UNVERIFIED. **Stage 1
exists solely to pin them down with a contract test before any production code depends on
them.** The assumptions:

- With `--input-format stream-json`, the prompt and every subsequent user turn are
  newline-delimited JSON objects on **stdin**, each of the shape
  `{"type":"user","message":{"role":"user","content":[{"type":"text","text":"…"}]}}`
  (the same event shape `--output-format stream-json` emits for a user turn). The
  positional `-p <prompt>` is then either omitted or supplanted by the first stdin object.
- The session stays alive after completing a turn and **blocks reading stdin** for the
  next user object. It ends when stdin reaches **EOF** (and the in-flight turn, if any,
  finishes). This is the termination contract the whole design depends on: closing our
  write end of the pipe is how the job ends the session.
- The session emits a `result` event (`{"type":"result","subtype":…}`) at the **end of
  each turn**, not only once. Whether the per-`result` `total_cost_usd`, `num_turns`,
  `duration_ms` are cumulative over the session or per-turn is **unknown** and must be
  measured (it decides §6's accounting).
- Behaviour on a malformed or unknown-`type` stdin line is **unknown** (ignore? error and
  exit? close the session?). Stage 1 must measure it, because a hostile or buggy message
  must not be able to kill the session.

If Stage 1 finds the real protocol differs (e.g. a different envelope, or the session does
not stay alive on an open stdin under `-p`), the later stages adapt to what it finds;
nothing downstream should hardcode the envelope before then.

---

## 3. Design overview

Add an opt-in mode in which a flagged Claude Code job keeps the agent's stdin open and
feeds newly-arrived mailbox messages onto it as user turns, while the session is running.
The pieces:

1. **A per-job flag** (`deliverMailboxToSession`, default **off**) threaded through the
   full job-is-source-of-truth path, and a runner capability
   (`supportsStreamingInput`) so the flag is honoured only where it can be.
2. **Runner wiring** in `ClaudeCodeRunner`: when the request asks for streaming input and
   the runner supports it, add `--input-format stream-json` and wire stdin to a pipe this
   process writes, instead of `/dev/null`.
3. **A mailbox-to-stdin pump** running in the Java job process on the worker node: a
   bounded long-poll of `GET /mailbox` (the same route `await_message` uses) that writes
   each new, foreign, this-workstream message to the session's stdin as a user-turn JSON
   object. This is the "node-side harness" — it is a thread inside the already-running
   job process, not a new service.
4. **Inactivity-monitor, transcript, and accounting adjustments** so an injected turn
   neither falsely proves liveness nor corrupts the capture/metrics.
5. **A fallback**: runners without `supportsStreamingInput` (opencode) ignore the flag and
   keep the existing pull model; the launch-time `ConversationCatchUp` path is unchanged
   for them.

### 3.1 Routing is pull, not push

No controller→node push channel exists for a running job, and this design **adds none**.
The controller cannot address an individual running session; it only owns the mailbox. The
node-side pump reuses the existing read path — `GET /api/workstreams/{id}/mailbox` with
`since`/`wait`/`exclude` — exactly as `await_message` does. The only new thing on the node
is *where the arrivals go*: onto the session's stdin instead of being returned to a tool
call. Keeping routing pull-based means the controller, `WorkstreamMailbox`,
`MailboxEndpointHandler` and `MessageEndpointHandler` need **no changes** for the core
feature, and the durability/retention/dedupe guarantees already in `WorkstreamMailbox`
carry over unchanged.

---

## 4. The per-job flag and its default

Per `flowtree/CLAUDE.md` ("THE JOB IS THE SOURCE OF TRUTH FOR THE WORKLOAD"), a property
that changes what a job *is* must travel the whole path. The flag is
`deliverMailboxToSession`, **default `false`**. Default-off is required so that a merge
does not change how every existing job launches: a job without the flag must get a
**byte-identical** command line and the same `/dev/null` stdin it gets today.

Thread it through, in one change:

| Layer | File | What to add |
|---|---|---|
| Submission tool | `tools/mcp/manager/workstream_submit_tools.py` | `deliver_mailbox_to_session: bool = False` param, docstring entry, `payload["deliverMailboxToSession"]` |
| HTTP submit | `io.flowtree.api.FlowTreeApiEndpoint#handleSubmit` | `JsonFieldExtractor.hasField` → `factory.setDeliverMailboxToSession(...)` |
| Submit response JSON | `io.flowtree.api.FlowTreeApiEndpoint` | echo the field so a caller confirms what it got |
| Factory | `io.flowtree.jobs.CodingAgentJobFactory` | field, accessors, decode case |
| Factory → job | `CodingAgentJobFactory#configure` | `job.setDeliverMailboxToSession(field)` |
| Job | `io.flowtree.jobs.CodingAgentJob` | field, accessor |
| Wire | `io.flowtree.jobs.CodingAgentJobCodec` | encode branch, decode case |
| Request | `io.flowtree.jobs.agent.AgentRunRequest` (+ `Builder`) | `deliverMailboxToSession` field + `isDeliverMailboxToSession()` |
| Request build | `io.flowtree.jobs.AgentRunRequestFactory#buildRunRequest` | set it on the builder from the job |
| Behaviour | `ClaudeCodeRunner` + the new pump | honour it |

`AgentRunRequest` already carries the workstream id (`ENV_WORKSTREAM_ID` /
`getWorkstreamId()`) and the environment carries `AR_CONTROLLER_URL` and `AR_MANAGER_TOKEN`
(used today by `OpencodeRunner#controllerSecretLookup`), so the pump has everything it
needs to read the mailbox without new request fields beyond the flag itself.

**File-length pressure.** `CodingAgentJob` (1592 lines at this writing) and
`CodingAgentJobFactory` (1590) sit within ~10 lines of the 1600-line Checkstyle cap, so
adding a field + accessors crosses it. `FlowTreeApiEndpoint` (1577) has a little more
headroom — about two dozen lines — but the submit decode + echo pushes it the same way and
it should be treated as under the same pressure. Per
`flowtree/CLAUDE.md`, the refactor is **in scope for this change**, not a follow-up: extract
a cohesive collaborator the way `PhaseRunnerConfig`/`RestartGovernor`/`GitJobConfig` were
(a `MailboxDeliveryConfig` is the natural unit — it can also own the knobs in §9's open
questions). Do the extraction in the same change that adds the field.

**Validation stays at the boundary, not inside the runner.** Per the project rule against
silently correcting a caller inside shared infrastructure: if the flag is set for a job
whose resolved runner does not support streaming input, that mismatch is reported at
submission/launch (a warning and the flag treated as off for that phase), not papered over
deep in the runner.

---

## 5. Runner wiring (`ClaudeCodeRunner` + `AgentProcessRunner`)

### 5.1 Capability

Add `supportsStreamingInput` to `io.flowtree.jobs.agent.AgentCapabilities` (a new boolean
record component, with a back-compat constructor as the existing provider-axis addition
did). `ClaudeCodeRunner#capabilities()` returns `true`; `OpencodeRunner#capabilities()`
returns `false`. This is the single source of truth for the fallback decision in §8 and
`GET /api/agents` advertises it via `AgentCapabilities#toJson()`.

### 5.2 Command line

In `ClaudeCodeRunner#buildCommandLine`, when
`request.isDeliverMailboxToSession()` is true, add `--input-format stream-json`. The exact
interaction with the positional `-p <prompt>` (keep it, or send the prompt as the first
stdin object) is decided by Stage 1's findings; the plan's default assumption is to keep
`-p` for the initial prompt and send only *mailbox* turns on stdin, so the first-turn
behaviour is unchanged from today and only subsequent turns are new. Everything else about
the command line is unchanged, preserving the default-off byte-identical guarantee.

### 5.3 Stdin

`AgentProcessRunner#applyRequestToProcessBuilder` currently hardcodes stdin to
`/dev/null`. It must redirect stdin to a pipe only in the streaming case. Because
`applyRequestToProcessBuilder` is shared by both runners and the flag is runner-honoured,
the cleanest seam is: leave `applyRequestToProcessBuilder` setting `/dev/null`, and in the
streaming path have the runner request `ProcessBuilder.Redirect.PIPE` for stdin so
`Process#getOutputStream()` is the writable end. The tmux path
(`AgentProcessRunner#runInTmux`) does not expose a stdin pipe (tmux provides the pane's own
stdin and the builder's redirections are ignored) — so **streaming input and tmux are
mutually exclusive**; if both are requested, streaming wins and tmux is declined with a
warning (or vice-versa — an open question for the owner, §9). The direct path
(`runInProcess`) is the supported one.

### 5.4 Where the pump attaches

`AgentProcessRunner#runInProcess` already owns the process lifecycle: `pb.start()`, the
`AgentInactivityMonitor`, the `pumpOutput` read loop, and `process.waitFor()`. The input
pump is the symmetric counterpart on the write side and belongs here, started right after
`pb.start()` and stopped in the `finally` alongside the monitor. It needs:

- the process's stdin (`process.getOutputStream()`),
- the mailbox read coordinates (controller base URL, workstream id, bearer token, own
  sender identity `job:<taskId>`), passed in from the request the same way the activity
  tracker is,
- the same `lastOutputAt` clock the monitor reads — see §6.

To keep `AgentProcessRunner` from growing a second responsibility, the pump itself is a new
collaborator class (e.g. `MailboxStdinPump`) in `flowtree/agents`, constructed by the
runner and handed to `runInProcess` through a new overload, mirroring how
`AgentActivityTracker` and the loop-signature extractor are threaded in today.

The pump and `ConversationCatchUp` share the same mailbox-URL derivation and own-sender
filtering. Because `ConversationCatchUp` is in `flowtree/runtime` and the pump is in
`flowtree/agents` — and `runtime` depends on `agents`, never the reverse (§1, layering) —
that shared logic cannot be a method *on* `ConversationCatchUp`. Extract it into a helper
in `flowtree/base` (the module both already depend on, and where `JsonFieldExtractor`
lives), and have `ConversationCatchUp` and the pump both call it. If instead the owner
decides the pump should live in `flowtree/runtime`, the shared method can stay there; §9
records this as the placement decision.

---

## 6. Interaction with the inactivity monitor

`AgentInactivityMonitor` kills the process tree after `lastOutputAt` has not advanced for
`inactivityTimeoutMillis` (35 min for Claude; 60 min while an MCP call is in flight per
`IN_FLIGHT_MCP_CALL_MILLIS`). `lastOutputAt` is advanced only by
`AgentProcessRunner#pumpOutput` on each **stdout** line.

Two hazards, both flagged in prior design notes:

1. **An injected turn must not count as proof of life.** The pump writes to *stdin*; it
   must **not** touch `lastOutputAt`. Liveness is still defined entirely by the agent
   producing stdout. If we let a write reset the clock, a dead-but-not-exited agent being
   fed messages would look alive forever. So: the pump shares the clock only to *read* it
   (for the throttle below), never to advance it.

2. **A freshly injected turn deserves the agent some quiet thinking time.** When the agent
   receives a new user turn it may think before emitting its first stdout event. That gap
   is legitimate, like the MCP-in-flight grace. The clean way to express it without a new
   bespoke timer is to reuse the activity-tracker mechanism: treat "a user turn was just
   injected and the agent has not yet responded" as an in-flight state that
   `AgentInactivityMonitor#toleratedSilenceMillis` already lengthens the window for. That
   is an extension to `AgentActivityTracker` (a `userTurnInjected()` / cleared-on-next
   `assistant`-event signal) rather than a change to the monitor's policy. Decide the grace
   length in §9.

**Do not loosen the watchdog globally.** The 35-minute wall is what catches a wedged
subprocess; streaming input must not become a way for a hung session to live forever
because messages keep arriving. The grace is bounded and tied to an *expected* stdout
response, not to stdin activity.

---

## 7. Interaction with transcripts / output capture

Output capture is `request.getOutputCapturePath()`: `ClaudeCodeRunner#run` appends the raw
stdout to that file, and `AgentRunResult#rawOutput()` is the full stdout. The injected
turns go in on **stdin**, so they are **not** in the stdout capture — the transcript would
show the agent responding to a turn that is invisible in the file. Options, to decide with
the owner (§9):

- Rely on the fact that `--output-format stream-json` **echoes** each received user turn
  back as a `{"type":"user",...}` stdout event (UNVERIFIED — confirm in Stage 1); if so,
  the injected turns appear in the capture naturally and no extra work is needed.
- If the CLI does **not** echo injected user turns, the pump should also append a record of
  each injected turn to the capture file (clearly marked) so the transcript is complete and
  a later reader can see what the agent was reacting to.

The result parser is the other transcript concern. `ClaudeCodeRunner#parseClaudeNdjson`
calls `JsonFieldExtractor.extractLastJsonObject(jsonOutput, "result")` — it assumes a
**single** terminal `result` object. A streaming multi-turn session emits a `result` per
turn (§2, UNVERIFIED). Taking the *last* one is probably still correct for session-final
metrics, but whether the per-`result` cost/turns are cumulative or per-turn decides whether
"last" is right or whether they must be **summed**. Stage 1 measures this; Stage 4 adjusts
the parser accordingly (and adds a test with a multi-`result` fixture). The launch-time
budget ceilings (`--max-turns`, `--max-budget-usd`) are set once at launch and are not
re-evaluated per injected turn; a long multi-turn streaming session could blow through the
turn budget the job was sized for — see §9.

---

## 8. Fallback for runners without stream input

`OpencodeRunner` launches `opencode run --format json <prompt>` — a single positional
prompt, no streaming-stdin protocol, and `capabilities().supportsStreamingInput()` returns
`false`. The fallback is: **the flag is a no-op for a non-supporting runner.** Concretely:

- If a job's resolved runner for a phase (`CodingAgentJob#resolveRunner`) does not support
  streaming input, the `--input-format` flag and the stdin pump are not wired; the session
  launches exactly as it does today, with `/dev/null` stdin.
- Such a job keeps the existing **pull** collaboration: the agent calls `await_message`
  when it wants to receive, and `ConversationCatchUp` still injects missed messages at each
  relaunch. Nothing an operator relied on regresses.
- The mismatch (flag set, runner can't honour it) is surfaced once as a warning at launch
  (§4), so an operator who set the flag for an opencode phase learns it had no effect rather
  than silently believing mid-session delivery was active.

Because phases can use different runners (`PhaseRunnerConfig`), the decision is **per
phase**, taken from the resolved runner's capability, not once for the whole job.

---

## 9. Open questions for the owner

1. **Session termination.** With stdin held open the session will not exit on its own (§2);
   it ends at stdin EOF. What ends a mid-session-delivery job? Candidates: (a) the agent
   signals completion (e.g. a sentinel message / a tool call) and the pump closes stdin;
   (b) an idle timer — no new mailbox message and no agent output for N minutes closes
   stdin; (c) the existing turn/budget ceiling. This is the single biggest unknown and the
   feature cannot ship without a chosen answer.
2. **Turn/budget ceilings under streaming.** `--max-turns` and `--max-budget-usd` are sized
   for one task. Should a streaming session get a larger or refreshed ceiling, and who
   authorises the extra spend? A message that triggers 20 more turns of work was not in the
   job's original budget.
3. **Message filtering / authority.** Every job on a workstream posts status, phase, and
   fingerprint messages to the **same** mailbox (`MessageEndpointHandler` archives all of
   them). Unfiltered delivery would inject one job's own chatter and other jobs' chatter as
   user turns. The pump must deliver only messages that are (a) not the job's own
   (`exclude=job:<taskId>`, which `GET /mailbox` already supports) and (b) genuinely
   addressed to this session. What is the inclusion rule — all foreign human/peer messages,
   or only those carrying some marker? A user turn is a higher-authority channel than a tool
   result, and `MessageEndpointHandler#senderOf` accepts a **caller-supplied** sender, so an
   authority/trust rule is needed before arbitrary senders can inject user turns.
4. **Grace window for an injected turn** (§6): how long should the watchdog wait for the
   agent's first stdout after an injection before counting silence again? Reuse
   `IN_FLIGHT_MCP_CALL_MILLIS` (60 min) or a smaller dedicated value?
5. **tmux vs streaming** (§5.3): when both are requested, which wins? The plan assumes
   streaming wins and tmux is declined with a warning.
6. **Transcript completeness** (§7): is echo of injected turns in `--output-format
   stream-json` sufficient, or must the pump also write injected turns to the capture file?
7. **Delivery while the agent is mid-turn.** If a message arrives while the agent is in the
   middle of a turn (tool calls in flight), is it delivered immediately (queued on stdin for
   the CLI to consume at the next turn boundary) or held until the current turn's `result`?
   Stage 1's finding on when the CLI consumes a mid-turn stdin line decides this.
8. **Pump placement and the shared helper's module** (§1, §5.4, §12). The pump reuses
   `ConversationCatchUp`'s mailbox-URL derivation and own-sender filtering, but
   `ConversationCatchUp` is in `flowtree/runtime` while the pump is proposed for
   `flowtree/agents`, and `runtime` depends on `agents` (never the reverse). So the shared
   logic cannot be a method on `ConversationCatchUp`. Two ways to resolve it, for the owner
   to choose: (a) factor the shared logic into a `flowtree/base` helper that both
   `ConversationCatchUp` and the agents-side pump call (the plan's default — `flowtree/base`
   already hosts `JsonFieldExtractor`, which this feature reuses); or (b) place the pump in
   `flowtree/runtime` alongside `ConversationCatchUp` and keep the shared method there. The
   plan assumes (a).

---

## 10. Staged implementation, each with tests

Each stage is independently reviewable and leaves the tree green. Default-off means stages
1–2 are observable only under the flag, so nothing regresses mid-series.

### Stage 1 — Pin the stream-json input contract (no production code)

Purpose: replace every UNVERIFIED assumption in §2 with a measured fact before building on
it. Write a contract test / harness that launches the real `claude` binary with
`-p --input-format stream-json --output-format stream-json --verbose`, feeds it crafted
stdin (a well-formed user turn, a malformed line, an unknown `type`, then EOF), and records:
the accepted user-turn envelope; whether the session stays alive on open stdin and exits on
EOF; when a mid-turn stdin line is consumed; whether injected turns are echoed to stdout;
how many `result` events appear and whether their cost/turns are cumulative or per-turn;
behaviour on malformed input.

- **Tests:** a gated integration test (skipped when the binary is absent — a real
  environment gate, reported not silently skip-gated per the integrity rules) that asserts
  the measured protocol. Its findings are written back into §2 of this document and become
  the spec the later stages code to.
- **Exit criterion:** §2's unknowns are all answered, or the owner is told the binary cannot
  be run in CI and the protocol is pinned from the SDK docs with a tracking note.

### Stage 2 — Capability + flag plumbing (no behaviour yet)

Add `supportsStreamingInput` to `AgentCapabilities` (back-compat constructor;
`ClaudeCodeRunner` true, `OpencodeRunner` false). Add `deliverMailboxToSession` through the
full path in §4, including the collaborator extraction that keeps `CodingAgentJob` /
`CodingAgentJobFactory` / `FlowTreeApiEndpoint` under the file-length cap. No command-line or
stdin change yet.

- **Tests:** `GitManagedJobSerializationTest#roundTripFactory` asserts the flag survives
  factory round-trip and the codec; `McpToolDiscoveryTest` / `test_server.py` for the new
  submission parameter; a capability test asserting the two runners' values and that
  `AgentCapabilities#toJson()` includes the field; `AgentCapabilitiesTest` back-compat
  constructor test.
- **Exit criterion:** a flag set at submission is visible in the job record and on the
  `AgentRunRequest`, and changes nothing about how any session launches.

### Stage 3 — Command line + stdin pipe in the runner

In `ClaudeCodeRunner#buildCommandLine`, add `--input-format stream-json` under the flag. In
the runner's streaming path, wire stdin to a pipe (§5.3) instead of `/dev/null`, via a new
`AgentProcessRunner#runInProcess` overload that accepts the write-side consumer. No pump
yet — the pipe is opened and closed immediately (EOF) so a flagged session still terminates,
proving the plumbing without delivery.

- **Tests:** `ClaudeCodeRunnerTest` asserting `--input-format stream-json` appears iff the
  flag is set and is **absent** otherwise (the byte-identical default-off guarantee);
  `AgentProcessRunner` test (using the existing fake-agent fixture) asserting a flagged run
  gets a PIPE stdin and an unflagged run still gets `/dev/null`, and that EOF on the pipe
  lets the fake agent exit.
- **Note:** the end-to-end fake agent used by the agent-e2e test lives under the CI-file
  lock (`tools/ci/...`); any change it needs must land on a `ci/...` branch, separately from
  this feature branch. Keep Stage-3/5 unit tests on fixtures that are **not** under the lock.

### Stage 4 — The mailbox-to-stdin pump

Add `MailboxStdinPump` in `flowtree/agents`: a bounded long-poll loop against
`GET /mailbox?since=<cursor>&wait=<n>&exclude=job:<taskId>` (reusing the mailbox-URL
derivation and own-sender filtering, extracted into a `flowtree/base` helper that both
`ConversationCatchUp` and the pump call — the layering constraint from §1 and §5.4 means
the helper cannot be a method on `ConversationCatchUp` itself), writing each delivered
message to the session's stdin as a user-turn object in
the envelope Stage 1 confirmed. Start it after `pb.start()`, stop and close stdin in the
`finally`. Apply the §3.1 pull semantics and the §9.3 inclusion rule once the owner fixes it.
Adjust `parseClaudeNdjson` for multiple `result` events per Stage 1's cumulative/per-turn
finding, with a multi-`result` fixture test.

- **Tests:** a `MailboxStdinPump` unit test with a fake mailbox endpoint (a local HTTP stub,
  the same style as `ConversationCatchUp`'s injected `fetch`) asserting: a foreign message is
  written to stdin in the right envelope; the job's own messages are excluded; the cursor
  advances so nothing is delivered twice; the loop stops and closes stdin on shutdown. A
  `parseClaudeNdjson` test with a multi-`result` capture.
- **Exit criterion:** a message posted to the workstream while a flagged fake session runs is
  observed on that session's stdin, with no polling by the agent.

### Stage 5 — Inactivity monitor + transcript integration

Extend `AgentActivityTracker` with the injected-user-turn in-flight signal (§6) so
`AgentInactivityMonitor#toleratedSilenceMillis` lengthens the window after an injection until
the next `assistant` stdout event, and assert the pump never advances `lastOutputAt`. Handle
transcript completeness per §7 (echo-confirmed in Stage 1, or append injected turns to the
capture).

- **Tests:** `AgentInactivityMonitorTest` / `AgentActivityTrackerTest` asserting the window
  extends after an injected turn and resets when the agent responds, and that a stdin write
  alone does not reset the silence clock (a dead agent fed messages is still killed). A
  capture-completeness test per the §7 decision.

### Stage 6 — Fallback verification and docs

Assert the opencode fallback (§8): a flagged job on opencode launches unchanged and logs the
once-only mismatch warning; the pull model and `ConversationCatchUp` are untouched. Update
`flowtree/docs/architecture/AGENT_RUNNERS.md` and the `flowtree/CLAUDE.md` silence-budget
note to describe streaming input and its watchdog interaction.

- **Tests:** `CodingAgentJobDispatchTest` (or the per-phase runner-resolution test) asserting
  a non-supporting runner ignores the flag and warns once; a doc-free-but-reviewed change to
  the two docs.

---

## 11. Failure modes

- **Controller unreachable from the pump.** Reads fail like `ConversationCatchUp`'s
  best-effort HTTP (log and continue). No mailbox read means no delivery, but the session
  keeps running on its original prompt — degrade to the pull model's effective behaviour, do
  not kill the session.
- **Malformed / hostile message content.** A message body becomes a user turn; Stage 1 must
  confirm the CLI tolerates a line it cannot parse without exiting. The pump must also escape
  message text into valid JSON (reuse `JsonFieldExtractor.escapeJson`) so a message
  containing quotes/newlines cannot produce a broken stdin line.
- **Stdin write after the agent exited.** The agent may exit (turn/budget limit, crash) while
  the pump is mid-write; `getOutputStream().write` then throws `IOException`/broken pipe. The
  pump must treat that as "session gone", stop, and not surface it as a job error (mirror
  `AgentProcessRunner#logIoOrKill`'s benign-after-termination handling).
- **Watchdog false grace.** If the injected-turn grace (§6) is mis-scoped it could let a hung
  session live. Bound it and tie it to an expected stdout response, never to stdin activity.
- **Message flood.** A peer posting rapidly could enqueue many turns faster than the agent
  consumes them. The CLI's stdin buffer backpressures the pump's writes; the pump must not
  buffer unboundedly in Java. Consider a bounded in-flight count — but per the project's
  asynchronous-submission rule, do not invent a caller-side cap that duplicates backpressure
  the receiver already provides; prefer letting the blocking write throttle the pump.
- **Duplicate delivery across a relaunch.** A job killed and relaunched (`RestartGovernor`)
  restarts the pump. The launch-time `ConversationCatchUp` already quotes missed messages
  into the relaunch prompt; the pump's cursor must start **after** what catch-up covered so a
  message is not both quoted in the prompt and injected again. Coordinate the two cursors
  (both key off the job's own-sender cut), an integration detail for Stage 4.
- **Two jobs on one workstream.** Both pumps read the same mailbox; each must exclude its own
  sender and apply §9.3's inclusion rule so job A does not inject job B's status chatter as a
  user turn.

---

## 12. Summary of code touch-points (for the implementer)

Changed (production):
- `tools/mcp/manager/workstream_submit_tools.py` — submission flag.
- `io.flowtree.api.FlowTreeApiEndpoint` — submit decode + echo (+ extraction for length).
- `io.flowtree.jobs.CodingAgentJobFactory`, `CodingAgentJob`, `CodingAgentJobCodec` — flag
  state + wire (+ extraction for length).
- `io.flowtree.jobs.agent.AgentRunRequest` (+ `Builder`), `AgentCapabilities`,
  `AgentRunner`/`ClaudeCodeRunner`/`OpencodeRunner` — flag, capability, command line, stdin.
- `io.flowtree.jobs.AgentProcessRunner`, `AgentActivityTracker` — pump attach point, monitor
  signal.
- New: `MailboxStdinPump` (flowtree/agents) — the pump; shares mailbox-read/own-sender
  logic with `io.flowtree.jobs.ConversationCatchUp`. The shared logic goes into a new
  `flowtree/base` helper that both call, because `ConversationCatchUp` is in
  `flowtree/runtime` and `runtime` depends on `agents` (not the reverse), so the pump
  cannot reference `ConversationCatchUp` directly (§1, §5.4). Alternative: place the pump
  in `flowtree/runtime` — the owner's placement decision, §9.
- New (or extended): a `flowtree/base` helper holding the shared mailbox-URL derivation
  and own-sender filtering.


Unchanged by design: `WorkstreamMailbox`, `MailboxEndpointHandler`,
`MessageEndpointHandler`, `await_message` (`messaging_tools.py`) — routing stays pull-based.

Out of this branch's scope (needs a `ci/...` branch): any change to the agent-e2e fake agent
under `tools/ci/`.
