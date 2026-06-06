// .opencode/plugins/__tests__/block-checkstyle-edit.smoke.cjs
//
// Plain-Node smoke test for the block-checkstyle-edit opencode
// adapter's wiring.  Mirrors block-mvn-test-direct.smoke.cjs.
// Runs without any TypeScript or Bun tooling — it exercises the
// same Python shared core the .ts plugin uses, and verifies the
// input-shape expectations the .ts plugin relies on.  The .ts
// plugin itself is loaded by opencode via Bun at startup; the
// .ts file is verified at that point.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-checkstyle-edit.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const HERE = __dirname
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "checkstyle_config_check.py")

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
  if (r.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: `core exit ${r.status}` }
  }
  try {
    return JSON.parse(r.stdout)
  } catch {
    return { action: "allow", reason: "", context: "", stderr: "core returned non-JSON" }
  }
}

console.log("== tool.execute.before: file-tool block path ==")
{
  const d = callCore({ tool: "write", filePath: "checkstyle.xml" })
  expect(d.action === "block", "write to checkstyle.xml → block")
  expect(d.reason.includes("FORBIDDEN"), "block reason says FORBIDDEN")
  expect(d.reason.includes("ABANDON"), "block reason says ABANDON")
  expect(d.reason.includes("checkstyle.xml"), "block reason names the target")
}

console.log("== tool.execute.before: file-tool suppressions block ==")
{
  const d = callCore({ tool: "edit", filePath: "checkstyle-suppressions.xml" })
  expect(d.action === "block", "edit checkstyle-suppressions.xml → block")
}

console.log("== tool.execute.before: bash redirect block ==")
{
  const d = callCore({ tool: "bash", command: "echo x > checkstyle.xml" })
  expect(d.action === "block", "echo x > checkstyle.xml → block")
  expect(d.reason.includes("FORBIDDEN"), "bash block reason says FORBIDDEN")
}

console.log("== tool.execute.before: bash sed -i block ==")
{
  const d = callCore({ tool: "bash", command: "sed -i 's/foo/bar/' checkstyle.xml" })
  expect(d.action === "block", "sed -i on checkstyle.xml → block")
}

console.log("== tool.execute.before: bash tee block ==")
{
  const d = callCore({ tool: "bash", command: "tee /tmp/checkstyle-suppressions.xml" })
  expect(d.action === "block", "tee to checkstyle-suppressions.xml → block")
}

console.log("== tool.execute.before: allow path ==")
{
  const d = callCore({ tool: "write", filePath: "README.md" })
  expect(d.action === "allow", "write to README.md → allow")
}

console.log("== tool.execute.before: allow bash (read-only) ==")
{
  const d = callCore({ tool: "bash", command: "cat checkstyle.xml" })
  expect(d.action === "allow", "cat checkstyle.xml → allow (read-only)")
}

console.log("== tool.execute.before: allow bash (unrelated) ==")
{
  const d = callCore({ tool: "bash", command: "echo hi > pom.xml" })
  expect(d.action === "allow", "echo hi > pom.xml → allow")
}

console.log("== Module-local checkstyle paths block ==")
{
  const d = callCore({ tool: "edit", filePath: "engine/ml/checkstyle.xml" })
  expect(d.action === "block", "module-local checkstyle.xml → block")
}

console.log("== /checkstyle/ directory paths block ==")
{
  const d = callCore({ tool: "write", filePath: "config/checkstyle/extra.xml" })
  expect(d.action === "block", "file in /checkstyle/ directory → block")
}

console.log("== Block message is forceful ALL-CAPS ==")
{
  const d = callCore({ tool: "write", filePath: "checkstyle.xml" })
  const phrases = [
    "MODIFYING CHECKSTYLE CONFIGURATION IS FORBIDDEN",
    "NEVER ACCEPTABLE",
    "ABANDON THE TASK",
    "ALWAYS PREFERABLE",
    "FIX THE CODE",
  ]
  for (const phrase of phrases) {
    expect(d.reason.includes(phrase), `block reason must contain: ${phrase}`)
  }
}

console.log("== Non-checkstyle .xml allows (no false positives) ==")
{
  expect(callCore({ tool: "write", filePath: "pom.xml" }).action === "allow", "pom.xml → allow")
  expect(callCore({ tool: "write", filePath: "settings.xml" }).action === "allow", "settings.xml → allow")
  expect(callCore({ tool: "write", filePath: "xcheckstyle.xml" }).action === "allow", "xcheckstyle.xml → allow (substring guard)")
  expect(callCore({ tool: "write", filePath: "suppressions.xml" }).action === "allow", "suppressions.xml → allow (needs both keywords)")
}

console.log("== Edge cases ==")
{
  expect(callCore({ tool: "write", filePath: "" }).action === "allow", "empty filePath → allow")
  expect(callCore({ tool: "write" }).action === "allow", "missing filePath → allow")
  expect(callCore({ tool: "bash" }).action === "allow", "missing command → allow")
  expect(callCore({ tool: "bash", command: "" }).action === "allow", "empty command → allow")
  expect(callCore({ tool: "some_other_tool", filePath: "checkstyle.xml" }).action === "allow", "unknown tool → allow (defensive)")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll block-checkstyle-edit opencode adapter wiring assertions passed.")
