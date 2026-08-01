// .opencode/plugins/__tests__/block-git-worktree.smoke.cjs
//
// Plain-Node smoke test for the block-git-worktree opencode
// adapter's wiring. Mirrors block-git-commit.smoke.cjs.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-git-worktree.smoke.cjs

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
  const r = spawnSync("python3", [CORE, "block-git-worktree", command], { encoding: "utf-8", timeout: 5000 })
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
  const d = callCore("git worktree add /tmp/wt")
  expect(d.action === "block", "git worktree add /tmp/wt → block")
  expect(d.reason.includes("BLOCKED"), "block reason says BLOCKED")
  expect(d.reason.includes("working tree"), "block reason mentions the working tree rule")
}

console.log("== tool.execute.before: allow path ==")
{
  const d = callCore("git worktree list")
  expect(d.action === "allow", "git worktree list → allow (read-only)")
}

console.log("== git worktree remove ==")
{
  const d = callCore("git worktree remove /tmp/wt")
  expect(d.action === "allow", "git worktree remove → allow (read-only-ish)")
}

console.log("== git worktree prune ==")
{
  const d = callCore("git worktree prune")
  expect(d.action === "allow", "git worktree prune → allow")
}

console.log("== git worktree --flag add ==")
{
  const d = callCore("git worktree --track add /tmp/wt")
  expect(d.action === "block", "git worktree --track add → block")
}

console.log("== Non-worktree commands pass through ==")
{
  expect(callCore("git status").action === "allow", "git status → allow")
  expect(callCore("ls -la").action === "allow", "ls → allow")
  expect(callCore("").action === "allow", "empty → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll block-git-worktree opencode adapter wiring assertions passed.")
