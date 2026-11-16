import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

import { type Decision } from "./_lib/command_pattern"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(HERE, "..", "..", ".claude", "hooks", "lib", "branch_upstream_check.py")

/** Ask the shared core whether this tool call may run; allow on any core failure. */
function decide(tool: string, command: string): Decision {
  const result = spawnSync("python3", [CORE, tool, command, process.cwd()], {
    encoding: "utf-8",
    timeout: 5_000,
  })
  if (result.error || result.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: "" }
  }
  try {
    return JSON.parse(result.stdout) as Decision
  } catch {
    return { action: "allow", reason: "", context: "", stderr: "" }
  }
}

/**
 * opencode counterpart to `.claude/hooks/require-own-upstream.sh`: refuses
 * every tool call while the current non-master branch tracks a remote
 * master/main, until the session fixes the upstream.
 *
 * The decision lives in `.claude/hooks/lib/branch_upstream_check.py`, which
 * reads the repository state from the process working directory. Unlike the
 * Class A command-pattern plugins this runs for every tool, not only `bash`:
 * the condition is repository state rather than a command, and nothing else
 * may run until it is fixed. A core malfunction allows the call, the same
 * fail-open rule as the other adapters. See docs/internals/agent-hooks.md.
 */
export const RequireOwnUpstreamPlugin: Plugin = async () => ({
  "tool.execute.before": async (input, output) => {
    const tool = (input.tool || "").toString()
    const args = output.args as { command?: unknown } | undefined
    const command = args && typeof args.command === "string" ? args.command : ""
    const decision = decide(tool, command)
    if (decision.action === "block") {
      throw new Error(decision.reason)
    }
  },
})
