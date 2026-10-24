// .opencode/plugins/__tests__/block-pytest-broad-run.smoke.cjs
//
// Plain-Node smoke test for the block-pytest-broad-run opencode adapter's
// wiring. It calls the same Python shared core the .ts plugin uses, the
// same way, and checks the decisions the plugin acts on.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-pytest-broad-run.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const CORE = path.resolve(__dirname, "..", "..", "..", ".claude", "hooks", "lib", "pytest_broad_run_check.py")

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
  const r = spawnSync("python3", [CORE, command], { encoding: "utf-8", timeout: 5000 })
  if (r.status !== 0) return { action: "allow", reason: "" }
  try {
    return JSON.parse(r.stdout)
  } catch {
    return { action: "allow", reason: "" }
  }
}

console.log("== block path ==")
{
  const d = callCore("python3 -m pytest tools/")
  expect(d.action === "block", "pytest on a directory → block")
  expect(d.reason.includes("BLOCKED"), "block reason says BLOCKED")
}

console.log("== allow path ==")
{
  expect(callCore("python3 -m pytest tools/tests/test_x.py").action === "allow", "one test file → allow")
  expect(callCore("pytest t/test_x.py::test_y").action === "allow", "node id → allow")
  expect(callCore("ls -la").action === "allow", "ls → allow")
  expect(callCore("").action === "allow", "empty → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll block-pytest-broad-run opencode adapter wiring assertions passed.")
