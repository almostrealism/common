// .opencode/plugins/__tests__/memory-reminder.test.ts
//
// Self-contained test for the opencode adapter. We don't launch
// opencode itself — we just exercise the plugin's handlers directly
// with synthetic input/output, after first calling the plugin factory.
//
// Run with:
//   bun test .opencode/plugins/__tests__/memory-reminder.test.ts
//   # or
//   npx tsx .opencode/plugins/__tests__/memory-reminder.test.ts
//
// Requires Python 3 on PATH (the plugin shells out to the shared
// core) and `tsx` or `bun` as the TS runner.

import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath, pathToFileURL } from "node:url"
import * as fs from "node:fs"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN_PATH = path.resolve(HERE, "..", "memory-reminder.ts")
const PYTHON_CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "memory_reminder_check.py")

// Sanity: the core must exist, and python3 must work.
if (!fs.existsSync(PYTHON_CORE)) {
  throw new Error(`shared core not found at ${PYTHON_CORE}`)
}
const py = spawnSync("python3", [PYTHON_CORE, "Bash", "1000", "{}"], { encoding: "utf-8" })
if (py.status !== 0) {
  throw new Error(`python3 not usable: status=${py.status} stderr=${py.stderr}`)
}

type Decision = {
  action: "allow" | "warn"
  reason: string
  context: string
  stderr: string
  new_state: Record<string, number> | null
}

type Hooks = {
  "tool.execute.before"?: (
    input: { tool: string; sessionID: string; callID: string },
    output: { args: unknown; output: string },
  ) => Promise<void>
  "tool.execute.after"?: (
    input: { tool: string; sessionID: string; callID: string; args: unknown },
    output: { title: string; output: string; metadata: unknown },
  ) => Promise<void>
}

type PluginFactory = (input?: unknown) => Promise<Hooks>

