// .opencode/plugins/__tests__/block-branch-track-master.smoke.cjs
//
// Plain-Node smoke test for the block-branch-track-master opencode
// adapter's wiring. Runs without any TypeScript or Bun tooling. It
// exercises the same Python shared core the .ts plugin uses, and
// verifies the input-shape expectations the .ts plugin relies on. The
// .ts plugin itself is loaded by opencode via Bun at startup; the .ts
// file is verified at that point (and by block-branch-track-master.test.ts).
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-branch-track-master.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const HERE = __dirname
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "git_command_check.py")

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

function callCore(command) {
  // Same invocation the .ts plugin uses.
  const r = spawnSync("python3", [CORE, "block-branch-track-master", command], { encoding: "utf-8", timeout: 5000 })
  if (r.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: `core exit ${r.status}` }
  }
  try {
    return JSON.parse(r.stdout)
  } catch {
    return { action: "allow", reason: "", context: "", stderr: "core returned non-JSON" }
  }
}

console.log("== block path: branch off a remote master ref ==")
{
  const d = callCore("git checkout -b feature origin/master")
  expect(d.action === "block", "checkout -b feature origin/master → block")
  expect(d.reason.includes("BLOCKED"), "block reason says BLOCKED")
  expect(d.reason.includes("--no-track"), "block reason suggests --no-track")
}

console.log("== allow path: the corrected --no-track form ==")
{
  const d = callCore("git checkout -b feature --no-track origin/master")
  expect(d.action === "allow", "--no-track form → allow")
}

console.log("== allow path: branch off local master ==")
{
  expect(callCore("git checkout -b feature master").action === "allow", "off local master → allow")
}

console.log("== block path: push that overwrites master ==")
{
  expect(callCore("git push origin HEAD:master").action === "block", "push HEAD:master → block")
}

console.log("== allow path: push a branch under its own name ==")
{
  expect(callCore("git push -u origin feature").action === "allow", "push -u origin feature → allow")
}

console.log("== unrelated commands pass through ==")
{
  expect(callCore("git status").action === "allow", "git status → allow")
  expect(callCore("ls -la").action === "allow", "ls → allow")
  expect(callCore("").action === "allow", "empty → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll block-branch-track-master opencode adapter wiring assertions passed.")
