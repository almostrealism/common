// .opencode/plugins/__tests__/block-vendor-promotion.smoke.cjs
//
// Plain-Node smoke test for the block-vendor-promotion opencode adapter's
// wiring. It calls the Python shared core the .ts plugin uses, the same way
// (the payload in Claude Code's shape as one argument), and checks the
// decisions the plugin acts on.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-vendor-promotion.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const CORE = path.resolve(__dirname, "..", "..", "..", ".claude", "hooks", "lib", "vendor_promotion_check.py")
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
  const d = callCore({ tool_name: "Write", tool_input: { file_path: "docs/notes.md", content: LINK } })
  expect(d.action === "block", "session link written to a document → block")
  expect((d.reason || "").includes("vendor-promotion-log.md"), "block reason points at the log")
  expect(callCore({ tool_name: "Edit",
    tool_input: { file_path: "A.java", old_string: "", new_string: "Claude-Session: x" } }).action === "block",
    "attribution trailer added by an edit → block")
  expect(callCore({ tool_name: "mcp__Claude_Docs__create", tool_input: {} }).action === "block",
    "document written to the claude.ai connector → block")
}

console.log("== allow path ==")
{
  expect(callCore({ tool_name: "Write", tool_input: { file_path: "docs/notes.md", content: "plain" } }).action === "allow",
    "plain document → allow")
  expect(callCore({ tool_name: "Write", tool_input: { file_path: "src/test/A.java", content: LINK } }).action === "allow",
    "test source fixture → allow")
  expect(callCore({ tool_name: "mcp__Claude_Docs__read", tool_input: {} }).action === "allow",
    "connector read → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll block-vendor-promotion opencode adapter wiring assertions passed.")
