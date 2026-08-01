// .opencode/plugins/warn-mvn-build.ts
//
// opencode adapter for the "soft steer toward ar-build-validator"
// policy on artifact-producing Maven invocations (`mvn install`,
// `mvn compile`, `mvn package`, ...).
//
// The decision logic lives in the shared core
//   .claude/hooks/lib/mvn_build_check.py
// which is the single source of truth. This plugin is the opencode
// counterpart to the Claude staleness hook
// (.claude/hooks/mvn-artifact-staleness.py): both fire when an mvn
// invocation will (re-)build artifacts, but they render the steer
// differently for their host harness.
//
// Behavior:
//   - never throws (this is a warn hook, not a block hook)
//   - on "warn" the steer text is appended to the bash tool output
//     so the model sees it alongside the actual command result
//   - on "allow" the output is left exactly as the bash tool
//     produced it (no observable side effect on the agent)
//
// False-positive safety: the steer never modifies command flags
// and never blocks. `mvn install -DskipTests` invocations used for
// dependency seeding (e.g., to set up the local Maven repository
// before a downstream tool runs) still execute exactly as written
// and produce their normal output; the steer just appends a note
// reminding the agent that `ar-build-validator` exists for the
// "is the build clean?" case.
//
// Performance: same callID cache pattern as block-mvn-test-direct.ts
// so the python3 subprocess runs at most once per bash tool call.
//
// See docs/plans/OPENCODE_HOOKS.md for the architecture.

import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

// The shared core. Resolved relative to this file so the plugin works
// regardless of the cwd opencode was launched from.
const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(
  HERE,
  "..",
  "..",
  ".claude",
  "hooks",
  "lib",
  "mvn_build_check.py",
)

interface Decision {
  action: "warn" | "allow"
  reason: string
  context: string
  stderr: string
}

// Module-level callID → Decision cache. Same shape as block-mvn-test-direct.ts;
// see that file for the rationale.
const decisionCache = new Map<string, Decision>()

/**
 * Call the shared core. Returns a Decision object. On any internal
 * error (Python missing, core crashed, bad JSON), returns an "allow"
 * Decision so a hook malfunction can never affect legitimate work.
 */
function callCore(command: string): Decision {
  const result = spawnSync("python3", [CORE, command], {
    encoding: "utf-8",
    timeout: 5_000,
  })

  if (result.error) {
    return { action: "allow", reason: "", context: "", stderr: `core spawn failed: ${result.error.message}` }
  }
  if (result.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: `core exited ${result.status}: ${result.stderr}` }
  }

  try {
    return JSON.parse(result.stdout) as Decision
  } catch (e) {
    return { action: "allow", reason: "", context: "", stderr: `core returned non-JSON: ${String(e)}` }
  }
}

/**
 * Best-effort logging. If logging fails for any reason we just
 * swallow it — a logging failure must never affect the policy
 * decision.
 */
function logDecision(decision: Decision): void {
  if (!decision.stderr) return
  // eslint-disable-next-line no-console
  console.error(`[ar-hooks/warn-mvn-build] ${decision.stderr.trim()}`)
}

/**
 * Extract the bash command from a `bash` tool's args object.
 * Returns an empty string when ``command`` is missing — the core
 * treats that as "allow".
 */
function extractBashCommand(args: unknown): string {
  if (!args || typeof args !== "object") return ""
  const obj = args as { command?: unknown }
  return typeof obj.command === "string" ? obj.command : ""
}

export const WarnMvnBuildPlugin: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      if (input.tool !== "bash") return

      const command = extractBashCommand(output.args)
      if (!command) return

      const decision = callCore(command)
      // Populate the cache so .after for the same callID can re-use
      // the Decision without a second subprocess spawn.
      decisionCache.set(input.callID, decision)
      logDecision(decision)

      // This hook never blocks. The warn case is rendered in .after
      // so the model sees the note alongside the actual mvn output.
    },

    "tool.execute.after": async (input, output) => {
      if (input.tool !== "bash") return

      const cached = decisionCache.get(input.callID)
      if (cached !== undefined) {
        // Fast path: .before ran for this callID and we have its
        // Decision cached. Consume-and-delete so the Map doesn't
        // grow without bound.
        decisionCache.delete(input.callID)
        if (cached.action === "warn") {
          const note = cached.context || cached.stderr
          if (note) {
            output.output = `${output.output}\n\n[ar-hooks/warn-mvn-build] ${note}`
          }
        }
        return
      }

      // Slow path: no cache entry. Re-run the core to stay correct.
      const command = extractBashCommand(input.args)
      if (!command) return

      const decision = callCore(command)
      if (decision.action === "warn") {
        const note = decision.context || decision.stderr
        if (note) {
          output.output = `${output.output}\n\n[ar-hooks/warn-mvn-build] ${note}`
        }
      }
    },
  }
}
