// .opencode/plugins/_lib/command_pattern.ts
//
// Shared TypeScript helper for the Class A opencode plugin adapters
// (block-git-commit, block-git-worktree, warn-git-log). Mirrors the
// mvn_test_check.py's role for block-mvn-test-direct.
//
// Why a shared helper: the three Class A hooks are structurally
// identical — each pulls `command` out of the opencode `bash` tool's
// args, asks the shared Python core to decide, and either throws
// (block) or mutates `output.output` (warn). The only thing that
// differs is the `policy` name passed to the core. Centralizing the
// plumbing here means each per-hook plugin is ~15 lines.
//
// Performance: the .before and .after handlers run for the same call
// share a module-level Map<callID, Decision> cache. The .before
// handler populates the cache; the .after handler
// consumes-and-deletes it. This halves the per-call python3 subprocess
// count from 2 to 1 (a cold Python interpreter is 50–100ms, so this
// is ~50–100ms saved per Bash tool call when the warn path is hit).
// On any cache miss (e.g. .after ran without a matching .before,
// which can happen if .before's throw was somehow swallowed), the
// .after falls back to running the core again — fail-safe.
//
// See docs/plans/OPENCODE_HOOKS.md for the architecture.

import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

// The shared Python core. Resolved relative to this file so the
// helper works regardless of the cwd opencode was launched from.
// Path: .opencode/plugins/_lib/ → .claude/hooks/lib/git_command_check.py
const HERE = path.dirname(fileURLToPath(import.meta.url))
export const GIT_COMMAND_CORE = path.resolve(
  HERE,
  "..",
  "..",
  "..",
  ".claude",
  "hooks",
  "lib",
  "git_command_check.py",
)

export interface Decision {
  action: "block" | "allow" | "warn"
  reason: string
  context: string
  stderr: string
}

export type PolicyId = "block-git-commit" | "block-git-worktree" | "warn-git-log"

// Module-level callID → Decision cache. Populated in tool.execute.before,
// consumed-and-deleted in tool.execute.after for the same callID. This
// avoids a second python3 subprocess spawn for the warn path, where
// .before and .after both need the Decision. The cache key (callID) is
// unique per opencode tool invocation, so there's no cross-contamination
// risk between concurrent tool calls.
//
// Why module-level (not closure-scoped): all plugins in this directory
// share the same Map. The cache key is namespaced by policy so plugins
// can't see each other's decisions (a "block-git-commit" call and a
// "block-git-worktree" call happen in separate handlers and their
// Decisions are different shapes of Decision — but the cache key uses
// `${policy}:${callID}` to be safe).
const decisionCache = new Map<string, Decision>()
const cacheKey = (policy: PolicyId, callID: string) => `${policy}:${callID}`

/**
 * Call the shared core for the given command and policy. Returns a
 * Decision object. On any internal error (Python missing, core
 * crashed, bad JSON), returns an "allow" Decision so a hook
 * malfunction can never block legitimate work.
 */
export function callGitCommandCore(command: string, policy: PolicyId): Decision {
  const result = spawnSync("python3", [GIT_COMMAND_CORE, policy, command], {
    encoding: "utf-8",
    timeout: 5_000,
  })

  if (result.error) {
    return { action: "allow", reason: "", context: "", stderr: `core spawn failed: ${result.error.message}` }
  }
  if (result.status !== 0) {
    return { action: "allow", reason: "", context: "", stderr: `core exited ${result.status}: ${result.stderr}` }
  }

  try {
    return JSON.parse(result.stdout) as Decision
  } catch (e) {
    return { action: "allow", reason: "", context: "", stderr: `core returned non-JSON: ${String(e)}` }
  }
}

/**
 * Best-effort logging. The plugin API gives us `client.app.log` so
 * the line shows up in opencode's structured log under the
 * `ar-hooks` service. If logging fails for any reason, we just
 * swallow it — a logging failure must never affect the policy
 * decision.
 */
function logDecision(policy: PolicyId, decision: Decision): void {
  if (!decision.stderr) return
  // eslint-disable-next-line no-console
  console.error(`[ar-hooks/${policy}] ${decision.stderr.trim()}`)
}

/**
 * Extract the bash command from a `bash` tool's args object. The
 * opencode `bash` tool's schema is `{ command: string, ... }` — same
 * field name as Claude Code, so no rename is needed. We tolerate any
 * shape and return an empty string if `command` is missing, which
 * the core treats as "allow".
 */
function extractBashCommand(args: unknown): string {
  if (!args || typeof args !== "object") return ""
  const obj = args as { command?: unknown }
  return typeof obj.command === "string" ? obj.command : ""
}

/**
 * Build the opencode plugin for a single Class A policy. The returned
 * Plugin has both `tool.execute.before` (decides and throws on
 * block) and `tool.execute.after` (decides and mutates
 * `output.output` on warn). Both handlers share a Decision cache
 * keyed by callID so the python3 subprocess runs at most once per
 * tool call.
 *
 * Usage:
 *   export const BlockGitCommitPlugin = makeCommandPatternPlugin("block-git-commit")
 *   export const BlockGitWorktreePlugin = makeCommandPatternPlugin("block-git-worktree")
 *   export const WarnGitLogPlugin = makeCommandPatternPlugin("warn-git-log")
 */
export function makeCommandPatternPlugin(policy: PolicyId): Plugin {
  return async () => ({
    "tool.execute.before": async (input, output) => {
      if (input.tool !== "bash") return

      const command = extractBashCommand(output.args)
      if (!command) return

      const decision = callGitCommandCore(command, policy)
      // Populate the cache so .after for the same callID can re-use
      // the Decision without a second subprocess spawn. We delete any
      // stale entry first (callID reuse is rare but possible if
      // opencode re-issues an ID after a long delay).
      decisionCache.set(cacheKey(policy, input.callID), decision)
      logDecision(policy, decision)

      if (decision.action === "block") {
        // opencode's plugin runner propagates thrown errors as the
        // tool's failure message — which is what the model sees as
        // the block reason. This matches Claude Code's "exit 2 with
        // the reason on stderr" semantics. We delete the cache entry
        // here because .after will not run for a tool that threw in
        // .before.
        decisionCache.delete(cacheKey(policy, input.callID))
        throw new Error(decision.reason || decision.stderr)
      }
      // "warn" is deferred to the .after hook: the model should see
      // the warning alongside (not before) the (presumably empty)
      // tool output. The Decision is already in the cache.
    },

    "tool.execute.after": async (input, output) => {
      if (input.tool !== "bash") return

      const key = cacheKey(policy, input.callID)
      const cached = decisionCache.get(key)
      if (cached !== undefined) {
        // Fast path: .before ran for this callID and we have its
        // Decision cached. Consume-and-delete so the Map doesn't
        // grow without bound.
        decisionCache.delete(key)
        if (cached.action === "warn") {
          const note = cached.context || cached.stderr
          if (note) {
            output.output = `${output.output}\n\n[ar-hooks/${policy}] ${note}`
          }
        }
        return
      }

      // Slow path: no cache entry. This can happen if the plugin
      // was reloaded mid-call, or if .before's throw was swallowed
      // by some external handler. Re-run the core to stay correct.
      // (We do NOT throw on cache miss — that would block legitimate
      // work on a transient plugin-state issue.)
      const command = extractBashCommand(input.args)
      if (!command) return

      const decision = callGitCommandCore(command, policy)
      if (decision.action === "warn") {
        const note = decision.context || decision.stderr
        if (note) {
          output.output = `${output.output}\n\n[ar-hooks/${policy}] ${note}`
        }
      }
    },
  })
}
