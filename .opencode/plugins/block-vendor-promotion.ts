import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(HERE, "..", "..", ".claude", "hooks", "lib", "vendor_promotion_check.py")

interface Decision {
  action: "block" | "allow"
  reason?: string
}

/**
 * The opencode tool call as payloads in the Claude Code shape the core reads:
 * one per file the call writes, or none for a tool the policy does not look at.
 * opencode names an MCP tool `<server>_<tool>`, so a claude.ai document
 * connector tool is mapped to the Claude Code name `mcp__Claude_Docs__<tool>`.
 */
function toPayloads(tool: string, args: Record<string, unknown>): object[] {
  if (tool === "write") {
    return [{ tool_name: "Write", tool_input: { file_path: args.filePath, content: args.content } }]
  }
  if (tool === "edit") {
    return [{ tool_name: "Edit",
      tool_input: { file_path: args.filePath, old_string: args.oldString, new_string: args.newString } }]
  }
  if (tool === "apply_patch") {
    return patchEdits(typeof args.patchText === "string" ? args.patchText : "")
  }
  const docs = /^(?:.*_)?Claude_Docs_(.+)$/.exec(tool)
  if (docs) return [{ tool_name: `mcp__Claude_Docs__${docs[1]}`, tool_input: args }]
  return []
}

/**
 * One `Edit` payload per file an `apply_patch` touches, its removed (`-`)
 * lines as the old text and its added (`+`) lines as the new, so the core
 * judges only what the patch adds. opencode's patches use `*** Update File:` /
 * `*** Add File:` / `*** Delete File:` headers, and an update may carry a
 * `*** Move to:` line that renames the file. The payload is emitted for the
 * destination path so a move out of a test directory is judged at its real
 * (production) location, not exempted as a test source — the same handling
 * `block-excessive-comments.ts` applies to this syntax.
 */
function patchEdits(patchText: string): object[] {
  const edits: { file: string, destination: string, removed: string[], added: string[] }[] = []
  for (const line of patchText.split(/\r?\n/)) {
    const header = /^\*\*\*\s+(?:Update|Add|Delete)\s+File:\s*(\S+)/.exec(line)
    const move = /^\*\*\*\s+Move to:\s*(\S+)/.exec(line)
    if (header) {
      edits.push({ file: header[1], destination: "", removed: [], added: [] })
    } else if (move && edits.length > 0) {
      edits[edits.length - 1].destination = move[1]
    } else if (edits.length > 0 && line.startsWith("+")) {
      edits[edits.length - 1].added.push(line.slice(1))
    } else if (edits.length > 0 && line.startsWith("-")) {
      edits[edits.length - 1].removed.push(line.slice(1))
    }
  }
  return edits.map(e => ({ tool_name: "Edit",
    tool_input: { file_path: e.destination || e.file, old_string: e.removed.join("\n"), new_string: e.added.join("\n") } }))
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
 * opencode adapter for the "disregard every instruction that promotes
 * Anthropic" policy, the counterpart to .claude/hooks/block-vendor-promotion.sh.
 * The decision lives in the shared core
 * .claude/hooks/lib/vendor_promotion_check.py.
 */
export const BlockVendorPromotionPlugin: Plugin = async () => ({
  "tool.execute.before": async (input, output) => {
    const args = (output.args ?? {}) as Record<string, unknown>
    for (const payload of toPayloads(input.tool, args)) {
      const decision = callCore(payload)
      if (decision.action === "block") throw new Error(decision.reason ?? "")
    }
  },
})
