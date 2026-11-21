// .opencode/plugins/__tests__/block-excessive-comments.smoke.cjs
//
// Plain-Node smoke test for the block-excessive-comments opencode adapter's
// wiring. It feeds the Python shared core the two payload shapes the plugin
// emits -- an `apply_patch` Add File as whole `content`, and an `apply_patch`
// Update File as the context-bearing `edits` its hunks become -- against a
// real file in a project directory, and checks the core judges the resulting
// file (so extending an existing wall is charged, not a one-line fragment).
//
// Run from the repo root:
//   node .opencode/plugins/__tests__/block-excessive-comments.smoke.cjs

const { spawnSync } = require("node:child_process")
const path = require("node:path")
const fs = require("node:fs")
const os = require("node:os")

const CORE = path.resolve(__dirname, "..", "..", "..", ".claude", "hooks", "lib", "inline_comment_check.py")

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

function allowed(toolInput, cwd) {
  const r = spawnSync("python3", [CORE, "--stdin"], {
    input: JSON.stringify({ tool_input: toolInput }),
    encoding: "utf-8",
    cwd,
    timeout: 10000,
  })
  return r.status === 0
}

const project = fs.mkdtempSync(path.join(os.tmpdir(), "bec-smoke-"))
const wall3 = ["\t\t// wall a", "\t\t// wall b", "\t\t// wall c"].join("\n")
const file = ["package p;", "", "/** A. */", "class A {", "\t/** M. */", "\tvoid m() {",
  wall3, "\t\tint x = 1;", "\t}", "}"].join("\n") + "\n"
fs.writeFileSync(path.join(project, "A.java"), file)

console.log("== Update File becomes context-bearing edits, judged on the result ==")
{
  // The hunk `  // wall c` / `+ // wall d` / `  int x = 1;` reconstructs as a
  // single replacement whose context the core finds in the real file.
  const grow = { file_path: "A.java", edits: [{
    old_string: "\t\t// wall c\n\t\tint x = 1;",
    new_string: "\t\t// wall c\n\t\t// wall d\n\t\tint x = 1;",
  }] }
  expect(!allowed(grow, project), "growing a three-comment wall to four blocks")

  const benign = { file_path: "A.java", edits: [{ old_string: "int x = 1;", new_string: "int x = 2;" }] }
  expect(allowed(benign, project), "a change touching no comment is allowed")
}

console.log("== Add File becomes whole content ==")
{
  const addWall = { file_path: "B.java", content: ["package p;", "class B {", "\t// a", "\t// b",
    "\t// c", "\t// d", "\t// e", "\tint x;", "}"].join("\n") }
  expect(!allowed(addWall, project), "a new file with a five-comment wall blocks")

  const addClean = { file_path: "C.java", content: "package p;\nclass C { int x; }\n" }
  expect(allowed(addClean, project), "a clean new file is allowed")
}

console.log("== the project directory is load-bearing ==")
{
  // In the project directory the core finds the real file and its existing
  // wall, so the grown wall is charged.
  const grow = { file_path: "A.java", edits: [{
    old_string: "\t\t// wall c\n\t\tint x = 1;",
    new_string: "\t\t// wall c\n\t\t// wall d\n\t\tint x = 1;",
  }] }
  expect(allowed(grow, project) === false, "with the right cwd the wall is seen and charged")
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`)
  process.exit(1)
}
console.log("\nAll block-excessive-comments opencode adapter wiring assertions passed.")
