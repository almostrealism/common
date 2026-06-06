// .opencode/plugins/__tests__/warn-git-log.test.ts
//
// Self-contained test for the warn-git-log opencode adapter.
//
// Run with:
//   bun test .opencode/plugins/__tests__/warn-git-log.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/warn-git-log.test.ts

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "warn-git-log.ts")
const PYTHON_HERE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib")
const PYTHON_CORE = path.join(PYTHON_HERE, "git_command_check.py")

if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, "warn-git-log", "git log"], { encoding: "utf-8" })
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
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { WarnGitLogPlugin: PluginFactory }
  return mod.WarnGitLogPlugin
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
  // warn-git-log never throws; .before just populates the Decision cache.

  // 1. `git log` must NOT throw (this is a warn, not a block).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "git log" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "git log should NOT throw (this is a warn)")
  }

  // 2. `git status` must NOT throw (no-op).
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

  // 3. Non-bash tools must be ignored entirely.
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
  // The interesting case for this hook: a "warn" command must have its
  // context appended to output.output.

  // 4. A "warn" command (e.g. `git log`) must have its context appended.
  {
    const before = hooks["tool.execute.before"]!
    const after = hooks["tool.execute.after"]!
    const out = { title: "bash", output: "(some tool output)", metadata: {} }
    // .before populates the cache
    await before({ tool: "bash", sessionID, callID }, { args: { command: "git log --oneline -5" } })
    // .after for the same callID should consume the cache and append
    await after({ tool: "bash", sessionID, callID, args: { command: "git log --oneline -5" } }, out)
    expect(out.output.includes("[ar-hooks/warn-git-log]"), "warn path should append the [ar-hooks] prefix")
    expect(out.output.includes("memory_branch_context"), "warn text should point at memory_branch_context")
  }

  // 5. An "allow" command (e.g. `git status`) must NOT have anything appended.
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "bash", output: "original", metadata: {} }
    await after({ tool: "bash", sessionID, callID, args: { command: "git status" } }, out)
    expect(out.output === "original", "allow path should leave output.output unchanged")
  }

  // 6. A "warn" command where .after is called WITHOUT a matching .before
  //    (cache miss) must still append the warn text. This is the
  //    fail-safe slow path in command_pattern.ts.
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "bash", output: "before-miss", metadata: {} }
    // Use a fresh callID so there's no .before in the cache for it.
    await after({ tool: "bash", sessionID, callID: "fresh-call-id", args: { command: "git log" } }, out)
    expect(out.output.includes("[ar-hooks/warn-git-log]"), "slow path should still append the [ar-hooks] prefix")
    expect(out.output.includes("memory_branch_context"), "slow path should still point at memory_branch_context")
  }

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All warn-git-log plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
