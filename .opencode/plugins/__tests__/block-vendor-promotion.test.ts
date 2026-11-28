// Adapter-level test for the block-vendor-promotion opencode plugin: it drives
// the plugin's own `tool.execute.before` handler, so it covers the wiring the
// smoke test cannot reach on its own -- `toPayloads`, the `apply_patch` route
// through `patchEdits`, the Claude_Docs connector-name mapping, and the throw
// the handler raises on a block. Needs Python 3.

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "block-vendor-promotion.ts")
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "vendor_promotion_check.py")
const ROOT = path.resolve(HERE, "..", "..", "..")
const LINK = "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv"

if (!fs.existsSync(CORE)) {
  throw new Error(`shared core not found at ${CORE}`)
}
const probe = spawnSync("python3", [CORE, JSON.stringify({ tool_name: "Read", tool_input: { file_path: "x" } })], { encoding: "utf-8" })
if (probe.status !== 0) {
  throw new Error(`python3 not usable: status=${probe.status} stderr=${probe.stderr}`)
}

type Hooks = {
  "tool.execute.before"?: (input: { tool: string }, output: { args: unknown }) => Promise<void>
}
type PluginFactory = (input: { directory: string }) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { BlockVendorPromotionPlugin: PluginFactory }
  return mod.BlockVendorPromotionPlugin
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
  return `*** Begin Patch\n*** Update File: ${file}\n${body}\n*** End Patch\n`
}

async function run(): Promise<void> {
  const factory = await loadPlugin()
  const hooks = await factory({ directory: ROOT })
  const before = hooks["tool.execute.before"]!

  // write / edit routes through toPayloads
  expect(
    await threw(before, "write", { filePath: "docs/notes.md", content: LINK }),
    "write adding a session link to a document should block",
  )
  expect(
    await threw(before, "edit", { filePath: "A.java", oldString: "", newString: "Created by Claude Code" }),
    "edit adding a tool credit should block",
  )
  expect(
    !(await threw(before, "write", { filePath: "docs/notes.md", content: "plain notes" })),
    "write of clean content should be allowed",
  )
  expect(
    !(await threw(before, "write", { filePath: "src/test/A.java", content: LINK })),
    "write to a test source should be allowed",
  )

  // apply_patch route through patchEdits: only added lines are judged
  expect(
    await threw(before, "apply_patch", { patchText: patch("docs/notes.md", `+Summary.\n+\n+${LINK}`) }),
    "apply_patch adding a session link should block",
  )
  expect(
    !(await threw(before, "apply_patch", { patchText: patch("docs/notes.md", "+Summary.\n+Describe the work.") })),
    "apply_patch adding clean lines should be allowed",
  )
  expect(
    !(await threw(before, "apply_patch", { patchText: `*** Begin Patch\n*** Update File: docs/notes.md\n-${LINK}\n*** End Patch\n` })),
    "apply_patch that only removes a link should be allowed",
  )

  // Claude_Docs connector-name mapping
  expect(
    await threw(before, "claude_ai_Claude_Docs_create", {}),
    "a write to the claude.ai document connector should block",
  )
  expect(
    await threw(before, "Claude_Docs_batch", {}),
    "an unprefixed connector write name should block",
  )
  expect(
    !(await threw(before, "claude_ai_Claude_Docs_read", {})),
    "a connector read should be allowed",
  )
  expect(
    !(await threw(before, "bash", { command: `echo ${LINK}` })),
    "a tool the policy does not look at should be allowed",
  )

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All block-vendor-promotion plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
