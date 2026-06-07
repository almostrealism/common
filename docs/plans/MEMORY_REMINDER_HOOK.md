# Memory Reminder Hook — Periodic Nudge to Store Findings

**Status:** design + implementation
**Branch:** `feature/opencode-hooks`
**Author:** coding agent session for job 5cfe08f6

---

## 0. Why this document exists

Coding-agent sessions drift. An agent reads a file, edits a file, runs a
test, edits another file, runs another test — and after 30 tool calls
the model still has not called `mcp__ar-manager__memory_store` once.
When the session ends, the only durable record of "what did this agent
*learn*" is what's in the diff and the transcript; the cross-session
narrative (why a path was abandoned, what was decided, which
hypothesis was ruled out, what the consultant said that steered the
design) is gone.

We have a self-improvement phase now (`docs/plans/SELF_IMPROVEMENT_PHASE.md`,
`mcp__ar-manager__workstream_context`) that *analyzes* transcripts after
the fact. That phase can detect the gap. But the cost of letting the
gap happen at all is real: it costs the agent (and the project) a
session's worth of recoverable context, and a retrospective can only
work with what the transcript still contains.

This hook is the preventive complement: it nudges the agent to store
a memory *during* the session, before context loss becomes
irrecoverable. The design below weighs the design axes and picks
explicit defaults.

The hook follows the same shared-core + thin-per-harness-adapter
pattern as every other recent policy in this repo
(`docs/plans/OPENCODE_HOOKS.md`).

---

## 1. The problem to solve

Agents go long stretches without calling `memory_store` (or the
`ar-consultant` analogue `remember`). The retrospective phase can
post-hoc identify the gap, but a mid-session nudge is cheaper, more
recoverable, and aligns with the project's CLAUDE.md rule
"checkpoint your design decisions as memories as you go" — currently
a recommendation, with no mechanical reinforcement.

