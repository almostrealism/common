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
 *   - `apply_patch`: an `Add File` section is the whole new file; an `Update
 *     File` section is turned back into the replacements its hunks describe,
 *     so the core reads the file, applies them and judges the result. A hunk
 *     whose context no longer matches the file falls back to judging the added
 *     text whole, the stricter reading. An update carrying `Move to` is judged
 *     at its destination, with the prior text read from the file it moves.
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

type ToolInput = Record<string, unknown>

/** Returns the string held under the first of the given keys, or the empty string. */
function text(args: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    if (typeof args[key] === "string") return args[key] as string
  }
  return ""
}

/** An apply_patch hunk as the two blocks it replaces: the old text and the new. */
type Replacement = { old_string: string; new_string: string }

/** The lines an `Add File` body introduces, stripped of their leading `+`. */
function addedLines(body: string[]): string[] {
  return body.filter((line) => line.startsWith("+")).map((line) => line.slice(1))
}

/** Turns an `Update File` body into the replacements its hunks describe.
 *
 * A context or removed line is part of the text the file holds now; a context
 * or added line is part of the text it will hold. The core applies each
 * replacement to the file it reads, so the result is judged whole; a hunk the
 * core cannot place falls back to judging its added text whole.
 */
function hunkReplacements(body: string[]): Replacement[] {
  const edits: Replacement[] = []
  let before: string[] = []
  let after: string[] = []
  const flush = () => {
    if (before.length || after.length) {
      edits.push({ old_string: before.join("\n"), new_string: after.join("\n") })
    }
    before = []
    after = []
  }

  for (const line of body) {
    if (line.startsWith("@@")) {
      flush()
    } else if (line.startsWith("+")) {
      after.push(line.slice(1))
    } else if (line.startsWith("-")) {
      before.push(line.slice(1))
    } else {
      const value = line.startsWith(" ") ? line.slice(1) : line
      before.push(value)
      after.push(value)
    }
  }
  flush()
  return edits
}

/** Returns one core payload per file an `apply_patch` call writes to. */
function patchInputs(patch: string): ToolInput[] {
  const inputs: ToolInput[] = []
  let kind: "add" | "update" | null = null
  let file = ""
  let destination = ""
  let body: string[] = []
  const flush = () => {
    if (kind === "add") {
      inputs.push({ file_path: file, content: addedLines(body).join("\n") })
    } else if (kind === "update" && destination) {
      inputs.push({ file_path: destination, move_from: file, edits: hunkReplacements(body) })
    } else if (kind === "update") {
      inputs.push({ file_path: file, edits: hunkReplacements(body) })
    }
    kind = null
    file = ""
    destination = ""
    body = []
  }

  for (const line of patch.split(/\r\n|\r|\n/)) {
    const header = line.match(/^\*\*\* (Update|Add) File: (.+)$/)
    const move = line.match(/^\*\*\* Move to: (.+)$/)
    if (header) {
      flush()
      kind = header[1] === "Add" ? "add" : "update"
      file = header[2].trim()
    } else if (move && kind === "update") {
      destination = move[1].trim()
    } else if (line.startsWith("*** ")) {
      flush()
    } else if (kind) {
      body.push(line)
    }
  }
  flush()
  return inputs
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

/** Asks the core about one payload and throws when it does not allow the edit.
 *
 * The core reads the edited file to judge the result, so it runs in the
 * project directory where a relative `file_path` resolves to the real file.
 */
function requireAllowed(toolInput: ToolInput, projectDir: string): void {
  const result = spawnSync("python3", [CORE, "--stdin"], {
    input: JSON.stringify({ tool_input: toolInput }),
    encoding: "utf-8",
    timeout: 10_000,
    cwd: projectDir,
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

export const BlockExcessiveCommentsPlugin: Plugin = async ({ directory }) => {
  const projectDir = directory || path.resolve(HERE, "..", "..")
  return {
    "tool.execute.before": async (input, output) => {
      for (const toolInput of toolInputs(input.tool, output.args)) {
        if (toolInput.file_path) requireAllowed(toolInput, projectDir)
      }
    },
  }
}
