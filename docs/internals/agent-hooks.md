# Agent Hooks

Coding-agent sessions in this repository run under a set of hooks: small checks that
see each tool call before or after it runs, and either block it, let it through, or
add a note the model reads on its next turn. They exist because written guidance alone
has repeatedly failed to change agent behaviour, while a check that fires at the moment
of the action does.

Two agent harnesses are supported, and **parity between them is a standing goal**:
every policy should behave the same whether the session runs under Claude Code or
opencode. Each policy is implemented once, as a shared core, with a thin adapter per
harness.

## Layout

| Location | Contents |
|---|---|
| `.claude/hooks/lib/*.py` | Shared cores: one pure decision function per policy, plus its `test_*.py`. |
| `.claude/hooks/*.sh`, `*.py` | Claude Code adapters, registered in `.claude/settings.json`. |
| `.opencode/plugins/*.ts` | opencode adapters, registered in `.opencode/opencode.json` (and the `tsconfig.json` `include` list). |
| `.opencode/plugins/_lib/command_pattern.ts` | Shared adapter for the git-command policies (`block-git-commit`, `block-git-worktree`, `warn-git-log`, `block-branch-track-master`). |
| `.opencode/plugins/__tests__/` | Per-plugin `*.test.ts` and plain-Node `*.smoke.cjs` wiring checks. |

`.claude/hooks/` is under the CI-file lock: it changes only on a `ci/...` branch.

## The decision contract

A core exposes `decide(...)` returning a harness-neutral decision:

```json
{ "action": "block" | "allow" | "warn", "reason": "...", "context": "...", "stderr": "..." }
```

`reason` is shown to the model when a call is blocked, `context` is injected on its next
turn when a call is allowed with a warning, and `stderr` is for the human. On any internal
error a core returns `allow`, so a hook malfunction never blocks legitimate work.

Each core has two command-line entry points. `--stdin` reads a Claude Code hook payload
(`{"tool_input": {...}}`) and renders the decision natively. With a positional argument,
it prints the decision as JSON for the opencode adapter.

| Action | Claude Code adapter | opencode adapter |
|---|---|---|
| `block` | exit 2, `reason` on stderr | throw `Error(reason)` from `tool.execute.before` |
| `warn` | exit 0, `{"hookSpecificOutput": {"additionalContext": context}}` on stdout | append `context` to `output.output` in `tool.execute.after` |
| `allow` | exit 0, no output | no-op |

`mvn_test_check.py` and its adapters (`block-mvn-test-direct.sh`, `.ts`) are the reference
implementation of the pattern.

## Differences between the harnesses

- **Argument names.** Claude Code uses snake case (`tool_input.file_path`); opencode uses
  camel case (`args.filePath`). `bash`'s `command` is the same in both. opencode's
  `apply_patch` carries its path inside `args.patchText`.
- **No matchers in opencode.** Claude Code routes by the `matcher` in `settings.json`; an
  opencode plugin receives every tool event and must return early on tools it does not
  check.
- **Project directory.** Claude Code sets `$CLAUDE_PROJECT_DIR`; opencode passes
  `directory`/`worktree` on the plugin input. A core that needs the root takes it as a
  parameter.
- **State.** A Claude Code hook is a fresh process per call, so state that spans calls
  lives on disk, keyed by `session_id`. An opencode plugin is loaded once, so it keeps
  state in a module-level `Map` keyed by `sessionID`. A stateful core takes the current
  state and returns the next one (`new_state`) rather than storing it itself.
- **Session start.** Claude Code's `SessionStart` output is added to the context;
  opencode's `session.created` has no return channel. `session-start-tools.sh` has no
  opencode equivalent yet, and needs a different mechanism rather than a port.
- **Performance.** A cold Python start costs tens of milliseconds. Adapters that need a
  decision in both `before` and `after` cache it per `callID` so the core runs once.

Hooks that do not yet have an opencode adapter are listed in the opencode parity task on
the tracker. When adding a hook, write the core first, then both adapters, and test the
core directly.

