# OpenCode Hooks — Study, Plan, and Proof of Concept

**Status:** study + plan + one proof-of-concept implementation
**Branch:** `feature/opencode-hooks`
**Author:** coding agent session 9a40614a

---

## 0. Why this document exists

`common` has spent ~1.5 years building a catalog of Claude Code hooks under
`.claude/hooks/` that enforce project rules — blocking `git commit`, blocking
direct `mvn test`, warning on `git log`, scanning written Java for producer
violations, enforcing the consultant-first rule, etc. The harness running
those hooks has been Claude Code.

We are now running many FlowTree phases on **opencode** (the open-source
terminal coding agent from the same Anomaly Co. team that built Claude Code).
opencode has its own extensibility model — a **plugin** system, not a "hook"
system. None of the existing `.claude/hooks/` scripts run there. Agents on
opencode therefore lose every project-level guard that the `.claude/hooks/`
catalog provides.

This document:

1. **Catalogs** the existing `.claude/hooks/` and what each does.
2. **Documents** how opencode's plugin/hook system actually works, and where
   it differs from Claude Code's.
3. **Recommends** an adaptation strategy (shared-core + thin per-harness
   adapters) and walks through how divergent I/O contracts get reconciled.
4. **Implements** one representative hook end-to-end as proof of concept.

The goal is a strategy that adds opencode support **without breaking**
Claude Code, and that the project owner can review in one sitting.

---

## 1. Catalog of existing `.claude/hooks/`

There are 18 hook scripts (17 shell + 1 Python) wired through
`.claude/settings.json`. Below: hook name, Claude Code event, the field(s)
of the event payload it reads, its effect (block / inject / record), and
notes on Claude-Code-specific assumptions.

### 1.1 SessionStart (1 hook)

| Hook | Reads from stdin JSON | Effect | CC-specific assumptions |
|---|---|---|---|
| `session-start-tools.sh` | n/a (no stdin) | injects detected binary versions into session context | n/a |

### 1.2 PreToolUse (12 hook instances, 9 distinct scripts)

Claude Code's matcher syntax routes the same `PreToolUse` event to multiple
hooks by `tool_name` (`Bash`, `Read`, `Edit`, `Write`, `MultiEdit`,
`NotebookEdit`).

| Hook | matcher | Reads | Effect | CC-specific assumptions |
|---|---|---|---|---|
| `block-git-commit.sh` | Bash | `tool_input.command` | block (exit 2) | none |
| `block-git-worktree.sh` | Bash | `tool_input.command` | block (exit 2) | none |
| `mvn-artifact-staleness.py` | Bash | `tool_input.command` | soft-inject (exit 0 + stdout JSON `hookSpecificOutput.additionalContext`) | reads `CLAUDE_PROJECT_DIR` env, scans `~/.m2` |
| `block-mvn-test-direct.sh` | Bash | `tool_input.command` | block (exit 2) **or** soft-inject (exit 0 + JSON `additionalContext`) | none |
| `block-memory-write.sh` | Write, Edit, MultiEdit, NotebookEdit, Bash | `tool_input.file_path` (Write/Edit/etc.) and `tool_input.command` (Bash) | block (exit 2) | **yes** — regex `\.claude/projects/[^/]+/memory` is Claude-Code-specific; the message steers the agent at the ar-consultant/manager APIs |
| `block-pom-write.sh` | Write, Edit | `tool_input.file_path` + `tool_input.new_string` / `tool_input.content` | block (exit 2) | reads file from disk for the Write path; diffs old vs new |
| `enforce-consultant-first.sh` | Write, Edit | `tool_input.file_path` | soft-warn (exit 0 + stderr banner) | reads `/tmp/.ar_consultant_last_${USER}.ts` written by `track-consultant-call.sh` |
| `guard-ci-file.sh` | Read, Edit, Write | `tool_input.file_path` | inject full `.github/CLAUDE.md` content to stdout (exit 0) | injects `analysis.yaml`-specific content; paths assume `common` layout |
| `guard-pom-read.sh` | Read | `tool_input.file_path` | inject reminder + auto-grep output to stdout (exit 0) | grep path patterns assume `common`'s `ar-*` artifactIds |
| `validate-ci-edit.sh` | Edit, Write | `tool_input.file_path` + `tool_input.new_string` / `tool_input.content` | block (exit 2) | parses analysis.yaml YAML by regex |
| `warn-defensive-guard.sh` | Edit, Write | `tool_input.new_string` / `tool_input.content` | soft-warn (exit 0 + stderr box) | references `.claude/hooks/rules/fail-loud.md` |
| `warn-git-log.sh` | Bash | `tool_input.command` | soft-warn (exit 0 + stderr box) | points at `mcp__ar-manager__memory_branch_context` |

