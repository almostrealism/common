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
    logDecision({ action: "allow", reason: "", context: "", stderr: `core spawn failed: ${result.error.message}` })
    return { action: "allow", reason: "", context: "", stderr: "" }
  }
  if (result.status !== 0) {
    logDecision({ action: "allow", reason: "", context: "", stderr: `core exited ${result.status}: ${result.stderr}` })
    return { action: "allow", reason: "", context: "", stderr: "" }
  }

  try {
    return JSON.parse(result.stdout) as Decision
  } catch (e) {
    logDecision({ action: "allow", reason: "", context: "", stderr: `core returned non-JSON: ${String(e)}` })
    return { action: "allow", reason: "", context: "", stderr: "" }
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
      logDecision(decision)

      if (decision.action === "block") {
        // opencode's plugin runner propagates thrown errors as the
        // tool's failure message — which is what the model sees as
        // the block reason. This matches Claude Code's "exit 2 with
        // the reason on stderr" semantics.
        throw new Error(decision.reason || decision.stderr)
      }
      // "warn" is deferred to the .after hook: the model should see
      // the warning alongside (not before) the (presumably empty)
      // tool output.
    },

    // TODO(review): callCore is called in both .before and .after; cache by
    // callID in a module-level Map to halve python3 subprocess cost per call.
    "tool.execute.after": async (input, output) => {
      if (input.tool !== "bash") return

      const command = extractBashCommand(input.args)
      if (!command) return

      const decision = callCore(command)

      if (decision.action === "warn") {
        // Append the warning to the tool's output so the model sees
        // it on its next turn. This matches Claude Code's
        // `additionalContext` semantics: the harness surfaces the
        // appended text to the model as a follow-up message.
        const note = decision.context || decision.stderr
        if (note) {
          output.output = `${output.output}\n\n[ar-hooks] ${note}`
        }
      }
    },
  }
}
