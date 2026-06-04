// .opencode/plugins/__tests__/block-git-commit.test.ts
//
// Self-contained test for the block-git-commit opencode adapter.
// Mirrors block-mvn-test-direct.test.ts.
//
// Run with:
//   bun test .opencode/plugins/__tests__/block-git-commit.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/block-git-commit.test.ts
//
// Requires Python 3 on PATH (the plugin shells out to the shared
// core via the command_pattern helper).

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "block-git-commit.ts")
const PYTHON_HERE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib")
const PYTHON_CORE = path.join(PYTHON_HERE, "git_command_check.py")

if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, "block-git-commit", "git commit -m x"], { encoding: "utf-8" })
if (py.status !== 0) {
  throw new Error(`python3 not usable: status=${py.status} stderr=${py.stderr}`)
}

type Decision = { action: "block" | "allow" | "warn"; reason: string; context: string; stderr: string }
type Hooks = {
  "tool.execute.before"?: (input: { tool: string; sessionID: string; callID: string }, output: { args: unknown }) => Promise<void>
  "tool.execute.after"?: (
    input: { tool: string; sessionID: string; callID: string; args: unknown },
    output: { title: string; output: string; metadata: unknown },
  ) => Promise<void>
}
type PluginFactory = (input?: unknown) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { BlockGitCommitPlugin: PluginFactory }
  return mod.BlockGitCommitPlugin
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

  // -- tool.execute.before ---------------------------------------------------

  // 1. `git commit -m x` must throw (i.e. block).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "git commit -m x" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "git commit -m x should throw (block)")
    expect(
      !!threw && threw.message.includes("BLOCKED") && threw.message.includes("git add"),
      "block message must point at git add",
    )
  }

  // 2. `git status` must NOT throw.
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "git status" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "git status should be allowed")
  }

  // 3. `git commit --amend` must throw.
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "git commit --amend" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "git commit --amend should throw (block)")
  }

  // 4. Non-bash tools must be ignored entirely.
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "read", sessionID, callID }, { args: { filePath: "/etc/passwd" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "non-bash tool should be a no-op")
  }

  // 5. Missing `command` field should be a no-op (defensive).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: {} })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "missing command should be a no-op")
  }

  // -- tool.execute.after ----------------------------------------------------
  // The block-git-commit policy has no warn path; .after should be a no-op
  // for both block and allow.

  // 6. An "allow" command must leave output.output unchanged.
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "bash", output: "original", metadata: {} }
    await after({ tool: "bash", sessionID, callID, args: { command: "git status" } }, out)
    expect(out.output === "original", "allow path should leave output.output unchanged")
  }

  // 7. Non-bash tools must be a no-op for .after too.
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "read", output: "original", metadata: {} }
    await after({ tool: "read", sessionID, callID, args: { filePath: "/etc/passwd" } }, out)
    expect(out.output === "original", "non-bash tool should be a no-op for .after")
  }

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All block-git-commit plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
