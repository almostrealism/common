// .opencode/plugins/steer-generated-code-read.ts
//
// opencode adapter for the "steer raw reads of generated compiler output to
// the ar-profile-analyzer MCP tool" policy. Mirrors
// .opencode/plugins/steer-ls-grep.ts in shape: pull the bash command out of
// the opencode tool-call event, ask the shared core to decide, and translate
// the structured result into the opencode-native mechanism (throw to block).
//
// The decision logic lives in the shared core
//   .claude/hooks/lib/steer_generated_code_check.py
// which is the single source of truth for this policy across both Claude Code
// (.claude/hooks/steer-generated-code-read.sh) and opencode (this file).
//
// This hook is a HARD BLOCK on the substitutable case: a read/search/list
// command (cat, grep, find, ls, sed, ...) whose target is a generated
// artifact — instruction-set dumps, *.metal/*.cl kernels, or OperationProfile
// XML. Generated output must be inspected via the ar-profile-analyzer tool
// (list_profiles -> search_operations -> get_source/get_source_summary), which
// preserves the operation-node -> source mapping and timing/structural
// context that a raw grep/cat discards. Build/run commands and source reads
// under src/ pass through (the core's `decide()` returns "allow").
//
// See docs/plans/OPENCODE_HOOKS.md for the harness contract.

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
  "steer_generated_code_check.py",
)

interface Decision {
  action: "block" | "allow"
  reason: string
  context: string
  stderr: string
}

/**
 * Call the shared core. Returns a Decision object. On any internal error
 * (Python missing, core crashed, bad JSON), returns an "allow" Decision so a
 * hook malfunction can never block legitimate work.
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
 * Best-effort logging. A logging failure must never affect the policy decision.
 */
function logDecision(decision: Decision, command: string): void {
  if (!decision.stderr) return
  // eslint-disable-next-line no-console
  console.error(`[ar-hooks/steer-generated-code-read] (${decision.action}) ${command}`)
}

/**
 * Extract the bash command from a `bash` tool's args object. The opencode
 * `bash` tool's schema is `{ command: string, ... }` — same field name as
 * Claude Code. We tolerate any shape and return an empty string if `command`
 * is missing, which the core treats as "allow".
 */
function extractBashCommand(args: unknown): string {
  if (!args || typeof args !== "object") return ""
  const obj = args as { command?: unknown }
  return typeof obj.command === "string" ? obj.command : ""
}

export const SteerGeneratedCodeReadPlugin: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      if (input.tool !== "bash") return

      const command = extractBashCommand(output.args)
      if (!command) return

      const decision = callCore(command)
      logDecision(decision, command)

      if (decision.action === "block") {
        // opencode propagates thrown errors as the tool's failure message —
        // what the model sees as the block reason. Matches Claude Code's
        // "exit 2 with the reason on stderr" semantics.
        throw new Error(decision.reason || decision.stderr)
      }
    },
  }
}