## Choosing a force level

A hook that blocks changes behaviour immediately. One that only advises is weighed
against whatever the agent was already doing, and loses whenever the agent is confident.
Experience with the method-placement reminder (`warn-method-placement.py`) gave the
working rules:

- **Fire on evidence, not on every edit.** A reminder that fires on getters, overrides,
  test helpers and real problems alike stops being read. Skip the cases it cannot judge.
- **Name the fix.** "This method references only types from `compute/algebra`;
  `LayerFeatures` extends `CollectionFeatures`, so it belongs there" is an instruction. A
  generic checklist is a reading assignment, and the agent answers "I did that".
- **Fire once per subject.** Repeating the same reminder on each edit to the same method
  is what turns it into noise.
- **Block the clear cases.** Where a check can be confident, refuse the call and say what
  would be accepted.
- **Check again at validation time.** A mid-edit `PreToolUse` reminder arrives at the
  least convenient moment to act. A pass in the build validator, on the final code,
  catches what the reminder could not.

Soft reminders still have a place where blocking would be the wrong force. The memory
reminder below is the example.

## The memory reminder

`memory-reminder.sh` / `memory-reminder.ts` (core `memory_reminder_check.py`) nudge an
agent to call `mcp__ar-manager__memory_store` after a long stretch of work without one. It
only ever warns. Blocking a quality nudge would interrupt real work, while a missed
reminder costs little.

- **Trigger.** Whichever comes first: a count of side-effect calls (`Bash`, `Edit`,
  `Write`, `MultiEdit`, `NotebookEdit`; opencode's `bash`, `write`, `edit`,
  `apply_patch`) since the last store, or wall-clock time since it. Reads do not count.
- **Reset.** Only an exact `mcp__ar-manager__memory_store` call resets the counters.
  `memory_recall`, `memory_namespaces` and `consult` read and do not.
- **Anti-nag.** A warmup at session start, a cooldown after a store, and a backoff after
  each reminder before it can fire again.
- **State.** On disk at `/tmp/.ar_memory_state_${USER}.json` (Claude Code), or in the
  plugin's module `Map` (opencode).

Tuning is by environment variable; defaults favour reminding later rather than sooner:

| Variable | Default | Meaning |
|---|---|---|
| `AR_MEMORY_REMIND_DISABLED` | unset | `1` disables the hook. |
| `AR_MEMORY_REMIND_CALLS_THRESHOLD` | 15 | Side-effect calls since the last store before firing. |
| `AR_MEMORY_REMIND_SECONDS_THRESHOLD` | 1200 | Seconds since the last store before firing. |
| `AR_MEMORY_REMIND_BACKOFF_CALLS` | 8 | Further calls after a reminder before it can fire again. |
| `AR_MEMORY_REMIND_BACKOFF_SECONDS` | 600 | Further seconds after a reminder before it can fire again. |
| `AR_MEMORY_REMIND_WARMUP_CALLS` | 5 | Calls at session start during which it is silent. |
| `AR_MEMORY_REMIND_COOLDOWN_CALLS` | 3 | Calls after a store during which it is silent. |

## Test-execution hooks

`block-mvn-test-direct` and `block-pytest-broad-run` apply the root `CLAUDE.md` "Test
Execution Limits" to commands an agent runs directly. The Maven hook blocks a
test-running phase without `-DskipTests` and any `AR_TEST_GROUP` assignment. The pytest
hook blocks a run with no path, with a directory, or above 5 test files or 40 node ids.
They parse command text and are backstops; the enforced surfaces are job submission, the
controller's submit endpoint and the MCP test runner.

## Testing a hook

- Core: `python3 -m pytest .claude/hooks/lib/test_<core>.py`.
- opencode wiring: `node .opencode/plugins/__tests__/<plugin>.smoke.cjs`, and
  `bun test .opencode/plugins/__tests__/<plugin>.test.ts` where present. The exfiltration
  guard blocks agent sessions from running scripts that spawn processes, so these smoke
  tests are run by hand.
