// .opencode/plugins/__tests__/block-checkstyle-edit.test.ts
//
// Self-contained test for the opencode adapter.  We don't launch
// opencode itself — we just exercise the plugin's handlers directly
// with synthetic input/output, after first calling the plugin
// factory.
//
// Run with:
//   bun test .opencode/plugins/__tests__/block-checkstyle-edit.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/block-checkstyle-edit.test.ts
//
// Requires Python 3 on PATH (the plugin shells out to the shared
// core).

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "block-checkstyle-edit.ts")
const PYTHON_HERE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib")
const PYTHON_CORE = path.join(PYTHON_HERE, "checkstyle_config_check.py")

if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, JSON.stringify({ tool: "write", filePath: "checkstyle.xml" })], { encoding: "utf-8" })
if (py.status !== 0) {
  throw new Error(`python3 not usable: status=${py.status} stderr=${py.stderr}`)
}

type Decision = { action: "block" | "allow"; reason: string; context: string; stderr: string }
type Hooks = {
  "tool.execute.before"?: (input: { tool: string; sessionID: string; callID: string }, output: { args: unknown }) => Promise<void>
  "tool.execute.after"?: (
    input: { tool: string; sessionID: string; callID: string; args: unknown },
    output: { title: string; output: string; metadata: unknown },
  ) => Promise<void>
}
type PluginFactory = (input?: unknown) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { BlockCheckstyleEditPlugin: PluginFactory }
  return mod.BlockCheckstyleEditPlugin
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

  // 1. `write` of checkstyle.xml must throw (block).
  {
    let threw: Error | null = null
    try {
      await before({ tool: "write", sessionID, callID }, { args: { filePath: "checkstyle.xml", content: "x" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "write checkstyle.xml should throw (block)")
    expect(
      !!threw && threw.message.includes("FORBIDDEN") && threw.message.includes("ABANDON"),
      "block message must say FORBIDDEN and ABANDON",
    )
    expect(
      !!threw && threw.message.includes("checkstyle.xml"),
      "block message must name the target file",
    )
  }

  // 2. `edit` of checkstyle-suppressions.xml must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "edit", sessionID, callID }, { args: { filePath: "checkstyle-suppressions.xml", oldString: "a", newString: "b" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "edit checkstyle-suppressions.xml should throw (block)")
  }

  // 3. `bash` with redirect to checkstyle.xml must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "echo x > checkstyle.xml" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "bash `echo x > checkstyle.xml` should throw (block)")
  }

  // 4. `bash` with sed -i on checkstyle.xml must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "sed -i 's/a/b/' checkstyle.xml" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "bash `sed -i` on checkstyle.xml should throw (block)")
  }

  // 5. `write` of an unrelated file must NOT throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "write", sessionID, callID }, { args: { filePath: "README.md", content: "x" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "write README.md should be allowed")
  }

  // 6. `bash` reading checkstyle.xml must NOT throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: { command: "cat checkstyle.xml" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "bash `cat checkstyle.xml` should be allowed (read-only)")
  }

  // 7. Non-inspected tools (read, grep, glob) must be a no-op.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "read", sessionID, callID }, { args: { filePath: "checkstyle.xml" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "read of checkstyle.xml should be a no-op (this plugin only blocks writes/edits)")
  }

  // 8. Missing `filePath` field on write should be a no-op (defensive).
  {
    let threw: Error | null = null
    try {
      await before({ tool: "write", sessionID, callID }, { args: { content: "x" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "missing filePath on write should be a no-op")
  }

  // 9. Missing `command` field on bash should be a no-op.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID }, { args: {} })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "missing command on bash should be a no-op")
  }

  // 10. `apply_patch` with a checkstyle file in the patch header must throw.
  {
    let threw: Error | null = null
    try {
      await before(
        { tool: "apply_patch", sessionID, callID },
        { args: { patchText: "*** Update File: checkstyle.xml\n@@ -1,1 +1,1 @@\n-old\n+new\n" } },
      )
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "apply_patch of checkstyle.xml should throw (block)")
  }

  // 11. Module-local checkstyle path must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "edit", sessionID, callID }, { args: { filePath: "engine/ml/checkstyle.xml", oldString: "a", newString: "b" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "edit module-local checkstyle.xml should throw (block)")
  }

  // 12. Filenames matching `*checkstyle*suppress*.xml` must throw.
  {
    let threw: Error | null = null
    try {
      await before({ tool: "write", sessionID, callID }, { args: { filePath: "engine-ml-checkstyle-suppressions.xml", content: "x" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw !== null, "write of *checkstyle*suppress*.xml should throw (block)")
  }

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All block-checkstyle-edit plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
