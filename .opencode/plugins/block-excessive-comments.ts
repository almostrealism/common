/**
 * opencode adapter for the inline-comment policy: an edit may not leave a
 * long inline comment in a source file.
 *
 * The decision logic is the shared core
 * `.claude/hooks/lib/inline_comment_check.py`, the single source of truth
 * for this policy across the Claude Code hook
 * (`.claude/hooks/block-excessive-comments.sh`), the shell-write check, the
 * commit tool and the checkstyle job in CI. This file only translates an
 * opencode tool call into the payload that core reads on stdin, and its
 * exit code into opencode's way of refusing a tool call (a thrown Error,
 * which the model sees as the tool's failure message).
 *
 * Tool coverage:
 *   - `write`: `filePath` and `content`, the whole file.
 *   - `edit`: `filePath`, `oldString`, `newString` and `replaceAll`. The core
 *     reads the file, applies the replacement and judges the result, so a
 *     line added to a comment that is already there is measured with it.
 *   - `apply_patch`: each file's added lines are handed to the core as a
 *     fragment it cannot place in the file, which it judges whole. That is
 *     the stricter reading, and the CI check judges the resulting file.
 *
 * Unlike the adapters whose core failure only loses a warning, this one
 * refuses the edit when the core cannot be run or does not reach a verdict:
 * a check that crashed has not decided the edit is acceptable.
 */

import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(HERE, "..", "..", ".claude", "hooks", "lib", "inline_comment_check.py")

/** Text an edit names that no file holds, so the core judges the fragment whole. */
const UNPLACEABLE = "\u0000unplaceable\u0000"

type ToolInput = Record<string, unknown>

/** Returns the string held under the first of the given keys, or the empty string. */
function text(args: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    if (typeof args[key] === "string") return args[key] as string
  }
  return ""
}

/** Returns one core payload per file an `apply_patch` call adds lines to. */
function patchInputs(patch: string): ToolInput[] {
  const files: { path: string; lines: string[] }[] = []
  let current: { path: string; lines: string[] } | null = null

  for (const line of patch.split(/\r\n|\r|\n/)) {
    const header = line.match(/^\*\*\* (?:Update|Add) File: (.+)$/)
    if (header) {
      current = { path: header[1].trim(), lines: [] }
      files.push(current)
    } else if (line.startsWith("*** ")) {
      current = null
    } else if (current && line.startsWith("+")) {
      current.lines.push(line.slice(1))
    }
  }

  return files.map((file) => ({
    file_path: file.path,
    old_string: UNPLACEABLE,
    new_string: file.lines.join("\n"),
  }))
}

/** Returns the core payloads for a tool call, or none when the tool writes no file. */
function toolInputs(tool: string, args: unknown): ToolInput[] {
  if (!args || typeof args !== "object") return []
  const fields = args as Record<string, unknown>
  const name = tool.toLowerCase()

  if (name === "write") {
    return [{ file_path: text(fields, "filePath", "file_path"), content: text(fields, "content") }]
  }
  if (name === "edit") {
    return [{
      file_path: text(fields, "filePath", "file_path"),
      old_string: text(fields, "oldString", "old_string"),
      new_string: text(fields, "newString", "new_string"),
      replace_all: fields.replaceAll === true || fields.replace_all === true,
    }]
  }
  if (name === "apply_patch") {
    return patchInputs(text(fields, "patchText", "patch", "input"))
  }
  return []
}

/** Asks the core about one payload and throws when it does not allow the edit. */
function requireAllowed(toolInput: ToolInput): void {
  const result = spawnSync("python3", [CORE, "--stdin"], {
    input: JSON.stringify({ tool_input: toolInput }),
    encoding: "utf-8",
    timeout: 10_000,
  })

  if (result.status === 0) return

  if (result.status === 2 && result.stderr) {
    throw new Error(result.stderr)
  }

  const cause = result.error ? result.error.message : `exit ${result.status}: ${result.stderr}`
  throw new Error(
    "BLOCKED: the inline-comment check did not run to a verdict (" + cause +
      "); an edit it could not judge is not allowed through.",
  )
}

export const BlockExcessiveCommentsPlugin: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      for (const toolInput of toolInputs(input.tool, output.args)) {
        if (toolInput.file_path) requireAllowed(toolInput)
      }
    },
  }
}