async function loadPlugin(): Promise<PluginFactory> {
  const mod = (await import(pathToFileURL(PLUGIN_PATH).href)) as { MemoryReminderPlugin: PluginFactory }
  return mod.MemoryReminderPlugin
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
  const before = hooks["tool.execute.before"]!
  const after = hooks["tool.execute.after"]!

  // 1. Read tools are a no-op (the output is unchanged).
  {
    const out = { args: { filePath: "/etc/passwd" }, output: "" }
    await before({ tool: "Read", sessionID: "s1", callID: "c1" }, out)
    expect(out.output === "", "Read should not add anything to output")
  }

  // 2. mcp__ar-manager__memory_recall is a no-op (read, not a side effect).
  {
    const out = { args: { query: "foo" }, output: "" }
    await before({ tool: "mcp__ar-manager__memory_recall", sessionID: "s1", callID: "c2" }, out)
    expect(out.output === "", "memory_recall should not add anything")
  }

  // 3. A long stretch of side-effect calls without a store eventually
  //    fires the reminder. The .ts plugin reads env vars through the
  //    python3 subprocess (the core's _thresholds() reads os.environ),
  //    so we set the env var for the test process.
  {
    const prev = process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD
    const prevWarm = process.env.AR_MEMORY_REMIND_WARMUP_CALLS
    const prevBo = process.env.AR_MEMORY_REMIND_BACKOFF_CALLS
    process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD = "3"
    process.env.AR_MEMORY_REMIND_WARMUP_CALLS = "0"
    process.env.AR_MEMORY_REMIND_BACKOFF_CALLS = "0"
    try {
      let last = { args: {}, output: "" }
      for (let i = 0; i < 4; i++) {
        last = { args: { command: "mvn test" }, output: "" }
        await before({ tool: "Bash", sessionID: "s2", callID: `c${i}` }, last)
      }
      expect(last.output.includes("[ar-hooks/memory-reminder]"), "warn path appends the prefix")
      expect(last.output.includes("memory_store"), "warn text mentions memory_store")
    } finally {
      if (prev === undefined) delete process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD
      else process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD = prev
      if (prevWarm === undefined) delete process.env.AR_MEMORY_REMIND_WARMUP_CALLS
      else process.env.AR_MEMORY_REMIND_WARMUP_CALLS = prevWarm
      if (prevBo === undefined) delete process.env.AR_MEMORY_REMIND_BACKOFF_CALLS
      else process.env.AR_MEMORY_REMIND_BACKOFF_CALLS = prevBo
    }
  }

  // 4. mcp__ar-manager__memory_store does not fire the reminder.
  {
    const prev = process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD
    const prevWarm = process.env.AR_MEMORY_REMIND_WARMUP_CALLS
    process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD = "1"
    process.env.AR_MEMORY_REMIND_WARMUP_CALLS = "0"
    try {
      const out = { args: { content: "test" }, output: "" }
      await before(
        { tool: "mcp__ar-manager__memory_store", sessionID: "s3", callID: "c-store" },
        out,
      )
      expect(out.output === "", "memory_store should not add a reminder")
    } finally {
      if (prev === undefined) delete process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD
      else process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD = prev
      if (prevWarm === undefined) delete process.env.AR_MEMORY_REMIND_WARMUP_CALLS
      else process.env.AR_MEMORY_REMIND_WARMUP_CALLS = prevWarm
    }
  }

  // 5. Two different sessionIDs have independent state.
  //    With a low threshold, both sessions should fire; one
  //    session's reminder must not affect the other's state.
  {
    const prev = process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD
    const prevWarm = process.env.AR_MEMORY_REMIND_WARMUP_CALLS
    const prevBo = process.env.AR_MEMORY_REMIND_BACKOFF_CALLS
    process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD = "2"
    process.env.AR_MEMORY_REMIND_WARMUP_CALLS = "0"
    process.env.AR_MEMORY_REMIND_BACKOFF_CALLS = "0"
    try {
      const sA1 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-A", callID: "A1" }, sA1)
      expect(sA1.output === "", "sess-A 1st call no fire")
      const sA2 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-A", callID: "A2" }, sA2)
      expect(sA2.output.includes("[ar-hooks/memory-reminder]"), "sess-A 2nd call fires")
      // sess-B is fresh — its first two calls should not fire even
      // though sess-A is in the middle of a backoff.
      const sB1 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-B", callID: "B1" }, sB1)
      expect(sB1.output === "", "sess-B 1st call no fire (fresh state)")
      const sB2 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-B", callID: "B2" }, sB2)
      expect(sB2.output.includes("[ar-hooks/memory-reminder]"), "sess-B 2nd call fires (independent state)")
    } finally {
      if (prev === undefined) delete process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD
      else process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD = prev
      if (prevWarm === undefined) delete process.env.AR_MEMORY_REMIND_WARMUP_CALLS
      else process.env.AR_MEMORY_REMIND_WARMUP_CALLS = prevWarm
      if (prevBo === undefined) delete process.env.AR_MEMORY_REMIND_BACKOFF_CALLS
      else process.env.AR_MEMORY_REMIND_BACKOFF_CALLS = prevBo
    }
  }

  // 6. After a memory_store, the same session starts a fresh counter
  //    and needs the full threshold to fire again.
  {
    const prev = process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD
    const prevWarm = process.env.AR_MEMORY_REMIND_WARMUP_CALLS
    const prevCo = process.env.AR_MEMORY_REMIND_COOLDOWN_CALLS
    process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD = "3"
    process.env.AR_MEMORY_REMIND_WARMUP_CALLS = "0"
    process.env.AR_MEMORY_REMIND_COOLDOWN_CALLS = "0"
    try {
      const c1 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-C", callID: "C1" }, c1)
      expect(c1.output === "", "C1 no fire")
      const c2 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-C", callID: "C2" }, c2)
      expect(c2.output === "", "C2 no fire (still under threshold=3)")
      const c3 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-C", callID: "C3" }, c3)
      expect(c3.output.includes("[ar-hooks/memory-reminder]"), "C3 fires (counter=3)")
      // Now store a memory.
      const cStore = { args: { content: "x" }, output: "" }
      await before({ tool: "mcp__ar-manager__memory_store", sessionID: "sess-C", callID: "Cstore" }, cStore)
      // Counter is now reset. Next 2 calls should not fire.
      const c4 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-C", callID: "C4" }, c4)
      expect(c4.output === "", "C4 no fire (post-store reset)")
      const c5 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-C", callID: "C5" }, c5)
      expect(c5.output === "", "C5 no fire (counter=2, threshold=3)")
      // 3rd post-store call fires.
      const c6 = { args: {}, output: "" }
      await before({ tool: "Bash", sessionID: "sess-C", callID: "C6" }, c6)
      expect(c6.output.includes("[ar-hooks/memory-reminder]"), "C6 fires (counter=3 again)")
    } finally {
      if (prev === undefined) delete process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD
      else process.env.AR_MEMORY_REMIND_CALLS_THRESHOLD = prev
      if (prevWarm === undefined) delete process.env.AR_MEMORY_REMIND_WARMUP_CALLS
      else process.env.AR_MEMORY_REMIND_WARMUP_CALLS = prevWarm
      if (prevCo === undefined) delete process.env.AR_MEMORY_REMIND_COOLDOWN_CALLS
      else process.env.AR_MEMORY_REMIND_COOLDOWN_CALLS = prevCo
    }
  }

  // 7. The .after handler is a no-op (does not throw, does not
  //    mutate output).
  {
    const out = { title: "Bash", output: "(original output)", metadata: {} }
    await after({ tool: "Bash", sessionID: "sess-X", callID: "X1", args: { command: "ls" } }, out)
    expect(out.output === "(original output)", ".after does not mutate output")
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
