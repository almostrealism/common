// .opencode/plugins/__tests__/warn-mvn-build.smoke.cjs
//
// Plain-Node smoke test for the warn-mvn-build opencode adapter wiring.
// Exercises the Python shared core (mvn_build_check.py) via the same
// argv path the .ts plugin uses and verifies the input-shape
// expectations the .ts plugin relies on. The .ts plugin itself is
// loaded by opencode via Bun at startup and is verified at that point.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/warn-mvn-build.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const HERE = __dirname
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "mvn_build_check.py")

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
  if (r.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: `core exit ${r.status}` }
  }
  try {
    return JSON.parse(r.stdout)
  } catch {
    return { action: "allow", reason: "", context: "", stderr: "core returned non-JSON" }
  }
}

console.log("== tool.execute.after: warn path for bare 'mvn install' ==")
{
  const d = callCore("mvn install")
  expect(d.action === "warn", "mvn install → warn")
  expect(d.context.includes("ar-build-validator"), "warn context steers toward ar-build-validator")
  expect(d.context.includes("does not block"), "warn note clarifies that the command is not blocked")
}

console.log("== tool.execute.after: warn path for 'mvn install -DskipTests' (the preflight seed pattern) ==")
{
  const d = callCore("mvn install -DskipTests")
  // We DO warn here even with -DskipTests — the steer is informational
  // and explicitly tells the agent this does not block the command.
  // Legitimate dependency-seeding invocations still run exactly as
  // written; the model just sees a small note in the bash output.
  expect(d.action === "warn", "mvn install -DskipTests → warn (informational only)")
  expect(d.context.includes("ar-build-validator"), "skipTests path still mentions ar-build-validator")
}

console.log("== tool.execute.after: warn path for ar-test-runner-style preflight seed ==")
{
  const d = callCore("mvn -pl engine/utils -am install -DskipTests -B")
  expect(d.action === "warn", "preflight seed pattern → warn")
}

console.log("== tool.execute.after: allow path for 'mvn test' ==")
{
  // `mvn test` is the responsibility of block-mvn-test-direct, not
  // this hook. We must not double-warn — and `test` is not in the
  // artifact goals list, so the steer correctly stays silent.
  const d = callCore("mvn test")
  expect(d.action === "allow", "mvn test → allow (handled by block-mvn-test-direct)")
}

console.log("== tool.execute.after: allow path for non-mvn commands ==")
{
  expect(callCore("ls -la").action === "allow", "ls → allow")
  expect(callCore("git status").action === "allow", "git status → allow")
  expect(callCore("npm install").action === "allow", "npm install → allow (no mvn token)")
}

console.log("== tool.execute.after: chained-command attribution ==")
{
  const d = callCore("npm install && mvn install")
  expect(d.action === "warn", "second segment 'mvn install' → warn even after npm install")
}

console.log("== Core is fault-tolerant on bad input ==")
{
  expect(callCore("").action === "allow", "empty → allow")
  expect(callCore("   ").action === "allow", "whitespace → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll warn-mvn-build adapter wiring assertions passed.")
