// .opencode/plugins/__tests__/block-mvn-test-direct.test.ts
//
// Self-contained test for the opencode adapter. We don't launch
// opencode itself — we just exercise the plugin's handlers directly
// with synthetic input/output, after first calling the plugin factory.
//
// Run with:
//   bun test .opencode/plugins/__tests__/block-mvn-test-direct.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/block-mvn-test-direct.test.ts
//
// Requires Python 3 on PATH (the plugin shells out to the shared core).

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "block-mvn-test-direct.ts")
const PYTHON_HERE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib")
const PYTHON_CORE = path.join(PYTHON_HERE, "mvn_test_check.py")

// Sanity: the core must exist, and python3 must work.
if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, "mvn test"], { encoding: "utf-8" })
if (py.status !== 0) {
  throw new Error(`python3 not usable: status=${py.status} stderr=${py.stderr}`)
}

// The plugin's exported Plugin type is a function returning a hooks
// object. We import it dynamically so the test works with either
// Node's experimental TS support or with `tsx` / `bun` as the runner.
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
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { BlockMvnTestDirectPlugin: PluginFactory }
  return mod.BlockMvnTestDirectPlugin
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

  // 1. `mvn test` must throw (i.e. block).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "mvn test" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "mvn test should throw (block)")
    expect(threw?.message.includes("BLOCKED") || threw?.message.includes("mcp__ar-test-runner__start_test_run") ?? false, "block message must point at the test runner")
  }

  // 2. `mvn install -DskipTests` must NOT throw.
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "mvn install -DskipTests" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "mvn install -DskipTests should be allowed")
  }

  // 3. `bash -c "mvn test"` must throw (recursion catches this).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: 'bash -c "mvn test"' } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "bash -c 'mvn test' should throw (block via recursion)")
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

  // 6. A "warn" command must have its context appended to output.output.
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "bash", output: "(some tool output)", metadata: {} }
    // A `mvn test` invocation wrapped in unbalanced quotes so the core
    // is "uncertain" but `mvn` and `test` are both present.
    await after({ tool: "bash", sessionID, callID, args: { command: "awk 'BEGIN { mvn test" } }, out)
    expect(out.output.includes("[ar-hooks]"), "warn path should append the [ar-hooks] prefix")
    expect(out.output.includes("mcp__ar-test-runner__start_test_run"), "warn text should point at the test runner")
  }

  // 7. A "block" command must NOT have anything appended by .after
  //    (the throw in .before would already have stopped the tool).
  //    .after is called only when .before did NOT throw, so the
  //    `action` here will be "allow" or "warn". For "allow" we
  //    expect the output to be unchanged.
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "bash", output: "original", metadata: {} }
    await after({ tool: "bash", sessionID, callID, args: { command: "mvn install -DskipTests" } }, out)
    expect(out.output === "original", "allow path should leave output.output unchanged")
  }

  // 8. Non-bash tools must be a no-op for .after too.
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
  console.log("All plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
