# FlowTree Development Guidelines

---

# THE JOB IS THE SOURCE OF TRUTH FOR THE WORKLOAD

**Every property that describes what a job does belongs on `CodingAgentJob` and
`CodingAgentJobFactory`, travels over the wire, and is visible in the job
record. There is no acceptable reason to leave one out.**

A FlowTree job is configured on the controller and executed on another machine,
possibly days later, possibly by a different agent picking up where the first
left off. The job and its factory are the only durable description of what that
workload is. A property that lives anywhere else — inlined into the prompt text
by a submitting tool, inferred from a string, held only in the submitter's head
— is invisible to:

- the worker that has to decide how to run the job,
- the controller's job record and `/api/workstreams/{id}/jobs` listing,
- every later job that needs to know what the earlier one was,
- anyone debugging why a run behaved the way it did.

## The rule

When you add a capability that changes what a job *is*, thread it through the
whole path. All of it, in the same change:

| Layer | File | What to add |
|---|---|---|
| Submission tool | `tools/mcp/manager/workstream_submit_tools.py` | parameter, docstring entry, `payload["<name>"]` |
| HTTP submit | `io.flowtree.api.FlowTreeApiEndpoint#handleSubmit` | `extractJsonHasField` → `factory.set…` |
| Job record | `io.flowtree.api.FlowTreeApiEndpoint` submit response JSON | the field, so a caller can confirm what it got |
| Factory | `io.flowtree.jobs.CodingAgentJobFactory` | field, accessors that call `set(...)`, decode case |
| Factory → job | `io.flowtree.jobs.CodingAgentJobConfigurer` | `job.set…(factory.is…())` |
| Job | `io.flowtree.jobs.CodingAgentJob` | field, accessors |
| Wire | `io.flowtree.jobs.CodingAgentJobCodec` | encode branch, decode case |
| Behaviour | wherever the property takes effect | e.g. `InstructionPromptBuilder` |

Then prove it travels: a test that round-trips the factory through
`GitManagedJobSerializationTest.roundTripFactory` and asserts the property
survived. A flag that does not survive serialization does nothing at all, and
nothing else in the pipeline will tell you so.

## "The file is near its length cap" is not a reason

`CodingAgentJob`, `CodingAgentJobFactory`, and `FlowTreeApiEndpoint` all run
close to the 1600-line Checkstyle `FileLength` limit. That pressure is real and
it is *not* an argument for putting a job property somewhere else. It is an
argument for refactoring, and **that refactoring is part of the scope of
whatever change needed the room** — not a follow-up, not someone else's
problem, and never a reason to compress javadoc until the field fits.

Both classes are already built for this. Cohesive groups of state have been
extracted into collaborators (`PhaseRunnerConfig`, `RestartGovernor`,
`JobSessionAccumulator`, `GitJobConfig`, `CodingAgentJobConfigurer`) with the
owning class delegating to them and its public API unchanged. Find the next
cohesive group and do the same. If the factory and the job hold two
implementations of the same idea, one collaborator shared by both removes
duplication and length together — `PhaseRunnerConfig`, which serves the job and
the factory through an optional property sink, is the worked example.

## The failure this prevents

A submitting tool that appends instructions to the prompt string instead of
setting a property produces a job that behaves collaboratively but *records*
nothing. Nothing downstream can filter for it, resume it, reason about its
timeouts, or even report what it was. The behaviour appears to work, which is
what makes the shortcut attractive and what makes it expensive later.

---

# WHERE THINGS LIVE

See the module map in the repository-root `CLAUDE.md` for the layer graph, and
`.github/CLAUDE.md` before touching CI. Within flowtree:

- `api/`, `base/`, `graphpersist/` — protocol, shared helpers, persistence.
- `agents/` — `AgentRunner` and its implementations (`ClaudeCodeRunner`,
  `OpencodeRunner`), subprocess management, the inactivity watchdog.
- `runtime/` — controller, jobs, `NodeGroup`, Slack, the HTTP API.

`flowtree/runtime/docs/` is formal documentation only. Plans and investigation
notes go in `docs/plans/` at the repository root.

---

# AGENT SESSIONS HAVE A SILENCE BUDGET

`AgentInactivityMonitor` destroys an agent's whole process tree after
`AgentRunner.DEFAULT_INACTIVITY_TIMEOUT_MILLIS` (35 minutes; `OpencodeRunner`
overrides to 45) of **stdout silence** — not of total runtime. Anything that
makes an agent quiet for a long stretch has to account for it.

What counts as output depends on the runner's output format, and the Claude
CLI is unforgiving here: `--output-format json` writes **one line when the
session ends and nothing before it**, which turns the silence budget into a
hard wall clock. `ClaudeCodeRunner` therefore asks for `stream-json`, where
every event — the agent's turns, each `tool_use`, each `tool_result` — is a
line. Do not switch it back to `json` to make the capture file smaller.

A blocking MCP tool call still emits nothing while it blocks. The runner reads
the event stream (`ClaudeCodeRunner.classifyActivity`) into an
`AgentActivityTracker`, and while an `mcp__*` call is open the monitor
tolerates `AgentInactivityMonitor.IN_FLIGHT_MCP_CALL_MILLIS` (60 minutes) of
silence instead of the configured window. A `Bash` call earns no such grace —
an unterminated shell loop is the hang the monitor exists to catch. A tool
that waits should still bound its wait: `await_message` composes a long wait
out of short controller long-polls, returns `timed_out=true` rather than
blocking indefinitely, and its prompt protocol tells the agent to call it
again. Returning is what proves the session is alive; the grace is what keeps
one long legitimate call from being mistaken for a hang.
