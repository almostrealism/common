// .opencode/plugins/__tests__/steer-ls-grep.test.ts
//
// Self-contained test for the opencode adapter. We don't launch
// opencode itself — we just exercise the plugin's handlers directly
// with synthetic input/output, after first calling the plugin factory.
//
// Run with:
//   bun test .opencode/plugins/__tests__/steer-ls-grep.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/steer-ls-grep.test.ts
//
// Requires Python 3 on PATH (the plugin shells out to the shared core).

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "steer-ls-grep.ts")
const PYTHON_CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "steer_ls_grep_check.py")

if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, "ls docs/"], { encoding: "utf-8" })
if (py.status !== 0) {
  throw new Error(`python3 not usable: status=${py.status} stderr=${py.stderr}`)
}

type Decision = { action: "block" | "allow"; reason: string; context: string; stderr: string }
type Hooks = {
  "tool.execute.before"?: (
    input: { tool: string; sessionID: string; callID: string },
    output: { args: unknown },
  ) => Promise<void>
}
type PluginFactory = (input?: unknown) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { SteerLsGrepPlugin: PluginFactory }
  return mod.SteerLsGrepPlugin
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

  // -- tool.execute.before: BLOCK path ---------------------------------------

  // 1. `ls docs/` must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "ls docs/" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "ls docs/ should throw (block)")
    expect(!!threw && threw.message.includes("BLOCKED"), "block message must start with BLOCKED")
    expect(!!threw && threw.message.includes("`glob`"), "block message must point at the glob tool")
  }

  // 2. Bare `ls` must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "ls" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "bare `ls` should throw (block)")
  }

  // 3. `grep pattern file.txt` must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "grep pattern file.txt" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "grep PATTERN FILE should throw (block)")
    expect(!!threw && threw.message.includes("`grep`"), "block message must point at the grep tool")
  }

  // 4. `rg PATTERN FILE` must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "rg pattern file.txt" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "rg PATTERN FILE should throw (block)")
  }

  // -- tool.execute.before: ALLOW path ---------------------------------------

  // 5. `ls -la` must NOT throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "ls -la" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "ls -la should be allowed")
  }

  // 6. `ls -lh /var/log` must NOT throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "ls -lh /var/log" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "ls -lh /var/log should be allowed")
  }

  // 7. `grep -P '\\w+' f` (perl regex) must NOT throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "grep -P '\\w+' f" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "grep -P should be allowed (perl-regex is a shell feature)")
  }

  // 8. `grep --include='*.java' -r pattern src/` must NOT throw.
  {
    let threw: Error | null = null
    try {
      await before(
        { tool: "bash", sessionID, callID },
        { args: { command: "grep --include='*.java' -r pattern src/" } },
      )
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "grep --include should be allowed (filter the structured tool doesn't expose)")
  }

  // 9. `grep pattern file.txt | head -20` must NOT throw.
  {
    let threw: Error | null = null
    try {
      await before(
        { tool: "bash", sessionID, callID },
        { args: { command: "grep pattern file.txt | head -20" } },
      )
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "piped grep should be allowed (structured tool can't express the pipe)")
  }

  // 10. Unrelated commands must NOT throw.
  {
    let threw: Error | null = null
    try {
      await before(
        { tool: "bash", sessionID, callID },
        { args: { command: "mvn install -DskipTests" } },
      )
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "mvn install -DskipTests should be allowed")
  }

  // 11. Non-bash tools must be a no-op.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "read", sessionID, callID }, { args: { filePath: "/etc/passwd" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "non-bash tool should be a no-op")
  }

  // 12. Missing `command` field should be a no-op (defensive).
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: {} })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "missing command should be a no-op")
  }

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