### 1.3 PostToolUse (4 hook instances, 4 distinct scripts)

PostToolUse runs after the tool result is already on disk / in the message
stream. These hooks generally *inform* (they cannot unblock a blocked call;
the result is already in). They use the **soft-inject** pattern: exit 0,
write a `hookSpecificOutput.additionalContext` JSON to stdout, which Claude
Code appends to the model's next turn.

| Hook | matcher | Reads | Effect | CC-specific assumptions |
|---|---|---|---|---|
| `file-length-advisory.sh` | Read | `tool_response.filePath` (falls back to `tool_input.file_path`) | soft-inject (exit 0 + JSON `additionalContext`) | reads file from disk to count lines |
| `scan-producer-violations.sh` | Write, Edit | `tool_input.file_path` | soft-warn (exit 0 + stderr box) | reads file from disk, scans for `.evaluate(`, `.toDouble(`, etc. |
| `warn-assertion-free-test.sh` | Edit, Write | `tool_input.file_path` | soft-warn (exit 0 + stderr box) | reads file from disk, uses `awk` to walk @Test methods |
| `track-consultant-call.sh` | `mcp__ar-consultant__consult` | n/a (only writes a file) | side-effect: write timestamp to `/tmp` | read by `enforce-consultant-first.sh` (cross-hook state) |

### 1.4 Rule-doc and supplementary files (not hooks, but loaded by them)

- `.claude/hooks/rules/fail-loud.md` — the rationale doc injected by
  `warn-defensive-guard.sh` and `warn-assertion-free-test.sh`.

---

## 2. The Claude Code hook contract (the surface we have to translate)

After reading all 18 scripts end-to-end, the de-facto contract each script
relies on is:

| Aspect | Claude Code's behavior |
|---|---|
| Input delivery | one JSON object on **stdin**; scripts `read` it with `cat` or `$(cat)` |
| Field name (tool) | `tool_name` (string) |
| Field name (args) | `tool_input` (object; tool-specific shape) |
| Field name (response, PostToolUse only) | `tool_response` (object) |
| "Block" | `exit 2` with a multi-line reason on **stderr**; the model sees the stderr text as the reason |
| "Allow, but inject" | `exit 0` **and** print a JSON object to stdout with shape `{ "hookSpecificOutput": { "hookEventName": "PreToolUse" | "PostToolUse", "additionalContext": "..." } }`; the model sees `additionalContext` next turn |
| "Allow, no message" | `exit 0`, write nothing (or write only to stderr for human/agent visibility) |
| Project dir env | `$CLAUDE_PROJECT_DIR` set by the harness to the repo root |
| Matchers | declared in `settings.json` (the hook itself does no tool-name filtering — the harness routes by `matcher` field) |
| Multi-hook per event | harness runs all matched hooks; each gets its own stdin read |

Important consequences:

- **Stdout is for the model.** Stderr is for the user/agent. Exit code is
  the BLOCK signal.
- The "soft-inject" pattern depends on the harness parsing JSON from
  stdout. A script that prints a plain banner to stdout is invisible to
  the model and only visible to a human reading the transcript.
- The harness runs all hooks for a matcher in declaration order; a script
  that exits 2 stops the tool call AND prevents later hooks from running
  (this matters when ordering matters — e.g. `block-pom-write.sh` and
  `enforce-consultant-first.sh` both fire on Edit of a pom.xml; the
  blocker wins).

---

## 3. How opencode's plugin system works (and how it differs)

opencode has no "hook" terminology. It has **plugins** (JavaScript /
TypeScript modules that export a function returning event handlers). I
read the opencode docs at `opencode.ai/docs/plugins/`, the plugin SDK
type definitions in `packages/plugin/src/index.ts` of the `anomalyco/opencode`
repo, and the trigger code in `packages/opencode/src/plugin/index.ts`.