The hook is **soft-inject only** (never blocks). Blocking would be
the wrong force level for a quality nudge: the cost of
*unintended blocking* (the agent is mid-test-run, must go store
something, comes back) outweighs the cost of *occasional non-firing*
(an agent that just doesn't get reminded this session).

---

## 2. Design axes and trade-offs

### 2.1 Trigger metric — what does "recently" mean?

Four candidates:

**(a) Tool-call count since last `memory_store`.**
Simple, deterministic, easy to test. But conflated: 20 trivial reads is
not the same risk as 20 substantive edits. A purely count-based
threshold over-prompts on read-heavy sessions and under-prompts on
write-heavy sessions.

**(b) Wall-clock time since last `memory_store`.**
Aligns with the felt "long silent stretch." But hooks fire on tool
calls, not on a timer — so the time check only happens *when a tool
runs*, which means a quiet session that hasn't done anything in 20
minutes produces no event and no reminder. That's actually correct
(a quiet session isn't losing work), but the wall-clock signal is
only useful in combination with another.

**(c) Count of *significant* actions** (edits / writes / test runs /
commits) since last store.
Most meaningful signal: a session with 0 edits in 30 minutes and 0
memories stored is fine; a session with 8 edits and 0 memories is
the problem we're trying to catch. Cost: the "is this significant?"
classifier has to be maintained, and getting it wrong means either
over-prompting on read-heavy sessions or under-prompting on
mixed-mode sessions.

**(d) Hybrid** — significant-action count as the primary signal,
with a wall-clock floor as a backstop for long, slow sessions.

**Recommended: (d), with a simple significance classifier.** The
"significance" classifier is just "non-read tool name" (anything in
{`Bash`, `Edit`, `Write`, `MultiEdit`, `NotebookEdit`} for Claude
Code; `{bash, write, edit, apply_patch}` for opencode). Reads and
lookups don't count. This is a low-risk classifier — the false
positive direction (over-counting) is harmless because the threshold
default is generous (15), and the false negative direction
(under-counting, missing a real edit) is closed by the wall-clock
floor (20 minutes).

The wall-clock floor's job is to catch the case where an agent does
just a few edits, slowly, over a long stretch — the count signal
won't fire because the threshold isn't hit, but the agent is
nonetheless past the point where it should have checkpointed.

### 2.2 State tracking across tool calls — the cross-harness asymmetry

This is the hard part of the design. Two harnesses, two state models:

- **opencode `.ts` plugins** are loaded once per `opencode` process
  and persist in memory. A module-level `Map<sessionID, SessionState>`
  is the natural state container. A session ID is available on every
  event (`input.sessionID`); multiple sessions can be live
  concurrently and their state is namespaced by the map key.

- **Claude Code `.sh` hooks** are fresh shell subprocesses per
  invocation. Module-level state is impossible. The only way to
  persist state across invocations is on disk, keyed by session ID.
  The session ID is available in the hook payload as
  `payload.session_id`.

The **shared core must be stateless**. It accepts the current state
and the tool event, returns a `Decision`, and lets the caller update
its persisted state. The core's return shape is the established one:

```json
{
  "action":  "allow" | "warn",
  "reason":  "string",
  "context": "string",     // injected to the model on warn
  "stderr":  "string",     // printed to stderr for the human
  "new_state": {...}       // the state to persist for the next call
}
```

(Compare `mvn_test_check.py`'s `Decision` shape; the only addition is
`new_state`. The adapters are responsible for storing that state
in their harness-appropriate location. On any internal error the
core returns `action: "allow"` and `new_state: null` — the adapter
keeps its existing state, never blocks on a hook malfunction.)

This is the same cross-harness split the planning document
(`OPENCODE_HOOKS.md` §3.3) calls out: shared core is harness-neutral,
adapters handle the harness-specific persistence.

The Claude Code state file path follows the established
`/tmp/.ar_…_${USER}.json` pattern used by `enforce-consultant-first.sh`
and `track-consultant-call.sh`. The file is a JSON dict keyed by
session ID; values are the per-session state. Concurrent sessions
are safe (each adapter reads-then-writes its own session's entry).

The opencode plugin state is a `Map<sessionID, SessionState>` held at
module scope. Stale entries (sessions that have ended) are pruned
on every Nth call (counter, not a timer — the hook fires on tool
calls, not clock ticks).

### 2.3 Detecting `memory_store` and resetting

The two harnesses name the tool identically: `mcp__ar-manager__memory_store`.
The `ar-consultant` analogue is `mcp__ar-consultant__remember`. Both
produce durable memory entries; both should reset the "since last
store" counters.

`mcp__ar-manager__memory_recall` and `mcp__ar-consultant__recall` /
`consult` do **not** reset — they don't store anything new, they
read. The motivation is to checkpoint *stored* findings, not
*consulted* findings (a recall is itself a check of the consultant /
memory store, and is observable in the transcript even without a
memory entry).

`mcp__ar-consultant__start_consultation` /
`mcp__ar-consultant__end_consultation` / and other read-side tools
also do not reset.

Detection is by exact `tool_name` match. We do not use prefix or
substring matching — a hypothetical future tool
`memory_store_bulk_search_result` (a read tool) should not reset.

### 2.4 Reminder delivery and forcefulness

Three candidate delivery mechanisms (per the existing catalog):

- **stderr box** (used by `warn-assertion-free-test.sh`): loud,
  human-visible, but not visible to the model unless it appears in
  the harness's `additionalContext` flow.
- **JSON `additionalContext` to stdout** (used by
  `file-length-advisory.sh`, `mvn-artifact-staleness.py`): the model
  sees the text next turn. The right channel for a steering nudge.
- **throw Error in opencode** (block, used by `block-mvn-test-direct.ts`):
  wrong — the spec is soft-inject.

**Recommended: JSON `additionalContext` to stdout for Claude Code
(exit 0); mutate `output.output` in `tool.execute.after` for opencode.**
This matches the existing `mvn-build-check` and `file-length-advisory`
pattern. The reminder is short (a single paragraph), so a stderr box
is not needed for emphasis; the `additionalContext` is the visible
artifact.

**Forcefulness — preventing nagging.** A reminder that fires on
*every* tool call after threshold is just noise. After firing, the
hook re-arms only when EITHER:
- `N_backoff` additional side-effect calls accumulate (default 8), OR
- `M_backoff` wall-clock minutes pass without a store (default 10).

This gives the model time to act on the reminder. A second reminder
in rapid succession would just teach the model to ignore the hook
("oh, that thing again — not actionable"). The backoff defaults are
deliberately generous; they're tuned to *escalate* only when the
agent is clearly not responding.

**Don't fire right after a store.** A `COOLDOWN_CALLS` of 3
side-effect calls after a `memory_store` silences the hook — this
gives the model room to *do the work it just remembered* without
being asked to remember again immediately.

**Don't fire in the first few tool calls.** A `WARMUP_CALLS` of 5
side-effect calls silences the hook at session start. The first
session-burst is almost always exploration; we want the reminder to
hit mid-session, not "you haven't done this in the first 30
seconds."

### 2.5 Tuning / configurability

All tuning is env-var based. No on-disk config file in v1 (keep
the surface area small; revisit if tuning needs escape from
"per-harness env var"):

| Env var | Default | Meaning |
|---|---|---|
| `AR_MEMORY_REMIND_DISABLED=1` | unset | Disables the hook entirely. |
| `AR_MEMORY_REMIND_CALLS_THRESHOLD=15` | 15 | Side-effect calls since last store before firing. |
| `AR_MEMORY_REMIND_SECONDS_THRESHOLD=1200` | 1200 (20 min) | Wall-clock seconds since last store before firing. |
| `AR_MEMORY_REMIND_BACKOFF_CALLS=8` | 8 | Additional side-effect calls after a reminder before re-firing. |
| `AR_MEMORY_REMIND_BACKOFF_SECONDS=600` | 600 (10 min) | Additional wall-clock seconds after a reminder before re-firing. |
| `AR_MEMORY_REMIND_WARMUP_CALLS=5` | 5 | Side-effect calls at session start before the hook can fire. |
| `AR_MEMORY_REMIND_COOLDOWN_CALLS=3` | 3 | Side-effect calls after a `memory_store` during which the hook is silent. |

The defaults are deliberately skewed to "fires later rather than
sooner." The cost of a missed nudge is "agent loses some recoverable
context"; the cost of an over-nudge is "agent learns to ignore the
hook." The asymmetry favors the latter.

### 2.6 Failure modes / non-goals

**Failsafe behaviors:**
- On any internal error in the core (Python crash, bad JSON, etc.),
  return `action: "allow"`. A hook malfunction must never block
  legitimate work.
- On a missing or malformed state file (Claude Code), treat the
  session as fresh-start.
- On a `Map` cache miss (opencode), default to fresh-start state.

**Out of scope for v1:**
- **Phase awareness.** The hook does not know whether the agent is
  in the primary phase, a review phase, a corrective phase, or a
  retrospective. In phases where memory storage is meaningless (e.g.,
  a review pass that only reads code), the hook will still fire on
  long enough dry spells. This is a *feature*, not a bug, in v1: a
  review pass that runs for 20 minutes without checkpointing
  findings is also a regression risk.
- **Semantic analysis of what's been done.** The hook is purely
  count + time based. It does not parse tool arguments to see
  whether the agent just wrote a comment containing
  "TODO: store this" (an existing practice that suggests the agent
  *knows* it should store). Adding that signal in v2 might reduce
  false-positive over-nudging.
- **Blocking `memory_store` is not a goal.** The hook is purely
  advisory.
- **Cross-harness state sharing.** If a single human runs both
  harnesses back-to-back, the opencode in-memory state and the
  Claude Code on-disk state are independent. This is correct:
  each harness has its own session ID space.
- **Coordination with the `retrospective` phase.** The retrospective
  phase *analyzes* transcripts after the fact. The reminder hook
  *produces* better transcripts by mid-session nudging. They are
  complementary; we do not need to coordinate them.

---

## 3. Recommended design — summary

| Axis | Choice |
|---|---|
| Trigger metric | Hybrid: count of side-effect tool calls (Bash/Edit/Write/…) + wall-clock time, whichever first. |
| State model | Stateless shared core. Per-harness state: opencode in-memory `Map<sessionID, …>`; Claude Code on-disk JSON in `/tmp/.ar_memory_state_${USER}.json`. |
| `memory_store` detection | Exact match on `mcp__ar-manager__memory_store` or `mcp__ar-consultant__remember`. `memory_recall` and `consult` do **not** reset. |
| Delivery | Soft-inject (JSON `additionalContext` for Claude Code; `output.output` mutation for opencode). |
| Forcefulness | Soft, with backoff (8 calls or 10 minutes between reminders), warmup (5 calls at start), cooldown (3 calls after a store). |
| Tuning | Env vars (see §2.5). Defaults favor under-nudging. |
| Failure mode | Allow on any internal error. |
| Out of scope | Phase awareness, semantic analysis, blocking, cross-harness state, retrospective coordination. |

The implementation follows the established pattern:

- `.claude/hooks/lib/memory_reminder_check.py` — shared core.
  `decide(tool_event, current_state) -> Decision`. CLI: `--stdin` for
  the .sh adapter; argv for the .ts adapter.
- `.claude/hooks/memory-reminder.sh` — thin shell wrapper.
  On-disk state, calls the core.
- `.opencode/plugins/memory-reminder.ts` — opencode adapter.
  Module-level state, registered in `.opencode/opencode.json`.

---

## 4. Plan for implementation

The implementation lands in one commit, three logical pieces:

1. **Shared core** (`.claude/hooks/lib/memory_reminder_check.py`) +
   unit tests (`test_memory_reminder_check.py`).
2. **Claude Code adapter** (`.claude/hooks/memory-reminder.sh`) +
   wiring in `.claude/settings.json` (PreToolUse and PostToolUse
   entries, no tool_name matcher so the hook fires for *every* tool
   call). State on disk in `/tmp/.ar_memory_state_${USER}.json`.
3. **opencode adapter** (`.opencode/plugins/memory-reminder.ts`).
   State in module-level `Map<sessionID, …>`. Registered in
   `.opencode/opencode.json` and `tsconfig.json` `include` list.
   Smoke tests follow the existing
   `.opencode/plugins/__tests__/<plugin>.smoke.cjs` pattern.

### 4.1 Performance: don't spawn a subprocess per output field

The PoC lessons from `block-mvn-test-direct.ts` apply: cache the
core's `Decision` per callID in a module-level `Map`, so the
`tool.execute.before` and `tool.execute.after` paths for the same
tool call share one python3 subprocess invocation, not two. The
state-update happens once per call, also in `tool.execute.before`
(the state is needed to evaluate the call, and writing it in
`before` is the natural place — the call hasn't happened yet, so we
pre-increment then decide; or, equivalently, we decide based on the
state as of the previous call and write the new state in `after`;
either works as long as the increment and decision use the same
state snapshot). We choose: state in `before` only, `.after` is a
no-op for the in-memory adapter except for warn-context
application. This keeps `.after` cheap.

For Claude Code, the .sh hook fires for every tool call. A 50ms
python3 startup per call adds up over a long session. The state
file is read once at the top of the .sh and the new state is
written at the end. This is unavoidable for the .sh side (no
module-level cache is possible), but the python3 process is the
only overhead — there is no second invocation per call (unlike
the opencode `.before`+`.after` pair).

### 4.2 Tests

- **Shared core unit tests** (`test_memory_reminder_check.py`):
  - Reset on `memory_store` (counters zero, last_store_ts updated).
  - Reset on `consultant__remember` (same).
  - No reset on `memory_recall` / `consult` / `read` / `grep`.
  - Fire after `CALLS_THRESHOLD` side-effect calls.
  - Fire after `SECONDS_THRESHOLD` wall-clock even with low count.
  - Don't fire in the warmup window.
  - Don't fire in the cooldown window after a store.
  - Backoff: don't re-fire on the immediate next call; re-fire
    after `BACKOFF_CALLS` more calls.
  - Disabled via env var → never fires.
  - All side-effect tool types counted; reads not counted.
  - CLI: `--stdin` mode renders natively (JSON
    `additionalContext`); argv mode returns JSON Decision.
- **opencode adapter tests**
  (`.opencode/plugins/__tests__/memory-reminder.smoke.cjs` and
  `.test.ts`):
  - Module-level `Map` state is shared across .before calls
    (sessionID-keyed).
  - Two different `sessionID`s have independent state.
  - A `memory_store` tool call resets state and does not fire.
  - A long stretch of side-effect calls without a store fires the
    reminder.
  - Backoff: after firing, the next call does not re-fire.
- **Claude Code adapter tests** (Python, in the same test file as
  the core): drive the `--stdin` CLI with a synthetic payload,
  verify exit 0 + JSON `additionalContext` for the warn case and
  exit 0 + empty stdout for the allow case. Verify on-disk state
  file is written and re-read on a second invocation (state
  persists across `.sh` invocations).

### 4.3 Verification

- `python3 -m unittest .claude/hooks/lib/test_memory_reminder_check.py -v` passes.
- `node .opencode/plugins/__tests__/memory-reminder.smoke.cjs` passes.
- `npx tsx .opencode/plugins/__tests__/memory-reminder.test.ts` passes.
- `mvn install -DskipTests` from the repo root succeeds (the hook
  files are not Java, but the standard compile-check pass
  confirms nothing else broke).
- `mcp__ar-build-validator__start_validation` returns clean
  (checkstyle and code_policy do not apply to .sh / .py / .ts
  files; we run it to be sure no Java was accidentally touched).
