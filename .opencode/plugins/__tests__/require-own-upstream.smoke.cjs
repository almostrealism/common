/**
 * Plain-Node smoke test for the require-own-upstream opencode adapter's
 * wiring. It drives the same Python shared core the .ts plugin calls, with the
 * same argv shape ([tool, command, cwd]), against a scratch repository whose
 * feature branch is configured to track origin/master.
 *
 * Run from the repo root:
 *   node .opencode/plugins/__tests__/require-own-upstream.smoke.cjs
 */

const { spawnSync } = require("node:child_process")
const fs = require("node:fs")
const os = require("node:os")
const path = require("node:path")

const CORE = path.resolve(__dirname, "..", "..", "..", ".claude", "hooks", "lib", "branch_upstream_check.py")

let failures = 0
function expect(cond, msg) {
  if (!cond) {
    console.error("  FAIL:", msg)
    failures++
  }
}

function git(cwd, ...args) {
  const r = spawnSync("git", ["-C", cwd, ...args], { encoding: "utf-8" })
  if (r.status !== 0) throw new Error(`git ${args.join(" ")}: ${r.stderr}`)
}

function decide(tool, command, cwd) {
  const r = spawnSync("python3", [CORE, tool, command, cwd], { encoding: "utf-8", timeout: 5000 })
  return r.status === 0 ? JSON.parse(r.stdout) : { action: "allow" }
}

const repo = fs.mkdtempSync(path.join(os.tmpdir(), "own-upstream-"))
try {
  git(repo, "init", "-q", "-b", "master")
  git(repo, "remote", "add", "origin", "https://example.invalid/repo.git")
  git(repo, "checkout", "-q", "-b", "feature/x")
  git(repo, "config", "branch.feature/x.remote", "origin")
  git(repo, "config", "branch.feature/x.merge", "refs/heads/master")

  console.log("== mistracked branch blocks every tool ==")
  expect(decide("read", "", repo).action === "block", "read → block")
  expect(decide("bash", "git push", repo).action === "block", "bash git push → block")

  console.log("== the fix is allowed and clears the block ==")
  expect(decide("bash", "git branch --unset-upstream", repo).action === "allow", "unset-upstream → allow")
  git(repo, "branch", "--unset-upstream")
  expect(decide("read", "", repo).action === "allow", "read after the fix → allow")
} finally {
  fs.rmSync(repo, { recursive: true, force: true })
}

if (failures) {
  console.error(`${failures} failure(s)`)
  process.exit(1)
}
console.log("ok")
