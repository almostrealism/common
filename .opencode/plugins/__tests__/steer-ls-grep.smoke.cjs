// .opencode/plugins/__tests__/steer-ls-grep.smoke.cjs
//
// Plain-Node smoke test for the opencode adapter's wiring. This runs
// without any TypeScript or Bun tooling. It exercises the same Python
// shared core the .ts plugin uses, and verifies the input-shape
// expectations that the .ts plugin relies on. The .ts plugin itself
// is loaded by opencode via Bun at startup; the .ts file is verified
// at that point.
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/steer-ls-grep.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")

const HERE = __dirname
const CORE = path.resolve(HERE, "..", "..", "..", ".claude", "hooks", "lib", "steer_ls_grep_check.py")

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

console.log("== tool.execute.before: ls blocks ==")
{
  const cases = [
    "ls",
    "ls docs/",
    "ls .",
    "ls *.py",
  ]
  for (const c of cases) {
    const d = callCore(c)
    expect(d.action === "block", `\`${c}\` → block`)
    expect(d.reason.includes("`glob`"), `block reason for \`${c}\` points at the glob tool`)
  }
}

console.log("== tool.execute.before: ls allows ==")
{
  const cases = [
    "ls -la",
    "ls -l",
    "ls -lh",
    "ls -R",
    "ls -d",
    "ls -F",
    "ls -l docs/",
    "ls | head -5",
    "ls docs/ && echo done",
  ]
  for (const c of cases) {
    const d = callCore(c)
    expect(d.action === "allow", `\`${c}\` → allow`)
  }
}

console.log("== tool.execute.before: grep/rg blocks ==")
{
  const cases = [
    "grep pattern file.txt",
    "grep -n pattern src/",
    "rg pattern file.txt",
    "rg -n pattern src/",
    "egrep 'foo|bar' file.txt",
    "fgrep 'literal' file.txt",
  ]
  for (const c of cases) {
    const d = callCore(c)
    expect(d.action === "block", `\`${c}\` → block`)
    expect(d.reason.includes("`grep`"), `block reason for \`${c}\` points at the grep tool`)
  }
}

console.log("== tool.execute.before: grep/rg allows ==")
{
  const cases = [
    "grep -P 'p' f",
    "grep --include='*.java' -r pattern src/",
    "rg --pcre2 'foo|bar' file.txt",
    "grep -l pattern src/",
    "grep -c pattern src/",
    "grep -A 2 pattern file.txt",
    "grep pattern file.txt | head -20",
    "grep -r pattern src/ | xargs sed -i 's/x/y/'",
  ]
  for (const c of cases) {
    const d = callCore(c)
    expect(d.action === "allow", `\`${c}\` → allow`)
  }
}

console.log("== Core is fault-tolerant on bad input ==")
{
  // Empty / whitespace is allowed.
  expect(callCore("").action === "allow", "empty → allow")
  expect(callCore("   ").action === "allow", "whitespace → allow")
  // Unparseable is allowed (we don't block on parse errors).
  expect(callCore("ls 'unterminated").action === "allow", "unparseable → allow")
  // Unrelated command is allowed.
  expect(callCore("mvn install -DskipTests").action === "allow", "mvn → allow")
  expect(callCore("git status").action === "allow", "git status → allow")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll opencode adapter wiring assertions passed.")
