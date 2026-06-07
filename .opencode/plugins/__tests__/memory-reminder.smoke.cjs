// .opencode/plugins/__tests__/memory-reminder.smoke.cjs
//
// Plain-Node smoke test for the opencode adapter's wiring. This runs
// without any TypeScript or Bun tooling. It exercises the same Python
// shared core the .ts plugin uses, and verifies the input-shape
// expectations that the .ts plugin relies on. The .ts plugin itself
// is loaded by opencode via Bun at startup; the .ts file is verified
// at that point.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/memory-reminder.smoke.cjs
//
// Each test sets its own AR_MEMORY_REMIND_* env vars via spawnSync
// so the run is hermetic and does not depend on the test runner's
// environment.

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const HERE = __dirname
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "memory_reminder_check.py")

if (!fs.existsSync(CORE)) {
  console.error("shared core not found at", CORE)
  process.exit(2)
}

let failures = 0
function expect(cond, msg) {
  if (!cond) {
    console.error("  FAIL:", msg)
    failures++
  }
}

function callCoreWithEnv(tool, nowTs, state, env) {
  const r = spawnSync("python3", [CORE, tool, String(nowTs), JSON.stringify(state || {})], {
    encoding: "utf-8",
    timeout: 5000,
    env: { ...process.env, ...env },
  })
  if (r.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: `core exit ${r.status}`, new_state: null }
  }
  try {
    return JSON.parse(r.stdout)
  } catch {
    return { action: "allow", reason: "", context: "", stderr: "core returned non-JSON", new_state: null }
  }
}

const freshState = () => ({
  calls_since_last_store: 0,
  last_store_ts: 0,
  last_remind_ts: 0,
  calls_at_last_remind: 0,
  session_start_ts: 0,
})

const envFastFire = {
  AR_MEMORY_REMIND_CALLS_THRESHOLD: "3",
  AR_MEMORY_REMIND_WARMUP_CALLS: "0",
  AR_MEMORY_REMIND_BACKOFF_CALLS: "0",
}

console.log("== Read tool does not increment counter ==")
{
  const d = callCoreWithEnv("Read", 1000, freshState(), envFastFire)
  expect(d.action === "allow", "Read should be allow (no fire)")
  expect(d.new_state.calls_since_last_store === 0, "Read does not increment")
}

console.log("== opencode read does not increment counter ==")
{
  const d = callCoreWithEnv("read", 1000, freshState(), envFastFire)
  expect(d.action === "allow", "opencode read should be allow")
  expect(d.new_state.calls_since_last_store === 0, "opencode read does not increment")
}

console.log("== Bash counts as side effect ==")
{
  let state = freshState()
  let last
  for (let i = 0; i < 4; i++) {
    last = callCoreWithEnv("Bash", 1000 + i, state, envFastFire)
    state = last.new_state
  }
  expect(last.action === "warn", "4th Bash call should fire (threshold=3, backoff=0)")
  expect(state.calls_since_last_store === 4, "counter at 4 after 4 calls")
}

console.log("== mcp__ar-manager__memory_store resets the counter ==")
{
  let state = freshState()
  for (let i = 0; i < 4; i++) {
    const d = callCoreWithEnv("Bash", 1000 + i, state, envFastFire)
    state = d.new_state
  }
  const d = callCoreWithEnv("mcp__ar-manager__memory_store", 1004, state, envFastFire)
  expect(d.action === "allow", "memory_store is allow")
  expect(d.new_state.calls_since_last_store === 0, "memory_store resets counter")
  expect(d.new_state.last_store_ts === 1004, "memory_store stamps last_store_ts")
}

console.log("== mcp__ar-manager__memory_recall does NOT reset ==")
{
  let state = freshState()
  for (let i = 0; i < 3; i++) {
    const d = callCoreWithEnv("Bash", 1000 + i, state, envFastFire)
    state = d.new_state
  }
  const before = state.calls_since_last_store
  const d = callCoreWithEnv("mcp__ar-manager__memory_recall", 1003, state, envFastFire)
  expect(d.action === "allow", "memory_recall is allow")
  expect(d.new_state.calls_since_last_store === before, "memory_recall does not change counter")
  expect(d.new_state.last_store_ts === 0, "memory_recall does not stamp last_store_ts")
}

console.log("== mcp__ar-consultant__remember resets ==")
{
  let state = freshState()
  for (let i = 0; i < 3; i++) {
    const d = callCoreWithEnv("Bash", 1000 + i, state, envFastFire)
    state = d.new_state
  }
  const d = callCoreWithEnv("mcp__ar-consultant__remember", 1003, state, envFastFire)
  expect(d.new_state.calls_since_last_store === 0, "consultant remember resets counter")
  expect(d.new_state.last_store_ts === 1003, "consultant remember stamps last_store_ts")
}

console.log("== AR_MEMORY_REMIND_DISABLED=1 suppresses everything ==")
{
  const disabled = { ...envFastFire, AR_MEMORY_REMIND_DISABLED: "1" }
  let state = freshState()
  let last
  for (let i = 0; i < 20; i++) {
    last = callCoreWithEnv("Bash", 1000 + i, state, disabled)
    state = last.new_state
  }
  expect(last.action === "allow", "disabled: never fires")
}

console.log("== Edit tool counts as side effect ==")
{
  let state = freshState()
  let last
  for (let i = 0; i < 4; i++) {
    last = callCoreWithEnv("Edit", 2000 + i, state, envFastFire)
    state = last.new_state
  }
  expect(last.action === "warn", "Edit fires at threshold")
}

console.log("== ar-docs tools are read-only ==")
{
  const d = callCoreWithEnv("mcp__ar-docs__read_ar_guidelines", 1000, freshState(), envFastFire)
  expect(d.action === "allow", "ar-docs tool is allow")
  expect(d.new_state.calls_since_last_store === 0, "ar-docs tool does not increment")
}

console.log("== Warn path returns a context string ==")
{
  let state = freshState()
  for (let i = 0; i < 3; i++) {
    const d = callCoreWithEnv("Bash", 1000 + i, state, envFastFire)
    state = d.new_state
  }
  const d = callCoreWithEnv("Bash", 1003, state, envFastFire)
  expect(d.action === "warn", "warn path reached")
  expect(typeof d.context === "string" && d.context.length > 0, "warn context is non-empty")
  expect(d.context.includes("memory_store"), "warn context mentions memory_store")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll opencode adapter wiring assertions passed.")
