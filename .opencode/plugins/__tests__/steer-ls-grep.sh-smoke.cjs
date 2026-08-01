// .opencode/plugins/__tests__/steer-ls-grep.sh-smoke.cjs
//
// Plain-Node smoke test for the .sh adapter's wiring. This runs
// without any TypeScript or Bun tooling. It feeds a synthetic
// Claude-Code-style stdin JSON to the .sh wrapper and verifies
// the exit-code + stderr contract that the .sh adapter is
// responsible for.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/steer-ls-grep.sh-smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const HERE = __dirname
const SH = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "steer-ls-grep.sh")

if (!fs.existsSync(SH)) {
  console.error("shell adapter not found at", SH)
  process.exit(2)
}

let failures = 0
function expect(cond, msg) {
  if (!cond) {
    console.error("  FAIL:", msg)
    failures++
  }
}

function callSh(command) {
  // Build a Claude-Code-style hook payload.
  const payload = JSON.stringify({ tool_input: { command } })
  return spawnSync("bash", [SH], {
    input: payload,
    encoding: "utf-8",
    timeout: 5000,
  })
}

console.log("== .sh adapter: BLOCK path (exit 2) ==")
{
  const cases = [
    "ls",
    "ls docs/",
    "ls .",
    "grep pattern file.txt",
    "rg pattern file.txt",
  ]
  for (const c of cases) {
    const r = callSh(c)
    expect(r.status === 2, `\`${c}\` exits 2 (block)`)
    expect(r.stderr.includes("BLOCKED"), `\`${c}\` stderr starts with BLOCKED`)
  }
}

console.log("== .sh adapter: ALLOW path (exit 0, no stderr) ==")
{
  const cases = [
    "ls -la",
    "ls -l docs/",
    "ls | head -5",
    "grep -P 'p' f",
    "grep --include='*.java' -r pattern src/",
    "grep pattern file.txt | head -20",
    "mvn install -DskipTests",
    "git status",
  ]
  for (const c of cases) {
    const r = callSh(c)
    expect(r.status === 0, `\`${c}\` exits 0 (allow)`)
    expect(r.stderr.trim() === "", `\`${c}\` has no stderr on allow path`)
  }
}

console.log("== .sh adapter: malformed stdin exits 0 ==")
{
  const r = spawnSync("bash", [SH], { input: "not json", encoding: "utf-8", timeout: 5000 })
  expect(r.status === 0, "malformed stdin exits 0 (does not block legitimate work on parse error)")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll .sh adapter wiring assertions passed.")
