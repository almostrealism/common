// .opencode/plugins/__tests__/block-git-commit.smoke.cjs
//
// Plain-Node smoke test for the block-git-commit opencode adapter's
// wiring. This runs without any TypeScript or Bun tooling. It
// exercises the same Python shared core the .ts plugin uses, and
// verifies the input-shape expectations that the .ts plugin relies
// on. The .ts plugin itself is loaded by opencode via Bun at
// startup; the .ts file is verified at that point.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-git-commit.smoke.cjs

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
  const r = spawnSync("python3", [CORE, "block-git-commit", command], { encoding: "utf-8", timeout: 5000 })
  if (r.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: `core exit ${r.status}` }
  }
  try {
    return JSON.parse(r.stdout)
  } catch {
    return { action: "allow", reason: "", context: "", stderr: "core returned non-JSON" }
  }
}

console.log("== tool.execute.before: block path ==")
{
  const d = callCore("git commit -m 'fix bug'")
  expect(d.action === "block", "git commit -m 'fix bug' → block")
  expect(d.reason.includes("BLOCKED"), "block reason says BLOCKED")
  expect(d.reason.includes("git add"), "block reason points at git add")
  // The .ts plugin's contract: throw new Error(d.reason || d.stderr)
  // on `action === "block"`. We don't have a way to test `throw` from
  // plain JS without replicating the .ts file; what we CAN test is
  // that the reason string is non-empty and contains the expected
  // text.
}

console.log("== tool.execute.before: allow path ==")
{
  const d = callCore("git status")
  expect(d.action === "allow", "git status → allow")
}

console.log("== git commit --amend ==")
{
  const d = callCore("git commit --amend")
  expect(d.action === "block", "git commit --amend → block")
}

console.log("== git --no-pager commit ==")
{
  const d = callCore("git --no-pager commit -m x")
  expect(d.action === "block", "git --no-pager commit → block")
}

console.log("== Non-bash-relevant commands pass through ==")
{
  expect(callCore("ls -la").action === "allow", "ls → allow")
  expect(callCore("").action === "allow", "empty → allow")
  expect(callCore("   ").action === "allow", "whitespace → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll block-git-commit opencode adapter wiring assertions passed.")
