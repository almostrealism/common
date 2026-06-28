// .opencode/plugins/__tests__/block-branch-track-master.test.ts
//
// Self-contained test for the block-branch-track-master opencode adapter.
// Mirrors block-git-commit.test.ts.
//
// Run with:
//   bun test .opencode/plugins/__tests__/block-branch-track-master.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/block-branch-track-master.test.ts
//
// Requires Python 3 on PATH (the plugin shells out to the shared
// core via the command_pattern helper).

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "block-branch-track-master.ts")
const PYTHON_HERE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib")
const PYTHON_CORE = path.join(PYTHON_HERE, "git_command_check.py")

if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, "block-branch-track-master", "git checkout -b feature origin/master"], { encoding: "utf-8" })
if (py.status !== 0) {
  throw new Error(`python3 not usable: status=${py.status} stderr=${py.stderr}`)
}

type Hooks = {
  "tool.execute.before"?: (input: { tool: string; sessionID: string; callID: string }, output: { args: unknown }) => Promise<void>
  "tool.execute.after"?: (
    input: { tool: string; sessionID: string; callID: string; args: unknown },
    output: { title: string; output: string; metadata: unknown },
  ) => Promise<void>
}
type PluginFactory = (input?: unknown) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { BlockBranchTrackMasterPlugin: PluginFactory }
  return mod.BlockBranchTrackMasterPlugin
}

const sessionID = "test-session"
const callID = "test-call"

let failures = 0
function expect(cond: unknown, msg: string): void {
  if (!cond) {
    console.error(`  FAIL: ${msg}`)
    failures++
  }
}

async function run(): Promise<void> {
  const factory = await loadPlugin()
  const hooks = await factory()
  const before = hooks["tool.execute.before"]!
  const after = hooks["tool.execute.after"]!

  async function throwsFor(command: string): Promise<Error | null> {
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command } })
      return null
    } catch (e) {
      return e as Error
    }
  }

  // 1. Branching off a remote master ref must throw (block) and explain the fix.
  {
    const threw = await throwsFor("git checkout -b feature origin/master")
    expect(threw !== null, "checkout -b feature origin/master should throw (block)")
    expect(
      !!threw && threw.message.includes("BLOCKED") && threw.message.includes("--no-track"),
      "block message must explain --no-track",
    )
  }

  // 2. The corrected --no-track form must NOT throw.
  expect((await throwsFor("git checkout -b feature --no-track origin/master")) === null, "--no-track form should be allowed")

  // 3. Branching off local master must NOT throw.
  expect((await throwsFor("git checkout -b feature master")) === null, "off local master should be allowed")

  // 4. A push that overwrites master must throw.
  expect((await throwsFor("git push origin HEAD:master")) !== null, "push HEAD:master should throw (block)")

  // 5. Pushing a branch under its own name must NOT throw.
  expect((await throwsFor("git push -u origin feature")) === null, "push -u origin feature should be allowed")

  // 6. Non-bash tools must be ignored entirely.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "read", sessionID, callID }, { args: { filePath: "/etc/passwd" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "non-bash tool should be a no-op")
  }

  // 7. Missing `command` field should be a no-op (defensive).
  expect((await throwsFor("")) === null, "empty command should be a no-op")

  // 8. This policy has no warn path; .after must leave output.output unchanged.
  {
    const out = { title: "bash", output: "original", metadata: {} }
    await after({ tool: "bash", sessionID, callID, args: { command: "git status" } }, out)
    expect(out.output === "original", "allow path should leave output.output unchanged")
  }

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All block-branch-track-master plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
