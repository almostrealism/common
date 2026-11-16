// .opencode/plugins/__tests__/block-private-url.smoke.cjs
//
// Plain-Node smoke test for the block-private-url opencode adapter's wiring.
// It calls the Python shared core the .ts plugin uses, the same way (the
// payload in Claude Code's shape as one argument), and checks the decisions
// the plugin acts on.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-private-url.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const CORE = path.resolve(__dirname, "..", "..", "..", ".claude", "hooks", "lib", "private_url_check.py")
const LINK = "https://claude.ai/code/session_01AbCdEfGhIjKlMnOpQrStUv"

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

function callCore(payload) {
  const r = spawnSync("python3", [CORE, JSON.stringify(payload)], { encoding: "utf-8", timeout: 5000 })
  if (r.status !== 0) return { action: "allow" }
  try {
    return JSON.parse(r.stdout)
  } catch {
    return { action: "allow" }
  }
}

console.log("== block path ==")
{
  const d = callCore({ tool_name: "Bash", tool_input: { command: `gh pr create --body "${LINK}"` } })
  expect(d.action === "block", "session link in a PR body → block")
  expect((d.reason || "").includes("private link"), "block reason names a private link")
  expect(callCore({ tool_name: "Write", tool_input: { file_path: "commit.txt", content: LINK } }).action === "block",
    "session link in commit.txt → block")
  expect(callCore({ tool_name: "mcp__ar-manager__github_create_pr", tool_input: { body: LINK } }).action === "block",
    "session link through the GitHub tool → block")
}

console.log("== allow path ==")
{
  expect(callCore({ tool_name: "Bash", tool_input: { command: "git status" } }).action === "allow", "git status → allow")
  expect(callCore({ tool_name: "Write", tool_input: { file_path: "notes.md", content: LINK } }).action === "allow",
    "other file → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll block-private-url opencode adapter wiring assertions passed.")
