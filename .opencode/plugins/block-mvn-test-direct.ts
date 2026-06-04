// .opencode/plugins/block-mvn-test-direct.ts
//
// opencode adapter for the "block direct `mvn test`" policy.
//
// The decision logic lives in the shared core
//   .claude/hooks/lib/mvn_test_check.py
// which is the single source of truth. This plugin is the opencode
// counterpart to .claude/hooks/block-mvn-test-direct.sh: it pulls the
// bash command out of the opencode tool-call event, asks the shared
// core to decide, and translates the structured result into the
// opencode-native mechanism (throw to block, mutate output.output to
// inject context).
//
// Performance: the .before and .after handlers run for the same call
// share a module-level Map<callID, Decision> cache. The .before handler
// populates the cache; the .after handler consumes-and-deletes it.
// This halves the per-call python3 subprocess count from 2 to 1 (a
// cold Python interpreter is 50–100ms, so this is ~50–100ms saved per
// Bash tool call when the warn path is hit). On any cache miss
// (e.g. .after ran without a matching .before, which can happen if
// .before's throw was somehow swallowed), the .after falls back to
// running the core again — fail-safe.
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
  "mvn_test_check.py",
)

interface Decision {
  action: "block" | "allow" | "warn"
  reason: string
  context: string
  stderr: string
}

// Module-level callID → Decision cache. Populated in tool.execute.before,
// consumed-and-deleted in tool.execute.after for the same callID. This
// avoids a second python3 subprocess spawn for the warn path, where
// .before and .after both need the Decision. The cache key (callID) is
// unique per opencode tool invocation, so there's no cross-contamination
// risk between concurrent tool calls.
//
// Why module-level (not closure-scoped): a closure would be a per-plugin-
// instance variable; module-level ensures all .before/.after pairs for
// the same Plugin object share the same Map. The plugin is loaded once
// at startup.
const decisionCache = new Map<string, Decision>()

/**
 * Call the shared core. Returns a Decision object. On any internal
 * error (Python missing, core crashed, bad JSON), returns an "allow"
 * Decision so a hook malfunction can never block legitimate work.
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
 * Best-effort logging. The plugin API gives us `client.app.log` so the
 * line shows up in opencode's structured log under the `ar-hooks`
 * service. If logging fails for any reason, we just swallow it — a
 * logging failure must never affect the policy decision.
 */
function logDecision(decision: Decision): void {
  if (!decision.stderr) return
  // eslint-disable-next-line no-console
  console.error(`[ar-hooks/block-mvn-test-direct] ${decision.stderr.trim()}`)
}

/**
 * Extract the bash command from a `bash` tool's args object. The
 * opencode `bash` tool's schema is `{ command: string, ... }` — same
 * field name as Claude Code, so no rename is needed. We tolerate any
 * shape and return an empty string if `command` is missing, which the
 * core treats as "allow".
 */
function extractBashCommand(args: unknown): string {
  if (!args || typeof args !== "object") return ""
  const obj = args as { command?: unknown }
  return typeof obj.command === "string" ? obj.command : ""
}

export const BlockMvnTestDirectPlugin: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      if (input.tool !== "bash") return

      const command = extractBashCommand(output.args)
      if (!command) return

      const decision = callCore(command)
      // Populate the cache so .after for the same callID can re-use
      // the Decision without a second subprocess spawn. We delete any
      // stale entry first (callID reuse is rare but possible if
      // opencode re-issues an ID after a long delay).
      decisionCache.set(input.callID, decision)
      logDecision(decision)

      if (decision.action === "block") {
        // opencode's plugin runner propagates thrown errors as the
        // tool's failure message — which is what the model sees as
        // the block reason. This matches Claude Code's "exit 2 with
        // the reason on stderr" semantics. We delete the cache entry
        // here because .after will not run for a tool that threw in
        // .before.
        decisionCache.delete(input.callID)
        throw new Error(decision.reason || decision.stderr)
      }
      // "warn" is deferred to the .after hook: the model should see
      // the warning alongside (not before) the (presumably empty)
      // tool output. The Decision is already in the cache.
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
            output.output = `${output.output}\n\n[ar-hooks] ${note}`
          }
        }
        return
      }

      // Slow path: no cache entry. This can happen if the plugin
      // was reloaded mid-call, or if .before's throw was swallowed
      // by some external handler. Re-run the core to stay correct.
      // (We do NOT throw on cache miss — that would block legitimate
      // work on a transient plugin-state issue.)
      const command = extractBashCommand(input.args)
      if (!command) return

      const decision = callCore(command)
      if (decision.action === "warn") {
        const note = decision.context || decision.stderr
        if (note) {
          output.output = `${output.output}\n\n[ar-hooks] ${note}`
        }
      }
    },
  }
}
