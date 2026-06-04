// .opencode/plugins/__tests__/block-mvn-test-direct.smoke.cjs
//
// Plain-Node smoke test for the opencode adapter's wiring. This runs
// without any TypeScript or Bun tooling. It exercises the same Python
// shared core the .ts plugin uses, and verifies the input-shape
// expectations that the .ts plugin relies on. The .ts plugin itself
// is loaded by opencode via Bun at startup; the .ts file is verified
// at that point.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-mvn-test-direct.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const HERE = __dirname
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "mvn_test_check.py")

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
  const r = spawnSync("python3", [CORE, command], { encoding: "utf-8", timeout: 5000 })
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
  const d = callCore("mvn test")
  expect(d.action === "block", "mvn test → block")
  expect(d.reason.includes("mcp__ar-test-runner__start_test_run"), "block reason points at the test runner")
  // The .ts plugin's contract: throw new Error(d.reason || d.stderr)
  // on `action === "block"`. We don't have a way to test `throw` from
  // plain JS without replicating the .ts file; what we CAN test is that
  // the reason string is non-empty and contains the expected text.
}

console.log("== tool.execute.before: allow path ==")
{
  const d = callCore("mvn install -DskipTests")
  expect(d.action === "allow", "mvn install -DskipTests → allow")
  // The .ts plugin's contract: no throw on `action === "allow"`.
}

console.log("== tool.execute.before: bash -c recursion ==")
{
  const d = callCore('bash -c "mvn test"')
  expect(d.action === "block", "bash -c 'mvn test' → block via recursion")
}

console.log("== tool.execute.after: warn path ==")
{
  const d = callCore("awk 'BEGIN { mvn test")
  expect(d.action === "warn", "unbalanced quote with mvn+test → warn")
  expect(d.context.includes("mcp__ar-test-runner__start_test_run"), "warn context points at the test runner")
  // The .ts plugin's contract: append `[ar-hooks] ${d.context || d.stderr}`
  // to output.output on `action === "warn"`. Verify the source text is
  // non-empty so the prefix-suffix concatenation has something to say.
}

console.log("== tool.execute.after: allow path leaves output unchanged ==")
{
  const d = callCore("ls -la")
  expect(d.action === "allow", "ls -la → allow")
  // The .ts plugin's contract: no mutation of output.output on allow.
}

console.log("== Core is fault-tolerant on bad input ==")
{
  // Empty command is allowed; whitespace too.
  expect(callCore("").action === "allow", "empty → allow")
  expect(callCore("   ").action === "allow", "whitespace → allow")
  // Unparseable but no mvn/test is still allowed (no warn).
  expect(callCore("awk 'BEGIN { print").action === "allow", "unbalanced, no mvn → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll opencode adapter wiring assertions passed.")
