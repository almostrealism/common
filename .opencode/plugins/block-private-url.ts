import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(HERE, "..", "..", ".claude", "hooks", "lib", "private_url_check.py")

interface Decision {
  action: "block" | "allow"
  reason?: string
}

/**
 * The opencode tool call in the Claude Code payload shape the core reads, or
 * null for a tool the policy does not look at. opencode names an MCP tool
 * `<server>_<tool>`, so an ar-manager GitHub tool is mapped to the Claude Code
 * name `mcp__ar-manager__<tool>`.
 */
function toPayload(tool: string, args: Record<string, unknown>, cwd: string): object | null {
  if (tool === "bash") return { tool_name: "Bash", tool_input: { command: args.command }, cwd }
  if (tool === "write") {
    return { tool_name: "Write", tool_input: { file_path: args.filePath, content: args.content } }
  }
  if (tool === "edit") {
    return { tool_name: "Edit", tool_input: { file_path: args.filePath, new_string: args.newString } }
  }
  if (tool === "apply_patch") {
    const content = patchCommitTxtContent(typeof args.patchText === "string" ? args.patchText : "")
    if (content === null) return null
    return { tool_name: "Write", tool_input: { file_path: "commit.txt", content } }
  }
  const github = /^ar-manager_(github_.+)$/.exec(tool)
  if (github) return { tool_name: `mcp__ar-manager__${github[1]}`, tool_input: args }
  return null
}

/**
 * The content a patch would leave in `commit.txt`, or null when the patch does
 * not touch it. opencode's `apply_patch` uses `*** Update File:` /
 * `*** Add File:` / `*** Delete File:` headers; within a file section the lines
 * that remain in the file are the added (`+`) and unchanged (` `) ones. Only
 * `commit.txt` is a published route, so only its section is returned, in the
 * Claude Code `Write` shape the shared core reads.
 */
function patchCommitTxtContent(patchText: string): string | null {
  let inCommit = false
  let touched = false
  const content: string[] = []
  for (const line of patchText.split(/\r?\n/)) {
    const header = /^\*\*\*\s+(?:Update|Add|Delete)\s+File:\s*(\S+)/.exec(line)
    if (header) {
      inCommit = path.basename(header[1]) === "commit.txt"
      if (inCommit) touched = true
      continue
    }
    if (!inCommit || line.startsWith("-")) continue
    content.push(line.replace(/^[+ ]/, ""))
  }
  return touched ? content.join("\n") : null
}

/**
 * Ask the shared core about a payload. Any internal error (Python missing,
 * core crashed, bad JSON) allows the call, so a hook malfunction can never
 * block legitimate work.
 */
function callCore(payload: object): Decision {
  const result = spawnSync("python3", [CORE, JSON.stringify(payload)], { encoding: "utf-8", timeout: 5_000 })
  if (result.error || result.status !== 0) return { action: "allow" }
  try {
    return JSON.parse(result.stdout) as Decision
  } catch {
    return { action: "allow" }
  }
}

/**
 * opencode adapter for the "never publish a private link" policy, the
 * counterpart to .claude/hooks/block-private-url.sh. The decision lives in the
 * shared core .claude/hooks/lib/private_url_check.py.
 */
export const BlockPrivateUrlPlugin: Plugin = async ({ directory }) => ({
  "tool.execute.before": async (input, output) => {
    const args = (output.args ?? {}) as Record<string, unknown>
    const payload = toPayload(input.tool, args, directory)
    if (!payload) return
    const decision = callCore(payload)
    if (decision.action === "block") throw new Error(decision.reason ?? "")
  },
})
