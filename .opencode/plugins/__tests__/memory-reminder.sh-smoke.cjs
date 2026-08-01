#!/usr/bin/env node
// .opencode/plugins/__tests__/memory-reminder.sh-smoke.cjs
//
// Plain-Node smoke test for the Claude Code .sh adapter. The
// adapter is a shell script that reads a Claude-Code-style JSON
// payload from stdin, persists per-session state to
// /tmp/.ar_memory_state_${USER}.json, and emits a JSON
// hookSpecificOutput.additionalContext on stdout when the
// threshold is crossed.
//
// This test drives the adapter by piping a synthetic payload into
// its stdin and asserts on (a) stdout content (the
// additionalContext JSON, if any) and (b) the persisted state
// file (so we can verify that state is correctly threaded across
// invocations).
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/memory-reminder.sh-smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")
const os = require("node:os")

const HERE = __dirname
const SH = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "memory-reminder.sh")
const STATE_FILE = `/tmp/.ar_memory_state_${process.env.USER || "developer"}.json`

if (!fs.existsSync(SH)) {
  console.error("adapter not found at", SH)
  process.exit(2)
}

let failures = 0
function expect(cond, msg) {
  if (!cond) {
    console.error("  FAIL:", msg)
    failures++
  }
}

function callAdapter(payload, env) {
  // Drive the .sh adapter by piping the payload to its stdin. The
  // env object overrides env vars for the subprocess.
  return spawnSync("bash", [SH], {
    input: JSON.stringify(payload),
    encoding: "utf-8",
    timeout: 10_000,
    env: { ...process.env, ...env },
  })
}

function readState() {
  if (!fs.existsSync(STATE_FILE)) return {}
  try {
    return JSON.parse(fs.readFileSync(STATE_FILE, "utf-8"))
  } catch {
    return {}
  }
}

function clearState() {
  try {
    fs.unlinkSync(STATE_FILE)
  } catch {}
}

// Reset before this test run.
clearState()

const envFastFire = {
  AR_MEMORY_REMIND_CALLS_THRESHOLD: "4",
  AR_MEMORY_REMIND_WARMUP_CALLS: "0",
  AR_MEMORY_REMIND_BACKOFF_CALLS: "0",
  AR_MEMORY_REMIND_COOLDOWN_CALLS: "0",
  AR_MEMORY_REMIND_BACKOFF_SECONDS: "1000000",
}

const sessionId = `smoke-${Date.now()}`

console.log("== First three Bash calls do not fire (threshold=4) ==")
{
  clearState()
  for (let i = 1; i <= 3; i++) {
    const r = callAdapter({ tool_name: "Bash", session_id: sessionId }, envFastFire)
    expect(r.status === 0, `call ${i} exits 0`)
    expect(r.stdout === "", `call ${i} has empty stdout (no fire yet)`)
  }
}

console.log("== Fourth Bash call fires (threshold=4) ==")
{
  const r = callAdapter({ tool_name: "Bash", session_id: sessionId }, envFastFire)
  expect(r.status === 0, "call 4 exits 0")
  expect(r.stdout.length > 0, "call 4 emits a non-empty stdout")
  let parsed
  try {
    parsed = JSON.parse(r.stdout.trim().split("\n").pop())
  } catch (e) {
    parsed = null
  }
  expect(parsed !== null, "call 4 stdout is valid JSON")
  expect(parsed && parsed.hookSpecificOutput, "call 4 stdout has hookSpecificOutput")
  expect(
    parsed && parsed.hookSpecificOutput && parsed.hookSpecificOutput.additionalContext.includes("memory_store"),
    "call 4 additionalContext mentions memory_store",
  )
}

console.log("== State file is persisted across invocations ==")
{
  const state = readState()
  expect(typeof state[sessionId] === "object", "session entry exists in state file")
  expect(state[sessionId] && state[sessionId].calls_since_last_store >= 4, "counter at 4+")
  expect(state[sessionId] && state[sessionId].last_remind_ts > 0, "last_remind_ts set")
}

console.log("== A memory_store resets the counter (no fire) ==")
{
  // Backoff is huge, but a memory_store should zero last_remind_ts
  // and the counter, so the next call won't fire on the backoff
  // path.
  const r = callAdapter(
    { tool_name: "mcp__ar-manager__memory_store", session_id: sessionId },
    envFastFire,
  )
  expect(r.status === 0, "memory_store exits 0")
  expect(r.stdout === "", "memory_store emits no additionalContext")
  const state = readState()
  expect(state[sessionId] && state[sessionId].calls_since_last_store === 0, "counter reset to 0")
  expect(state[sessionId] && state[sessionId].last_store_ts > 0, "last_store_ts set")
}

console.log("== Two different sessions have independent state ==")
{
  clearState()
  const sessX = `sessX-${Date.now()}`
  const sessY = `sessY-${Date.now()}`
  for (let i = 0; i < 4; i++) {
    callAdapter({ tool_name: "Bash", session_id: sessX }, envFastFire)
  }
  // sessY is fresh — its first three calls should not fire even
  // though sessX is well past the threshold.
  for (let i = 1; i <= 3; i++) {
    const r = callAdapter({ tool_name: "Bash", session_id: sessY }, envFastFire)
    expect(r.stdout === "", `sessY call ${i} does not fire (independent state)`)
  }
  // 4th call to sessY fires.
  const r = callAdapter({ tool_name: "Bash", session_id: sessY }, envFastFire)
  expect(r.stdout.length > 0, "sessY 4th call fires")
}

console.log("== Read tools do not increment ==")
{
  clearState()
  const sessR = `sessR-${Date.now()}`
  for (let i = 0; i < 5; i++) {
    const r = callAdapter({ tool_name: "Read", session_id: sessR }, envFastFire)
    expect(r.stdout === "", `Read call ${i + 1} does not fire`)
  }
  const state = readState()
  expect(state[sessR] && state[sessR].calls_since_last_store === 0, "counter stays at 0 for reads")
}

console.log("== AR_MEMORY_REMIND_DISABLED=1 suppresses everything ==")
{
  clearState()
  const sessD = `sessD-${Date.now()}`
  const env = { ...envFastFire, AR_MEMORY_REMIND_DISABLED: "1" }
  for (let i = 0; i < 10; i++) {
    const r = callAdapter({ tool_name: "Bash", session_id: sessD }, env)
    expect(r.stdout === "", `disabled: call ${i + 1} does not fire`)
  }
}

// Clean up the state file at the end so the test is hermetic.
clearState()

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll Claude Code .sh adapter wiring assertions passed.")
