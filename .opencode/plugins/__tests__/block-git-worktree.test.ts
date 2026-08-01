// .opencode/plugins/__tests__/block-git-worktree.test.ts
//
// Self-contained test for the block-git-worktree opencode adapter.
// Mirrors block-git-commit.test.ts.
//
// Run with:
//   bun test .opencode/plugins/__tests__/block-git-worktree.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/block-git-worktree.test.ts

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "block-git-worktree.ts")
const PYTHON_HERE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib")
const PYTHON_CORE = path.join(PYTHON_HERE, "git_command_check.py")

if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, "block-git-worktree", "git worktree add /tmp/wt"], { encoding: "utf-8" })
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
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { BlockGitWorktreePlugin: PluginFactory }
  return mod.BlockGitWorktreePlugin
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

  // 1. `git worktree add /tmp/wt` must throw (i.e. block).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "git worktree add /tmp/wt" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "git worktree add should throw (block)")
    expect(
      !!threw && threw.message.includes("BLOCKED") && threw.message.includes("working tree"),
      "block message must mention the working tree rule",
    )
  }

  // 2. `git worktree list` must NOT throw (read-only).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "git worktree list" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "git worktree list should be allowed")
  }

  // 3. `git worktree --track add /tmp/wt` must throw.
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "git worktree --track add /tmp/wt" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "git worktree --track add should throw (block via intermediate flag)")
  }

  // 4. `git worktree remove /tmp/wt` must NOT throw (not the 'add' subcommand).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "git worktree remove /tmp/wt" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "git worktree remove should be allowed")
  }

  // 5. Non-bash tools must be ignored entirely.
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

  // -- tool.execute.after ----------------------------------------------------

  // 6. An "allow" command must leave output.output unchanged.
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "bash", output: "original", metadata: {} }
    await after({ tool: "bash", sessionID, callID, args: { command: "git status" } }, out)
    expect(out.output === "original", "allow path should leave output.output unchanged")
  }

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All block-git-worktree plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
