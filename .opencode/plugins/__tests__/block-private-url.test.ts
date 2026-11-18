// Adapter-level test for the block-private-url opencode plugin: it drives the
// plugin's own `tool.execute.before` handler, covering the `apply_patch` route
// to commit.txt that never reaches the Python core as itself. Needs Python 3.

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "block-private-url.ts")
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "private_url_check.py")
const ROOT = path.resolve(HERE, "..", "..", "..")
const LINK = "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv"

if (!fs.existsSync(CORE)) {
  throw new Error(`shared core not found at ${CORE}`)
}
const probe = spawnSync("python3", [CORE, JSON.stringify({ tool_name: "Bash", tool_input: { command: "git status" } })], { encoding: "utf-8" })
if (probe.status !== 0) {
  throw new Error(`python3 not usable: status=${probe.status} stderr=${probe.stderr}`)
}

type Hooks = {
  "tool.execute.before"?: (input: { tool: string }, output: { args: unknown }) => Promise<void>
}
type PluginFactory = (input: { directory: string }) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { BlockPrivateUrlPlugin: PluginFactory }
  return mod.BlockPrivateUrlPlugin
}

let failures = 0
function expect(cond: unknown, msg: string): void {
  if (!cond) {
    console.error(`  FAIL: ${msg}`)
    failures++
  }
}

async function threw(before: NonNullable<Hooks["tool.execute.before"]>, tool: string, args: unknown): Promise<boolean> {
  try {
    await before({ tool }, { args })
    return false
  } catch {
    return true
  }
}

function patch(file: string, body: string): string {
  return `*** Begin Patch\n*** Add File: ${file}\n${body}\n*** End Patch\n`
}

async function run(): Promise<void> {
  const factory = await loadPlugin()
  const hooks = await factory({ directory: ROOT })
  const before = hooks["tool.execute.before"]!

  expect(
    await threw(before, "apply_patch", { patchText: patch("commit.txt", `+Fix.\n+\n+${LINK}`) }),
    "apply_patch adding a session link to commit.txt should block",
  )
  expect(
    !(await threw(before, "apply_patch", { patchText: patch("commit.txt", "+Fix the parser.\n+\n+Describe the work.") })),
    "apply_patch with clean commit.txt content should be allowed",
  )
  expect(
    !(await threw(before, "apply_patch", { patchText: patch("docs/notes.md", `+${LINK}`) })),
    "apply_patch to a non-commit.txt file should be allowed",
  )
  expect(
    await threw(before, "bash", { command: `gh pr create --body "${LINK}"` }),
    "bash gh pr create with a session link should block",
  )
  expect(
    await threw(before, "edit", { filePath: "commit.txt", newString: LINK }),
    "edit of commit.txt adding a session link should block",
  )
  expect(
    !(await threw(before, "write", { filePath: "docs/notes.md", content: LINK })),
    "write of an unrelated file should be allowed",
  )
  expect(
    !(await threw(before, "bash", { command: "git status" })),
    "bash git status should be allowed",
  )

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All block-private-url plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
