// Adapter-level test for the block-excessive-comments opencode plugin: it
// drives the plugin's own `tool.execute.before` handler over a real checkout,
// covering the `apply_patch` Update route that must judge the resulting file
// (not the added fragment) and the `write`/`edit` routes. Needs Python 3.
//
// Run from the repo root, with a TypeScript-aware runner:
//   bun .opencode/plugins/__tests__/block-excessive-comments.test.ts

import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"
import * as os from "node:os"
import { spawnSync } from "node:child_process"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "block-excessive-comments.ts")
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "inline_comment_check.py")

if (!fs.existsSync(CORE)) {
  throw new Error(`shared core not found at ${CORE}`)
}
const probe = spawnSync("python3", ["--version"], { encoding: "utf-8" })
if (probe.status !== 0) {
  throw new Error(`python3 not usable: status=${probe.status} stderr=${probe.stderr}`)
}

type Hooks = {
  "tool.execute.before"?: (input: { tool: string }, output: { args: unknown }) => Promise<void>
}
type PluginFactory = (context: { directory: string }) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as {
    BlockExcessiveCommentsPlugin: PluginFactory
  }
  return mod.BlockExcessiveCommentsPlugin
}

let failures = 0
function expect(condition: unknown, message: string): void {
  if (!condition) {
    console.error(`  FAIL: ${message}`)
    failures++
  }
}

async function threw(
  before: NonNullable<Hooks["tool.execute.before"]>,
  tool: string,
  args: unknown,
): Promise<boolean> {
  try {
    await before({ tool }, { args })
    return false
  } catch {
    return true
  }
}

/** A Java file whose method body holds a wall of `count` single-line comments. */
function fileWithWall(count: number): string {
  const marks = "abcdefghij".split("")
  const wall = Array.from({ length: count }, (_, i) => `\t\t// wall ${marks[i]}`)
  return ["package p;", "", "/** A. */", "class A {", "\t/** M. */", "\tvoid m() {",
    ...wall, "\t\tint x = 1;", "\t}", "}"].join("\n") + "\n"
}

function wrap(body: string): string {
  return `*** Begin Patch\n${body}\n*** End Patch\n`
}

async function run(): Promise<void> {
  const project = fs.mkdtempSync(path.join(os.tmpdir(), "bec-"))
  fs.writeFileSync(path.join(project, "A.java"), fileWithWall(3))

  const before = (await (await loadPlugin())({ directory: project }))["tool.execute.before"]!

  // apply_patch Update: appending one comment to the existing three-comment
  // wall leaves a four-comment wall in the resulting file and must block.
  expect(
    await threw(before, "apply_patch", {
      patchText: wrap("*** Update File: A.java\n@@\n \t\t// wall c\n+\t\t// wall d\n \t\tint x = 1;"),
    }),
    "apply_patch growing an existing wall to four comments blocks",
  )

  // apply_patch Update: a change that touches no comment is allowed.
  expect(
    !(await threw(before, "apply_patch", {
      patchText: wrap("*** Update File: A.java\n@@\n-\t\tint x = 1;\n+\t\tint x = 2;"),
    })),
    "apply_patch with a benign change is allowed",
  )

  // apply_patch Move to: the moved file is judged at its destination.
  expect(
    await threw(before, "apply_patch", {
      patchText: wrap("*** Update File: A.java\n*** Move to: C.java\n@@\n \t\t// wall c\n+\t\t// wall d\n \t\tint x = 1;"),
    }),
    "apply_patch moving a file while growing its wall blocks",
  )
  expect(
    !(await threw(before, "apply_patch", {
      patchText: wrap("*** Update File: A.java\n*** Move to: C.java\n@@\n-\t\tint x = 1;\n+\t\tint x = 2;"),
    })),
    "apply_patch moving a file with a benign change is allowed",
  )

  // apply_patch Add File: the whole new file is judged, so a five-comment wall blocks.
  expect(
    await threw(before, "apply_patch", {
      patchText: wrap("*** Add File: B.java\n+package p;\n+class B {\n+\t// a\n+\t// b\n+\t// c\n+\t// d\n+\t// e\n+\tint x;\n+}"),
    }),
    "apply_patch Add File with a five-comment wall blocks",
  )

  // edit: growing the wall by one comment is judged on the resulting file.
  expect(
    await threw(before, "edit", {
      filePath: "A.java",
      oldString: "\t\tint x = 1;",
      newString: "\t\t// wall d\n\t\tint x = 1;",
    }),
    "edit growing an existing wall blocks",
  )

  // write: a whole file carrying a five-comment wall blocks.
  expect(
    await threw(before, "write", { filePath: "A.java", content: fileWithWall(5) }),
    "write of a file with a five-comment wall blocks",
  )

  // A short, lawful edit is allowed.
  expect(
    !(await threw(before, "edit", { filePath: "A.java", oldString: "int x = 1;", newString: "int x = 3;" })),
    "a short lawful edit is allowed",
  )

  // A file the policy does not govern is left alone.
  expect(
    !(await threw(before, "write", { filePath: "notes.md", content: "// a\n// b\n// c\n// d\n// e\n" })),
    "a non-source file is not governed",
  )

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All block-excessive-comments plugin adapter assertions passed.")
}

run().catch((error) => {
  console.error("test runner crashed:", error)
  process.exit(1)
})
