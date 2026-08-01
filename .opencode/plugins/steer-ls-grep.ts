// .opencode/plugins/steer-ls-grep.ts
//
// opencode adapter for the "steer bash `ls`/`grep` to structured tools"
// policy. Mirrors .opencode/plugins/block-mvn-test-direct.ts in
// shape: pull the bash command out of the opencode tool-call event,
// ask the shared core to decide, and translate the structured
// result into the opencode-native mechanism (throw to block).
//
// The decision logic lives in the shared core
//   .claude/hooks/lib/steer_ls_grep_check.py
// which is the single source of truth for this policy across both
// Claude Code (.claude/hooks/steer-ls-grep.sh) and opencode
// (this file).
//
// This hook is a HARD BLOCK on simple substitutable uses (bare
// `ls <path>`, `grep <pattern> <file>`, etc.). It deliberately
// does NOT use a soft-warn path: retros repeatedly show that
// soft warns do not change behavior. Agents know the structured
// tools exist and still reach for `bash ls` unless blocked.
//
// Compound shell features (pipes, xargs, perl-regex, output-format
// flags) are allowed through — the structured tools cannot express
// them. The core's `decide()` function implements the full policy
// (it returns "allow" for compound commands); this adapter is a
// thin renderer that translates the decision into opencode's
// throw/allow contract.
//
// Performance: the shared core is a single python3 subprocess
// spawn per bash tool call. The decision is a pure function of
// the command string, so no caching is needed. (The block path
// exits before the bash tool runs, so a future .after handler
// is not required — there is no tool output to mutate.)
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
  "steer_ls_grep_check.py",
)

interface Decision {
  action: "block" | "allow"
  reason: string
  context: string
  stderr: string
}

/**
 * Call the shared core. Returns a Decision object. On any internal
 * error (Python missing, core crashed, bad JSON), returns an
 * "allow" Decision so a hook malfunction can never block legitimate
 * work.
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
 * Best-effort logging. If logging fails for any reason, we just
 * swallow it — a logging failure must never affect the policy
 * decision.
 */
function logDecision(decision: Decision, command: string): void {
  if (!decision.stderr) return
  // eslint-disable-next-line no-console
  console.error(`[ar-hooks/steer-ls-grep] (${decision.action}) ${command}`)
}

/**
 * Extract the bash command from a `bash` tool's args object. The
 * opencode `bash` tool's schema is `{ command: string, ... }` —
 * same field name as Claude Code, so no rename is needed. We
 * tolerate any shape and return an empty string if `command` is
 * missing, which the core treats as "allow".
 */
function extractBashCommand(args: unknown): string {
  if (!args || typeof args !== "object") return ""
  const obj = args as { command?: unknown }
  return typeof obj.command === "string" ? obj.command : ""
}

export const SteerLsGrepPlugin: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      if (input.tool !== "bash") return

      const command = extractBashCommand(output.args)
      if (!command) return

      const decision = callCore(command)
      logDecision(decision, command)

      if (decision.action === "block") {
        // opencode's plugin runner propagates thrown errors as the
        // tool's failure message — which is what the model sees as
        // the block reason. This matches Claude Code's "exit 2 with
        // the reason on stderr" semantics.
        throw new Error(decision.reason || decision.stderr)
      }
    },
  }
}