### 3.1 Plugin model

A plugin is a JS/TS file in `.opencode/plugins/` (project) or
`~/.config/opencode/plugins/` (global), or an npm package listed in
`opencode.json`'s `"plugin"` array. It looks like:

```ts
import type { Plugin } from "@opencode-ai/plugin"

export const MyPlugin: Plugin = async (input) => {
  return {
    "tool.execute.before": async (input, output) => {
      // input.tool, input.sessionID, input.callID
      // output.args is the tool's argument object — MUTABLE
    },
    "tool.execute.after": async (input, output) => {
      // input.args, input.tool, ...
      // output.title, output.output, output.metadata — MUTABLE
    },
  }
}
```

The function receives a `PluginInput` with `client` (SDK to call back
into the opencode server), `project`, `directory`, `worktree`, and `$`
(Bun's shell). Plugins can also register custom tools.

Plugins load **at startup** (when `opencode` launches). Errors in plugin
code are reported via the log and the user sees a `Failed to load plugin`
notification; they do not silently disable the rest.

### 3.2 Events relevant to us

From the docs page, here is the list of events. The ones that map onto
Claude Code's PreToolUse/PostToolUse/SessionStart are:

| opencode event | Claude Code analog | Args (input) | Output (mutable) |
|---|---|---|---|
| `tool.execute.before` | PreToolUse | `{ tool, sessionID, callID }` | `{ args: any }` |
| `tool.execute.after` | PostToolUse | `{ tool, sessionID, callID, args }` | `{ title, output, metadata }` |
| `command.execute.before` | (no direct analog — opencode's `/command` slash-commands) | `{ command, sessionID, arguments }` | `{ parts: Part[] }` |
| `session.created` | (SessionStart — closest) | session info | n/a (event is a notification) |
| `session.compacted` | (no analog) | n/a | n/a |
| `experimental.session.compacting` | (no analog) | `{ sessionID }` | `{ context: string[], prompt?: string }` |
| `experimental.chat.system.transform` | (no direct analog — but lets you append text to the system prompt on every turn) | `{ sessionID?, model }` | `{ system: string[] }` |
| `shell.env` | (no analog — opencode hooks shell startup env) | `{ cwd, sessionID?, callID? }` | `{ env }` |
| `event` | (catch-all; receives every opencode event) | `{ event }` | n/a |

### 3.3 The four things that are different — and how to reconcile them

1. **Tool-name field names in `args` are camelCase, not snake_case.**
   - Claude Code: `tool_input.file_path` (snake)
   - opencode: `args.filePath` (camel, for `read`/`edit`/`write`)
   - For `bash`: Claude Code's `tool_input.command` ↔ opencode's `args.command`. Same name. Lucky.
   - For `apply_patch`: opencode uses `args.patchText` (not `args.filePath`); the file path is inside the patch body. This matters for the `guard-pom-write` analog.
   - For `write`: opencode `args = { content, filePath }` (one struct). Claude Code `tool_input` for `Write` is `{ file_path, content }`.
   - For `edit`: opencode `args = { filePath, oldString, newString, replaceAll? }` (one struct). Claude Code `tool_input` for `Edit` is `{ file_path, old_string, new_string }`.
   - **Reconciliation:** a small adapter layer in each hook normalizes `(input) → harness-neutral record { tool, command?, file_path?, old_string?, new_string?, content? }` before running the shared check.

2. **There is no exit code and no stdin JSON.** The plugin function is called by opencode with typed arguments. Blocking is done by **throwing an `Error`** from the handler; opencode's `Plugin.trigger` wraps each handler in `Effect.promise`, so a thrown error propagates up and the tool fails with the error message as the model-visible reason (matches Claude Code's "exit 2 + stderr" semantics). Allowing + injecting text is done by **mutating `output.output`** in `tool.execute.after` (the model sees this on the next turn — same effect as Claude Code's `additionalContext`).
   - **Reconciliation:** the "shared core" returns a structured result `{ action: 'block' | 'allow', reason?: string, context?: string }` and each adapter translates that to its harness's primitive (exit code vs throw; stdout JSON vs mutate `output.output`).

3. **There is no `$CLAUDE_PROJECT_DIR` env var.** opencode passes `directory` and `worktree` on the plugin's `input` object. We use `input.directory` (or the resolved `worktree`).
   - **Reconciliation:** the shared core accepts a `project_dir` parameter; each adapter sources it from its harness's channel.

4. **The matcher model is inverted.** Claude Code declares matchers in `settings.json` (the hook script itself is tool-agnostic and inspects `tool_name` to skip non-matching events — but in this codebase matchers are already declared, so the script trusts the routing). opencode has **no matchers**: the plugin function gets every `tool.execute.before` event regardless of tool, and the plugin must inspect `input.tool` and return early for tools it doesn't care about. Multi-tool scripts (e.g. `block-memory-write.sh` for Write/Edit/MultiEdit/NotebookEdit/Bash) do this branching inside the script today; the same branching moves into the opencode adapter.

There is one more class of difference worth flagging now (we will not solve it in this job):

5. **No direct `SessionStart` event for injecting text into the session.** Claude Code's `SessionStart` runs the hook once and the stdout is concatenated into the agent's context. opencode's `session.created` is a notification event with no return channel. The closest opencode equivalent is `experimental.chat.system.transform`, which fires on every LLM call (and accepts a `system: string[]` to append). For "emit tool versions at session start" semantics, an opencode plugin must use `experimental.chat.system.transform` (fires often, repeated output — wasteful) or `session.created` (fires once, but can't inject text) — neither is a clean fit.
   - **Reconciliation (out of scope here, noted for the migration plan):** rewrite the hook's intent. `session-start-tools.sh`'s purpose is "save the agent 3–5 round-trips discovering tool versions"; for opencode, that information is more naturally a section in `AGENTS.md` (which opencode reads unconditionally) or in a one-shot injected via the `event` hook + a custom tool. Mark this hook as "needs design discussion before adapting."

---

## 4. Recommended adaptation strategy

After considering three options, I recommend **Option A** (shared core
+ thin per-harness adapters).

### 4.1 The three options

**Option A — Shared core + thin per-harness adapters (recommended).**
- Each policy has a single "core" function (Python is the natural choice —
  the project already requires Python everywhere; the MCP servers, the
  existing `mvn-artifact-staleness.py`, and all the inline-Python
  snippets inside `.sh` hooks are Python today).
- The core accepts a normalized record and returns a structured result
  (`{ action, reason, context }`).
- Two adapters: a `bash` adapter that reads Claude Code's stdin JSON and
  translates it to the normalized record (and exit code/stdout JSON back
  to Claude Code's contract); and a TypeScript adapter that implements
  the `Plugin` interface and translates `output.args` to the normalized
  record (and throws / mutates `output.output` to opencode's contract).
- **Pros:** one place to change the policy; both harnesses always agree;
  testable in isolation; works for every hook regardless of complexity.
- **Cons:** requires Python at runtime; introduces one process spawn per
  hook event for the opencode side (a few ms).

**Option B — Duplicate the logic per harness.**
- The Claude Code shell script stays as-is; a separate opencode plugin
  re-implements the same logic in TypeScript.
- **Pros:** no shared process; no Python dependency at hook time.
- **Cons:** every policy change must be made twice; the two copies WILL
  drift; the more complex hooks (shlex tokenization in
  `block-mvn-test-direct.sh`, diff computation in `block-pom-write.sh`,
  YAML parsing in `validate-ci-edit.sh`) would be painful to maintain in
  two languages. **Rejected.**

**Option C — Drop the `.claude/hooks/` catalog and use opencode's
permission system + a few plugins.**
- opencode already has a `permission` config that can deny `bash` patterns
  (e.g. `"git commit *": "deny"`). It also supports `experimental.policies`
  for provider-level allow/deny.
- **Pros:** zero new code; uses the host's native mechanism.
- **Cons:** opencode's pattern matcher is glob/wildcard-based; it cannot
  express "block `mvn test` but allow `mvn test -DskipTests`" without
  per-tool pattern work; it has no equivalent of "soft-inject a 4-paragraph
  architecture reminder into the model context" (the whole point of
  `guard-pom-read.sh`); and it has no equivalent of "run a 100-line check
  on the file being written." This option is a *supplement* to Option A,
  not a replacement — for the very simple "block by glob" cases, the
  `permission` config can replace a hook entirely. **Adopt as a
  secondary strategy where it fits.**

### 4.2 What "shared core" looks like

For a Bash-tool policy like `block-mvn-test-direct.sh`, the core is a
Python function:

```python
# mvn_test_check.py
def decide(command: str) -> dict:
    """Return {action, reason?, context?} for the given bash command."""
    ...
```

For an Edit-tool policy like `block-pom-write.sh`, the core is:

```python
# pom_dependency_check.py
def decide(tool: str, file_path: str, new_text: str, old_text: str = "") -> dict:
    """Return {action, reason?, context?}."""
    ...
```

For a stateful policy like `enforce-consultant-first.sh`, the core
takes the timestamp-file path as a parameter, so each adapter decides
where the marker file lives:

```python
# consultant_marker.py
def decide(file_path: str, marker_path: str, threshold_seconds: int) -> dict:
    ...
```

### 4.3 Adapter contract (both adapters speak to the same core)

```
input (per harness) → normalize → shared_core.decide() → result
                                                        ↓
                                     harness-native rendering
```

The shared core's return shape is fixed:

```python
{
    "action": "block" | "allow" | "warn",
    "reason": "string shown to the model on block",
    "context": "string shown to the model on warn (injected next turn)",
    "visible_to_human": "string printed to stderr / TUI for the human"
}
```

| action | Claude Code adapter | opencode adapter |
|---|---|---|
| `block` | `exit 2`, write `reason` to stderr | throw `Error(reason)` from the handler |
| `warn` | `exit 0`, write JSON `additionalContext: context` to stdout; write `visible_to_human` to stderr | append `context` to `output.output` in `tool.execute.after`; or mutate args to inject a leading comment where applicable |
| `allow` | `exit 0`, optionally `visible_to_human` to stderr | no-op |

### 4.4 Where the new files live

```
.claude/hooks/
├── block-git-commit.sh                # Claude Code adapter (existing — unchanged behavior)
├── block-mvn-test-direct.sh           # Claude Code adapter — refactored to thin shell
├── ...                                # other Claude Code adapters (unchanged)
└── lib/
    ├── mvn_test_check.py              # shared core (extracted)
    ├── pom_dependency_check.py        # shared core
    ├── consultant_marker.py           # shared core
    └── ...                            # more cores as we migrate

.opencode/
├── opencode.json                      # already exists; add "plugin" entries
└── plugins/
    ├── block-mvn-test-direct.ts       # opencode adapter (proof of concept)
    ├── block-git-commit.ts            # future migration
    └── ...                            # more adapters as we migrate
```

The split — `.claude/hooks/lib/` for cores, `.opencode/plugins/` for
opencode adapters — keeps the project's existing layout (`.claude/hooks/`
for Claude Code, `.opencode/` for opencode) and the two harness trees
remain greppable independently.

### 4.5 Cross-hook state (the hard case)

`enforce-consultant-first.sh` and `track-consultant-call.sh` share state
via `/tmp/.ar_consultant_last_${USER}.ts`. The shared core
`consultant_marker.py.decide()` will accept a `marker_path` parameter
so each adapter picks a path:

- Claude Code adapter: `/tmp/.ar_consultant_last_${USER}.ts` (unchanged,
  keeps backward compat with the existing file).
- opencode adapter: same path, so if both harnesses are run in the same
  shell session, they share state. (This is correct: a single human
  developer is using one or the other at a time per session.)

This pattern generalizes to any "cross-hook" coordination.

### 4.6 Which hooks are easy vs. hard to adapt

**Easy (Class A — bash command grep / pattern match):**
- `block-git-commit.sh`
- `block-git-worktree.sh`
- `warn-git-log.sh`

The check is one regex; the opencode adapter is ~30 lines of TypeScript.

**Moderate (Class B — non-trivial parsing, multiple tool_names, or
shlex/diff logic):**
- `block-mvn-test-direct.sh` — shlex + multi-command. *PoC.*
- `block-pom-write.sh` — diff + structural-element detection.
- `block-memory-write.sh` — 5 tool names, but the logic is the same in
  each. Has Claude-Code-specific path regex (`\.claude/projects/.../memory`)
  that is meaningless on opencode; needs a rethink.
- `scan-producer-violations.sh` — file-content scan, easy in TS.
- `warn-assertion-free-test.sh` — awk-based, easy to port.
- `warn-defensive-guard.sh` — multiple pattern greps, easy in TS.
- `file-length-advisory.sh` — single threshold, very easy.
- `guard-ci-file.sh` — file injection, but the injected content is
  specific to the CI pipeline that BOTH harnesses touch; trivial port.
- `guard-pom-read.sh` — auto-grep + reminder. The reminder text is
  project-specific (not Claude-Code-specific); port is straightforward.

**Hard (Class C — has Claude-Code-specific baked-in assumptions, or
fundamentally different event model):**
- `mvn-artifact-staleness.py` — easy core, but uses `CLAUDE_PROJECT_DIR`
  env. Replace with `input.directory`/`worktree` on opencode side.
- `validate-ci-edit.sh` — has its own YAML parser; logic is portable, just
  needs to load from `input.args.filePath` / `input.args.content` /
  `input.args.newString` / `input.args.oldString`.
- `enforce-consultant-first.sh` + `track-consultant-call.sh` — cross-hook
  state. The shared-core pattern (§4.5) handles it.
- `session-start-tools.sh` — fundamental event-model mismatch. **Hold
  for design discussion** before adapting; see §3.3.5.

### 4.7 Migration path (the future work)

In order, easy → hard, and only after the PoC is in:

1. **PoC (this job):** `block-mvn-test-direct.sh` refactored to a shared
   core + opencode plugin. See §5.
2. **Class A migration (one commit, three hooks):**
   `block-git-commit.ts`, `block-git-worktree.ts`, `warn-git-log.ts`. All
   three can share a common `command-pattern-block.ts` helper that takes
   a regex and a message — basically a 1-page templating exercise.
3. **File-inspection hooks:** `scan-producer-violations.ts`,
   `warn-assertion-free-test.ts`, `warn-defensive-guard.ts`,
   `file-length-advisory.ts`. All read a just-written/read file. The
   opencode plugin reads `input.args.filePath` (and on `.after` events,
   the existing `output.output` is the file content for `read`).
4. **`block-pom-write.ts`** — diff logic. Port the core verbatim.
5. **`guard-pom-read.ts`** + **`guard-ci-file.ts`** — inject-only, no
   blocking. These map cleanly to a `tool.execute.before` handler that
   returns without throwing.
6. **`validate-ci-edit.ts`** — YAML parsing, port the core.
7. **`mvn-artifact-staleness.ts`** — env var differences; small adapter.
8. **`enforce-consultant-first.ts` + `track-consultant-call.ts`** — pair
   port; the marker file path is shared.
9. **`block-memory-write.ts`** — needs design discussion (see §4.6).
10. **`session-start-tools.sh`** — needs design discussion (see §3.3.5).

Each step is one commit, one PR, one run of the relevant tests in the
existing Claude Code setting (regression check) and one manual
opencode-session smoke test.

### 4.8 What we deliberately do NOT do

- **Do not delete or replace the existing `.claude/hooks/` scripts.** They
  are the production code path for Claude Code today and must keep
  working byte-for-byte (modulo the PoC refactor in §5 which is a
  behavior-preserving extraction of the Python logic into a shared core
  that the shell script then calls).
- **Do not couple opencode plugin behavior to Claude Code's
  `settings.json`.** Each harness has its own config; the shared
  semantics live in the cores.
- **Do not introduce a new Maven module** for the shared cores. The
  cores are plain Python files under `.claude/hooks/lib/`; the opencode
  plugin shells out to them. No Java, no `pom.xml`, no module.
- **Do not bring in a build step for the opencode plugins.** opencode
  loads `.ts` plugins via `bun` at startup. No `tsc` step.

---

## 5. Proof of concept: `block-mvn-test-direct` for both harnesses

**Why this hook:** the spec asked for a "simple-to-moderate" representative.
`block-mvn-test-direct.sh` is in the middle of the complexity range —
real shlex tokenization, multi-segment command detection (`bash -c "mvn
test"` recursion), environment-variable handling, and it has BOTH the
hard-block and soft-warn paths (which is exactly the two-rendering-mode
question the strategy has to answer). It is a critical agent guard (the
team does not want agents running `mvn test` directly) so the PoC has
real value beyond demonstration. And the logic has zero Claude-Code-specific
assumptions — the check is "is this an mvn test command?" which is universal.

**What changes on disk for this PoC:**

| File | Change |
|---|---|
| `.claude/hooks/block-mvn-test-direct.sh` | Behavior-preserving refactor: extract the inline Python to a call to `.claude/hooks/lib/mvn_test_check.py` |
| `.claude/hooks/lib/mvn_test_check.py` (new) | The shared core — the Python logic moved out of the shell script verbatim |
| `.claude/hooks/lib/test_mvn_test_check.py` (new) | Unit tests for the core, runnable without either harness |
| `.opencode/plugins/block-mvn-test-direct.ts` (new) | The opencode adapter: implements `Plugin`, normalizes `input.args` to the core's shape, calls the core via subprocess, throws on block or appends `context` to `output.output` on warn |
| `.opencode/opencode.json` | Add a `"plugin"` entry pointing to the new `.ts` file |
| `.opencode/plugins/__tests__/block-mvn-test-direct.test.ts` (new) | Smoke test for the adapter's normalization + subprocess plumbing |

**What does NOT change:**

- The user-facing behavior of `block-mvn-test-direct.sh` when running
  under Claude Code. The script still reads stdin JSON, still exits 2
  on a real `mvn test`, still emits the same JSON `additionalContext`
  for the "uncertain" case. The only difference is that the Python
  body now lives in a separate file the shell sources.
- Any other `.claude/hooks/` script.
- Any existing `opencode.json` MCP server config.

**Verification plan for the PoC (before declaring done):**

1. The extracted core has unit tests covering: `mvn test` (block), `mvn
   install -DskipTests` (allow), `mvn -Dtest=Foo test` (block), `bash
   -c "mvn test"` (block), `echo "mvn test"` (uncertain → warn),
   `mvn test-compile` (allow). Run with `python3 -m unittest` — no
   harness required.
2. The shell wrapper smoke-test: feed it a synthetic stdin JSON for a
   `Bash` `mvn test` invocation and confirm it exits 2 with the same
   stderr message the old version produced.
3. The opencode adapter unit test: invoke its `decide`-equivalent with
   mock `input.args.command` strings and confirm it would `throw` on
   block / mutate `output.output` on warn. (We don't need to launch
   opencode itself to verify the plugin's logic.)
4. The full `mvn install -DskipTests` for the modules the changed
   files live in (just `.claude/hooks/` — no Java was touched, but
   `mvn install -DskipTests` from the repo root is the project's
   standard compile-check pass and confirms nothing else broke).
5. Run the build validator (`mcp__ar-build-validator__start_validation`)
   for `checkstyle` and `code_policy` on the changed files. The
   `.sh`/`.py`/`.ts` files are not Java, so checkstyle will not flag
   them; code-policy is the more important check because it enforces
   the "do not do math in Java, do not write Java that calls
   `.evaluate()`" rules. None of those rules apply to a Python or
   TypeScript hook script, but we run it to be sure no Java was
   accidentally touched.

### 5.1 Detailed shape of the shared core

```python
# .claude/hooks/lib/mvn_test_check.py
"""Decide whether a `bash` tool call is a direct `mvn test` invocation.

This module is the single source of truth for that decision. It is
invoked by:
  - .claude/hooks/block-mvn-test-direct.sh     (Claude Code)
  - .opencode/plugins/block-mvn-test-direct.ts (opencode)

Each adapter normalizes its harness's tool input into a `command` string
and calls `decide(command)`. The returned dict has the same shape for
both adapters:

    {
      "action":   "block" | "allow" | "warn",
      "reason":   str,    # shown to the model on block; printed to stderr
      "context":  str,    # injected into the model's next turn on warn
      "stderr":   str,    # always printed to stderr for the human
    }
"""
```

The Python body is the existing `analyze(...)` function from
`block-mvn-test-direct.sh`, moved out unchanged plus a thin
`decide(command)` wrapper that classifies the result.

### 5.2 Detailed shape of the opencode adapter

```ts
// .opencode/plugins/block-mvn-test-direct.ts
import type { Plugin } from "@opencode-ai/plugin"
import { spawnSync } from "node:child_process"
import * as path from "node:path"
import { fileURLToPath } from "node:url"

const HERE = path.dirname(fileURLToPath(import.meta.url))
const CORE = path.resolve(HERE, "../../.claude/hooks/lib/mvn_test_check.py")

export const BlockMvnTestDirectPlugin: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      if (input.tool !== "bash") return
      const args = output.args as { command?: string } | undefined
      const command = args?.command
      if (!command) return

      const result = callCore(command)
      if (result.action === "block") {
        throw new Error(result.reason)
      }
      // "warn" is handled in `.after` because we want the model to see
      // the warning *with* the (presumably empty) tool output, not
      // preemptively.
    },
    "tool.execute.after": async (input, output) => {
      if (input.tool !== "bash") return
      const args = input.args as { command?: string } | undefined
      const command = args?.command
      if (!command) return

      const result = callCore(command)
      if (result.action === "warn") {
        output.output = `${output.output}\n\n${result.context}`
      }
    },
  }
}

function callCore(command: string) {
  const r = spawnSync("python3", [CORE, command], { encoding: "utf-8" })
  if (r.status !== 0) {
    // The core should only exit nonzero on internal error. Fall through
    // to "allow" — never block on a hook malfunction.
    return { action: "allow", reason: "", context: "", stderr: r.stderr }
  }
  return JSON.parse(r.stdout)
}
```

(Exact code will be written to disk as part of the PoC. The above is the
shape.)

### 5.3 What the `.opencode/opencode.json` change looks like

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": { ... },
  "plugin": ["./plugins/block-mvn-test-direct.ts"]
}
```

(The existing `"mcp"` block is left untouched.)

### 5.4 Risk and rollback

The `.claude/hooks/block-mvn-test-direct.sh` refactor is the only change
that touches production code paths. Its behavior is preserved bit-for-bit
(verified by the shell smoke test in §5 verification step 2). If the
shared-core extraction goes wrong, reverting that one file restores the
old behavior. The opencode adapter is pure addition; removing it requires
removing the new files and the `"plugin"` entry in `opencode.json`.

---

## 6. Open follow-ups (for the project owner to decide)

These are decisions that came up during the study but are out of scope
for the PoC. Captured here so they don't get lost.

- **§3.3.5 / `session-start-tools.sh`:** opencode has no `SessionStart`
  equivalent that injects text into the model's context. Options:
  - Write the tool-version block to a project-local `AGENTS.md` and let
    opencode pick it up natively.
  - Add a custom tool (via the plugin's `tool` registration) that the
    model is told to call once at session start.
  - Drop this hook on the opencode side and accept the round-trip cost.

- **§4.6 / `block-memory-write.sh`:** the hook exists to block writes to
  `~/.claude/projects/<project>/memory/`, which is Claude Code's
  built-in local memory system. opencode has no equivalent directory, so
  on the opencode side the hook has nothing to block. The right answer
  is probably to delete the opencode adapter for this hook entirely and
  rely on ar-consultant/ar-manager being the only memory channel (which
  is already enforced by the opencode `permission` config — neither
  service writes to `~/.claude/projects/...`).

- **Option C (opencode's built-in `permission` config) for the very
  simple Class A hooks:** for `block-git-commit` the equivalent opencode
  config is `"permission": { "bash": { "*": "ask", "git commit *": "deny" } }`.
  The pattern is "the agent is denied permission to run `git commit
  <anything>`." This is a one-line change in `opencode.json` and could
  replace the opencode TypeScript plugin entirely. The team should
  decide whether the deny-by-permission route is preferred (simpler, no
  custom error message) or whether the hook-style "block with a custom
  multi-line explanation" route is preferred (consistent UX with Claude
  Code).

- **What to do when both harnesses are used in the same session:** the
  current PoC and migration plan assume one harness per session, but a
  developer could in principle have both running. The shared `/tmp`
  marker file pattern is the one place this could matter
  (`enforce-consultant-first`); everywhere else, the cores are pure
  functions of their inputs. The `/tmp` shared file is the right
  answer — two harnesses in the same shell user, same workspace, should
  share the "consultant was called recently" timestamp.

---

## 7. Changelog

- 2026-06-03: Initial version (this document). Created alongside the
  PoC commit on `feature/opencode-hooks`.
