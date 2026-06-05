// .opencode/plugins/__tests__/warn-git-log.smoke.cjs
//
// Plain-Node smoke test for the warn-git-log opencode adapter's
// wiring. Mirrors block-git-commit.smoke.cjs, with the key
// difference that the action is "warn" not "block" — the .ts
// plugin's `tool.execute.after` handler is responsible for
// appending the reminder to `output.output`.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/warn-git-log.smoke.cjs

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
  const r = spawnSync("python3", [CORE, "warn-git-log", command], { encoding: "utf-8", timeout: 5000 })
  if (r.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: `core exit ${r.status}` }
  }
  try {
    return JSON.parse(r.stdout)
  } catch {
    return { action: "allow", reason: "", context: "", stderr: "core returned non-JSON" }
  }
}

console.log("== tool.execute.after: warn path ==")
{
  const d = callCore("git log --oneline -5")
  expect(d.action === "warn", "git log --oneline -5 → warn")
  expect(d.context.includes("memory_branch_context"), "warn context points at memory_branch_context")
  // The .ts plugin's contract: append `[ar-hooks/warn-git-log] ${d.context || d.stderr}`
  // to output.output on `action === "warn"`. Verify the source text
  // is non-empty so the prefix-suffix concatenation has something to say.
}

console.log("== tool.execute.before/after: allow path ==")
{
  const d = callCore("git shortlog")
  expect(d.action === "allow", "git shortlog → allow (not 'git log')")
  // The .ts plugin's contract: no mutation of output.output on allow.
}

console.log("== git log with various flags ==")
{
  expect(callCore("git log").action === "warn", "git log → warn")
  expect(callCore("git log -p").action === "warn", "git log -p → warn")
  expect(callCore("git --no-pager log").action === "warn", "git --no-pager log → warn")
  expect(callCore("git log --all --graph").action === "warn", "git log --all --graph → warn")
}

console.log("== Non-log commands pass through ==")
{
  expect(callCore("git status").action === "allow", "git status → allow")
  expect(callCore("git commit -m x").action === "allow", "git commit → allow")
  expect(callCore("ls -la").action === "allow", "ls → allow")
  expect(callCore("").action === "allow", "empty → allow")
}

console.log("== log in path/name doesn't trigger ==")
{
  expect(callCore("git log.txt").action === "allow", "git log.txt → allow")
  expect(callCore("cat git/log").action === "allow", "cat git/log → allow")
  expect(callCore("./log").action === "allow", "./log → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll warn-git-log opencode adapter wiring assertions passed.")
