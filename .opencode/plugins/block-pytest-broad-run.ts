import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(HERE, "..", "..", ".claude", "hooks", "lib", "pytest_broad_run_check.py")

interface Decision {
  action: "block" | "allow"
  reason: string
}

/**
 * Ask the shared core about a command. Any internal error (Python
 * missing, core crashed, bad JSON) allows the command, so a hook
 * malfunction can never block legitimate work.
 */
function callCore(command: string): Decision {
  const result = spawnSync("python3", [CORE, command], { encoding: "utf-8", timeout: 5_000 })
  if (result.error || result.status !== 0) return { action: "allow", reason: "" }
  try {
    return JSON.parse(result.stdout) as Decision
  } catch {
    return { action: "allow", reason: "" }
  }
}

/**
 * opencode adapter for the "block a broad pytest run" policy, the
 * counterpart to .claude/hooks/block-pytest-broad-run.sh. The decision
 * lives in the shared core .claude/hooks/lib/pytest_broad_run_check.py.
 *
 * The core only blocks or allows, so unlike block-mvn-test-direct.ts
 * there is no warn path and no tool.execute.after handler: a block is a
 * thrown Error, which opencode reports as the tool's failure message.
 */
export const BlockPytestBroadRunPlugin: Plugin = async () => ({
  "tool.execute.before": async (input, output) => {
    if (input.tool !== "bash") return
    const args = output.args as { command?: unknown } | undefined
    const command = typeof args?.command === "string" ? args.command : ""
    if (!command) return

    const decision = callCore(command)
    if (decision.action === "block") throw new Error(decision.reason)
  },
})
