// .opencode/plugins/block-checkstyle-edit.ts
//
// opencode adapter for the "block edits to checkstyle configuration"
// policy.  Mirrors .opencode/plugins/block-mvn-test-direct.ts in
// shape: pull the relevant field(s) out of the opencode tool-call
// event, ask the shared core to decide, and translate the structured
// result into opencode-native rendering (throw to block).
//
// The decision logic lives in the shared core
//   .claude/hooks/lib/checkstyle_config_check.py
// which is the single source of truth for this policy across both
// Claude Code (.claude/hooks/block-checkstyle-edit.sh) and opencode
// (this file).
//
// opencode tool coverage:
//   - `bash`     : tool_input.command -> the shell command.  The
//                  shared core runs its bash-bypass detector
//                  (redirect, sed -i, tee, cp/mv, etc.).
//   - `write`    : tool_input.filePath  -> the file being created.
//   - `edit`     : tool_input.filePath  -> the file being edited.
//   - `apply_patch` (if present): tool_input.patchText contains
//                  the patch body; the path is encoded inside it.
//                  We extract a path from the patch text and check
//                  it against the same matcher.
//
// Any other tool name is a no-op (we return early) so the plugin
// does not affect the read/grep/glob workflows.
//
// Block semantics: a thrown Error is propagated by opencode as the
// tool's failure message, which is exactly what the model sees as
// the block reason — matching Claude Code's "exit 2 + stderr"
// semantics.  See docs/plans/OPENCODE_HOOKS.md for the harness
// contract differences.

import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

// The shared core. Resolved relative to this file so the plugin
// works regardless of the cwd opencode was launched from.
// Path: .opencode/plugins/block-checkstyle-edit.ts
//   -> .opencode/plugins/
//   -> .opencode/
//   -> <repo root>
//   -> .claude/hooks/lib/checkstyle_config_check.py
const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(
  HERE,
  "..",
  "..",
  ".claude",
  "hooks",
  "lib",
  "checkstyle_config_check.py",
)

interface Decision {
  action: "block" | "allow"
  reason: string
  context: string
  stderr: string
}

// Tools this plugin inspects.  opencode has no MultiEdit /
// NotebookEdit concept; a single `edit` tool with a `replaceAll`
// flag covers the multi-replace case, and notebook editing is
// outside the scope of a terminal coding agent.
const FILE_TOOLS = new Set(["write", "edit"])

/**
 * Call the shared core with a unified payload.  Returns a Decision
 * object.  On any internal error (Python missing, core crashed,
 * bad JSON), returns an "allow" Decision so a hook malfunction can
 * never block legitimate work.
 */
function callCore(payload: Record<string, unknown>): Decision {
  const result = spawnSync("python3", [CORE, JSON.stringify(payload)], {
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
 * Best-effort logging.  If logging fails for any reason, we just
 * swallow it — a logging failure must never affect the policy
 * decision.
 */
function logDecision(tool: string, decision: Decision): void {
  if (!decision.stderr) return
  // eslint-disable-next-line no-console
  console.error(`[ar-hooks/block-checkstyle-edit] (${tool}) ${decision.stderr.trim().split("\n", 1)[0]}`)
}

/**
 * Extract the file path from a write/edit tool's args object.
 * Returns the empty string if `filePath` is missing or non-string.
 */
function extractFilePath(args: unknown): string {
  if (!args || typeof args !== "object") return ""
  const obj = args as { filePath?: unknown; file_path?: unknown }
  if (typeof obj.filePath === "string") return obj.filePath
  if (typeof obj.file_path === "string") return obj.file_path
  return ""
}

/**
 * Extract the bash command from a `bash` tool's args object.
 * Returns the empty string if `command` is missing or non-string.
 */
function extractBashCommand(args: unknown): string {
  if (!args || typeof args !== "object") return ""
  const obj = args as { command?: unknown }
  return typeof obj.command === "string" ? obj.command : ""
}

/**
 * Extract a file path from an `apply_patch` tool's patch text.  The
 * patch format used by opencode (the `*** Update File:` /
 * `*** Add File:` / `*** Delete File:` headers, similar to
 * `git apply` / `patch -p1`) embeds the path in the first line
 * after the header.  We do a best-effort regex extraction; if
 * nothing looks like a path, the empty string is returned (which
 * the core treats as "no target", i.e. allow).
 */
function extractPatchFilePath(args: unknown): string {
  if (!args || typeof args !== "object") return ""
  const obj = args as { patchText?: unknown }
  if (typeof obj.patchText !== "string") return ""
  const header = obj.patchText.match(/^\*\*\*\s+(?:Update|Add|Delete)\s+File:\s*(\S+)/m)
  return header ? header[1] : ""
}

/**
 * Build the payload for the shared core from a tool name and the
 * opencode `output.args` object.  Returns null if the tool is not
 * one this plugin inspects, signalling "no-op".
 */
function buildPayload(tool: string, args: unknown): Record<string, unknown> | null {
  const toolLc = tool.toLowerCase()
  if (toolLc === "bash") {
    return { tool: "bash", command: extractBashCommand(args) }
  }
  if (FILE_TOOLS.has(toolLc)) {
    return { tool: toolLc, filePath: extractFilePath(args) }
  }
  if (toolLc === "apply_patch") {
    const p = extractPatchFilePath(args)
    if (!p) return null
    return { tool: "edit", filePath: p }
  }
  return null
}

export const BlockCheckstyleEditPlugin: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      const payload = buildPayload(input.tool, output.args)
      if (!payload) return
      // A payload with no file path / no command is a no-op.  The
      // core treats empty targets as "allow", but skipping the
      // subprocess entirely saves ~50ms of cold Python startup.
      if (payload.tool === "bash" && !payload.command) return
      if (payload.tool !== "bash" && !payload.filePath) return

      const decision = callCore(payload)
      logDecision(input.tool, decision)

      if (decision.action === "block") {
        throw new Error(decision.reason || decision.stderr)
      }
    },
  }
}
