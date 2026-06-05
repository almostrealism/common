// .opencode/plugins/__tests__/warn-mvn-build.test.ts
//
// Self-contained test for the warn-mvn-build opencode adapter. We
// don't launch opencode itself — we just exercise the plugin's
// handlers directly with synthetic input/output, after first calling
// the plugin factory.
//
// Run with:
//   bun test .opencode/plugins/__tests__/warn-mvn-build.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/warn-mvn-build.test.ts
//
// Requires Python 3 on PATH (the plugin shells out to the shared core).

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "warn-mvn-build.ts")
const PYTHON_HERE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib")
const PYTHON_CORE = path.join(PYTHON_HERE, "mvn_build_check.py")

if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, "mvn install"], { encoding: "utf-8" })
if (py.status !== 0) {
  throw new Error(`python3 not usable: status=${py.status} stderr=${py.stderr}`)
}

type Hooks = {
  "tool.execute.before"?: (
    input: { tool: string; sessionID: string; callID: string },
    output: { args: unknown },
  ) => Promise<void>
  "tool.execute.after"?: (
    input: { tool: string; sessionID: string; callID: string; args: unknown },
    output: { title: string; output: string; metadata: unknown },
  ) => Promise<void>
}
type PluginFactory = (input?: unknown) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { WarnMvnBuildPlugin: PluginFactory }
  return mod.WarnMvnBuildPlugin
}

const sessionID = "test-session"
let nextCallID = 0
function callID(): string {
  nextCallID += 1
  return `test-call-${nextCallID}`
}

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

  // 1. `mvn install` must NOT throw (this hook only warns).
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID: callID() }, { args: { command: "mvn install" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "warn hook must NEVER throw on a warn-worthy command")
  }

  // 2. `mvn -version` is allow; .before is a no-op.
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "bash", sessionID, callID: callID() }, { args: { command: "mvn -version" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "mvn -version must NOT throw")
  }

  // 3. Non-bash tools must be ignored entirely.
  {
    const before = hooks["tool.execute.before"]!
    let threw: Error | null = null
    try {
      await before({ tool: "read", sessionID, callID: callID() }, { args: { filePath: "/etc/passwd" } })
    } catch (e) {
      threw = e as Error
    }
    expect(threw === null, "non-bash tool should be a no-op")
  }

  // -- tool.execute.after ----------------------------------------------------

  // 4. `mvn install` triggers warn → context appended.
  {
    const before = hooks["tool.execute.before"]!
    const after = hooks["tool.execute.after"]!
    const id = callID()
    await before({ tool: "bash", sessionID, callID: id }, { args: { command: "mvn install" } })
    const out = { title: "bash", output: "(real mvn output here)", metadata: {} }
    await after({ tool: "bash", sessionID, callID: id, args: { command: "mvn install" } }, out)
    expect(out.output.includes("[ar-hooks/warn-mvn-build]"), "warn path should append the [ar-hooks/warn-mvn-build] prefix")
    expect(out.output.includes("ar-build-validator"), "warn note should steer toward ar-build-validator")
    expect(out.output.includes("does not block"), "warn note should clarify it does not block the command")
    expect(out.output.startsWith("(real mvn output here)"), "original output must be preserved")
  }

  // 5. `mvn install -DskipTests` ALSO warns (informational only).
  {
    const before = hooks["tool.execute.before"]!
    const after = hooks["tool.execute.after"]!
    const id = callID()
    await before({ tool: "bash", sessionID, callID: id }, { args: { command: "mvn install -DskipTests" } })
    const out = { title: "bash", output: "BUILD SUCCESS", metadata: {} }
    await after({ tool: "bash", sessionID, callID: id, args: { command: "mvn install -DskipTests" } }, out)
    expect(out.output.includes("ar-build-validator"), "skipTests path still mentions ar-build-validator")
    expect(out.output.startsWith("BUILD SUCCESS"), "original output preserved on warn for skipTests")
  }

  // 6. `mvn test` is allow (block-mvn-test-direct handles it).
  {
    const before = hooks["tool.execute.before"]!
    const after = hooks["tool.execute.after"]!
    const id = callID()
    await before({ tool: "bash", sessionID, callID: id }, { args: { command: "mvn test" } })
    const out = { title: "bash", output: "original", metadata: {} }
    await after({ tool: "bash", sessionID, callID: id, args: { command: "mvn test" } }, out)
    expect(out.output === "original", "mvn test → allow → output unchanged")
  }

  // 7. Non-mvn commands are allow.
  {
    const before = hooks["tool.execute.before"]!
    const after = hooks["tool.execute.after"]!
    const id = callID()
    await before({ tool: "bash", sessionID, callID: id }, { args: { command: "ls -la" } })
    const out = { title: "bash", output: "original", metadata: {} }
    await after({ tool: "bash", sessionID, callID: id, args: { command: "ls -la" } }, out)
    expect(out.output === "original", "ls -la → allow → output unchanged")
  }

  // 8. Non-bash tools are a no-op for .after too.
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "read", output: "original", metadata: {} }
    await after({ tool: "read", sessionID, callID: callID(), args: { filePath: "/etc/passwd" } }, out)
    expect(out.output === "original", "non-bash tool should be a no-op for .after")
  }

  // 9. Cache miss in .after should still work (the slow path).
  {
    const after = hooks["tool.execute.after"]!
    const out = { title: "bash", output: "BUILD SUCCESS", metadata: {} }
    // Use a callID that .before was never called with.
    await after(
      { tool: "bash", sessionID, callID: "never-seen-before", args: { command: "mvn install" } },
      out,
    )
    expect(out.output.includes("ar-build-validator"), "cache-miss slow path should still produce the warn note")
  }

  if (failures > 0) {
    console.error(`\n${failures} assertion(s) failed`)
    process.exit(1)
  }
  console.log("All warn-mvn-build plugin adapter assertions passed.")
}

run().catch((e) => {
  console.error("test runner crashed:", e)
  process.exit(1)
})
