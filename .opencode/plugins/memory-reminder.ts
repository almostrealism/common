// .opencode/plugins/memory-reminder.ts
//
// opencode adapter for the "soft nudge to call memory_store" policy.
//
// The decision logic lives in the shared core
//   .claude/hooks/lib/memory_reminder_check.py
// which is the single source of truth. This plugin is the opencode
// counterpart to .claude/hooks/memory-reminder.sh: it pulls the
// tool name out of the opencode tool-call event, asks the shared
// core to decide, and translates the structured result into the
// opencode-native mechanism (mutate output.output to inject
// additionalContext on warn; never throw — this is a soft
// nudge).
//
// State persistence: opencode plugins are loaded once per `opencode`
// process and persist in memory, so we keep per-session state in a
// module-level Map<sessionID, SessionState>. Stale entries (sessions
// that have ended) are pruned opportunistically on every Nth call.
// The shared core is stateless — it accepts the current per-session
// state and returns the new state, which the adapter stores back in
// the Map. The .sh adapter and the .ts adapter agree on the shape
// of the state; only the persistence layer differs.
//
// Performance: we use a module-level cache for the core's Decision
// in a different way than the other Class A plugins. The memory
// reminder hook fires for EVERY tool call, not just `bash`. Caching
// the Decision across .before/.after for the same callID doesn't
// help much (we'd need to do the work in .before anyway, since the
// state is updated there). The one optimization is that we DO NOT
// run the core in .after at all — the state was updated in
// .before, and any warn context was already applied in .before's
// path (we mutate output.output in .before when warn fires). The
// .after handler is therefore a no-op for this hook, which is
// correct: there's nothing to do for the post-tool-use event that
// wasn't already done in pre-tool-use.
//
// Why mutate output.output in .before (rather than in .after as
// the other warn hooks do): the reminder is a nudge to the agent
// about the NEXT call it will make, not about the result of the
// CURRENT call. Surfacing it before the current tool's result is
// mixed in keeps the reminder in the model's near-term context.
// .after would put the reminder after the (often lengthy) tool
// output, where it's more likely to be ignored.
//
// See docs/plans/OPENCODE_HOOKS.md for the harness contract and
// docs/plans/MEMORY_REMINDER_HOOK.md for the design.

import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(
  HERE,
  "..",
  "..",
  ".claude",
  "hooks",
  "lib",
  "memory_reminder_check.py",
)

interface Decision {
  action: "allow" | "warn"
  reason: string
  context: string
  stderr: string
  new_state: SessionState | null
}

interface SessionState {
  calls_since_last_store: number
  last_store_ts: number
  last_remind_ts: number
  calls_at_last_remind: number
  session_start_ts: number
}

const DEFAULT_STATE: SessionState = {
  calls_since_last_store: 0,
  last_store_ts: 0,
  last_remind_ts: 0,
  calls_at_last_remind: 0,
  session_start_ts: 0,
}

// Module-level per-session state. The opencode plugin runner loads
// the plugin once at startup; the Map persists for the lifetime of
// the opencode process. Stale entries (sessions that ended without
// ever hitting the hook again) are pruned every PRUNE_EVERY calls.
const sessionStates = new Map<string, SessionState>()
let callCounter = 0
const PRUNE_EVERY = 200
const MAX_STALE_AGE_SECONDS = 24 * 60 * 60

/**
 * Call the shared core. Returns a Decision object. On any internal
 * error (Python missing, core crashed, bad JSON), returns an
 * "allow" Decision with `new_state: null` so a hook malfunction
 * can never block legitimate work.
 */
function callCore(tool: string, nowTs: number, state: SessionState): Decision {
  const result = spawnSync(
    "python3",
    [CORE, tool, String(nowTs), JSON.stringify(state)],
    { encoding: "utf-8", timeout: 5_000 },
  )

  if (result.error) {
    return {
      action: "allow",
      reason: "",
      context: "",
      stderr: `core spawn failed: ${result.error.message}`,
      new_state: null,
    }
  }
  if (result.status !== 0) {
    return {
      action: "allow",
      reason: "",
      context: "",
      stderr: `core exited ${result.status}: ${result.stderr}`,
      new_state: null,
    }
  }

  try {
    return JSON.parse(result.stdout) as Decision
  } catch (e) {
    return {
      action: "allow",
      reason: "",
      context: "",
      stderr: `core returned non-JSON: ${String(e)}`,
      new_state: null,
    }
  }
}

/**
 * Best-effort logging. If logging fails for any reason, we just
 * swallow it — a logging failure must never affect the policy
 * decision.
 */
function logDecision(decision: Decision, tool: string): void {
  if (!decision.stderr) return
  // eslint-disable-next-line no-console
  console.error(`[ar-hooks/memory-reminder] (${tool}) ${decision.stderr.split("\n", 1)[0]}`)
}

/**
 * Apply a Decision's new_state to the in-memory Map for the
 * session. A null new_state means "don't update" (the core
 * returned an allow without a state write, e.g. on internal
 * error or when the hook is disabled).
 */
function applyState(sessionID: string, newState: SessionState | null): void {
  if (newState === null) return
  sessionStates.set(sessionID, newState)
}

/**
 * Opportunistically prune stale session entries. We don't have a
 * timer in this hook (it fires on tool calls, not clock ticks), so
 * we run the prune every PRUNE_EVERY calls. An entry is "stale"
 * if its last_remind_ts is more than MAX_STALE_AGE_SECONDS in the
 * past AND the session hasn't stored a memory in the same window.
 * This is a heuristic — the worst case is that we keep a stale
 * entry around for one extra call, which is harmless.
 */
function pruneStale(now: number): void {
  for (const [sessionID, st] of sessionStates) {
    const lastTouch = Math.max(st.last_store_ts, st.last_remind_ts, st.session_start_ts)
    if (now - lastTouch > MAX_STALE_AGE_SECONDS) {
      sessionStates.delete(sessionID)
    }
  }
}

export const MemoryReminderPlugin: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      const tool = (input.tool || "").toString()
      if (!tool) return

      const sessionID = input.sessionID || "default"
      const now = Math.floor(Date.now() / 1000)

      callCounter += 1
      if (callCounter % PRUNE_EVERY === 0) {
        pruneStale(now)
      }

      const current = sessionStates.get(sessionID) ?? { ...DEFAULT_STATE }
      const decision = callCore(tool, now, current)
      logDecision(decision, tool)

      // Persist the new state regardless of action. The core always
      // returns a new_state on the allow path; the new_state is
      // null only on internal error (which we want to leave the
      // existing state untouched).
      applyState(sessionID, decision.new_state)

      if (decision.action === "warn") {
        // The reminder is for the NEXT call the agent will make, not
        // for the result of the current call. Mutating output.output
        // in .before mixes the reminder in with the (yet-to-be-
        // produced) tool output, which is fine: the model sees both
        // in the same turn and can act on the reminder.
        const note = decision.context || decision.stderr
        if (note) {
          const base = output.output ?? ""
          output.output = `${base}\n\n[ar-hooks/memory-reminder] ${note}`
        }
      }
    },
    // .after is intentionally a no-op. State was updated in
    // .before; warn context was applied in .before. There is
    // nothing to do for the post-event that wasn't already done.
    "tool.execute.after": async () => {
      // no-op
    },
  }
}
