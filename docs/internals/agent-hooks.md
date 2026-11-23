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

## A branch that tracks master

`block-branch-track-master` (core `git_command_check.py`) refuses the commands that would
make a feature branch track a remote master/main: `git checkout -b <name> origin/master`
without `--no-track`, `git branch -u origin/master`, and pushes onto master from another
ref. It reads only command text, so it cannot see the state when it arises some other way,
such as a branch created in a person's own terminal, by a script, or before the guard
existed. On such a branch a bare `git push` updates master.

`require-own-upstream.sh` / `require-own-upstream.ts` (core `branch_upstream_check.py`)
check the state itself, before **every** tool call. While the current branch is not
master/main and its configured upstream (`branch.<name>.merge` on a real remote) is
master/main, every call is blocked except a Bash command made only of the fixes
(`git branch --unset-upstream`, `git branch -u <remote>/<not master>`,
`git push -u <remote> <branch>`), read-only git inspection (`git status`, the `git branch`
listing forms, `git rev-parse`, `git config --get`, `git remote -v`) and `cd`. The session
has to fix the branch before it can do anything else. A detached HEAD, a branch with no
upstream or a local one, and a directory that is not a repository are allowed; the check
fails open on anything it cannot read.

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

## Installed-artifact staleness hook

`mvn-artifact-staleness.py` runs before a Bash command with an artifact-producing Maven
goal and before the ar-test-runner and ar-build-validator tools. It compares the install
time of every module artifact against the last branch switch in the reflog, and warns when
an installed artifact was built before it: a module the run does not rebuild would resolve
the previous branch's code.

It judges the directory that run will install into and resolve from, read through
`tools/mcp/common/maven_repository.py` (`install_location`), the same reading the
ar-test-runner preflight uses:

| Setting | Effect |
|---|---|
| `-Dmaven.repo.local=<dir>` on the command line, then in `MAVEN_ARGS`, then in `MAVEN_OPTS` | repository base (CI's `select-maven-repo` action sets it in `MAVEN_OPTS`) |
| `FLOWTREE_MAVEN_LOCAL_REPOSITORY` | repository base a FlowTree job exports when none of the above is set |
| none of them | `~/.m2/repository` |
| `aether.enhancedLocalRepository.split` and `localPrefix` (a FlowTree job's `MAVEN_ARGS`) | installs go to `<base>/<localPrefix>`, the job's own space |

A leading `MAVEN_OPTS=... mvn ...` assignment counts for that command. The MCP tools'
Maven processes inherit the session environment and add no repository flag, so for them
the environment alone decides. A repository relocated only by a `settings.xml` other than
the one the job exports is not followed.

Every message names the directory and the setting that chose it. A directory with no
project artifacts in it yet is reported in one line as a fresh install space, not as
staleness; a module the run does not build then fails to resolve instead of resolving old
code. The scope sentence follows the command: only a full-reactor `install` refreshes
everything; `-pl <module> -am` rebuilds that module and its dependencies, and every other
module still resolves from the install directory. The ar-build-validator is modelled by
what its arguments make it do: its default checks run a full `mvn install -DskipTests`
first, so it refreshes the whole reactor, while `skip_build` or a scan-only check set
(e.g. `checkstyle`) only resolves what is installed — unlike the ar-test-runner, which
builds just its selection. Tests: `tools/tests/test_mvn_artifact_staleness.py`.

## New-type equivalence gate

An agent must not add a type that re-implements a concept the codebase already has; the
problem is solved with the existing abstraction, improved if necessary. Three pieces
enforce this, sharing one implementation of the candidate search,
`.claude/hooks/lib/type_equivalence.py`:

- **The hook** (`warn-new-type-placement.py`) runs on every Write/Edit/MultiEdit that adds
  a top-level main-source Java type, and reads every declaration through the library's
  parser. It compares the new type with every main-source type of the repository on three
  signals of the same concept: shared public method signatures (name and arity, beyond the
  contract a supertype already dictates), javadoc first sentences that describe the same
  thing (in both directions, words weighted by rarity), and names that say the same thing.
  It blocks (exit 2) only on that evidence: shared signatures together with a matching name
  or javadoc; a name with the same qualifiers on an equivalent head (`ArithmeticIndexSet`
  beside `ArithmeticIndexSequence`) whose javadoc agrees; or a near-copy. Shared vocabulary
  alone, or a shared package, never blocks. Siblings of one supertype, collaborators (the
  new type's public methods take or return the candidate, or the candidate refers to it),
  role variants (`Grain` and `GrainParameters`) and subtypes are reported as warnings unless
  the new type is a near-copy. The block lists the duplicates with paths and the remedies in
  order: use the candidate, change the signature or caller, extend or improve the candidate
  or introduce an interface it implements, and only then a new type. A type that only
  resembles others gets the placement warning with those types listed. Test sources are not
  checked. A failure anywhere in the assessment fails open, with a warning on stderr and in
  the session's system message. The index is cached in the system temporary directory per
  repository root and refreshed incrementally by file modification time.
- **Lifting the block.** It lifts when the new type genuinely builds on a candidate: it
  extends or implements it and overrides or calls its members, or calls its methods through
  a field, parameter or local of its type, or constructs it. A marker supertype or an unused
  field does not count. It also lifts when `.claude/new-type-ledger.json` (tracked)
  justifies it: the entry covers every blocking candidate with `cannot_extend`,
  `cannot_change_signature` and `interface_would_not_work`, and names a `test`
  (`Class#method`) that exists in the repository's test sources, declares that method and
  refers to the new type. The schema is in the module docstring of `type_equivalence.py`; an
  entry matches its type by fully-qualified name only. Renaming the type is not a remedy.
- **The independent audit.** The FlowTree harness runs the base-branch copy of the same
  search (`NewTypeAudit`), so the audited session cannot weaken it. `DeduplicationRule`
  runs for every job and gives each duplicate to a separate session that searches
  documentation and memory as well, treats the ledger as a claim to verify, and folds the
  type into the existing abstraction unless the justification holds; a kept type gets
  `"audit": {"verdict": "kept"}` in the ledger. The outcome of each audit is recorded in the
  branch's `ObjectOrientedReviewLedger` (the same content-keyed mechanism the design review
  uses), keyed by a digest of the type's source and its ledger entry as the audit left them,
  so a verdict written by any other session does not count and a later edit is audited
  again. `FileStager` refuses to stage a duplicate until an audit has kept it — together with
  every changed file that refers to any type the refused file declares, transitively — and
  refuses every changed Java file when the search could not run, which also makes the job
  `DEGRADED` with the reason. The review prompt asks of every new type whether it should
  exist before asking whether it is correct.

`python3 .claude/hooks/lib/type_equivalence.py calibrate --root . [PREFIX...]` treats every
existing type under the prefixes as if it were new and reports how many would block. The
`CalibrationTest` in `.claude/hooks/lib/test_new_type_placement.py` holds that rate at or
below 3% over `engine/audio`, `studio/music` and `domain/graph`, and checks that real
duplicates (an arithmetic-progression set and progression beside `ArithmeticIndexSequence`,
and existing classes copied under new names) still block.

`tools/mcp/common/snapshot_index.py` keeps its own Java type pattern: it indexes nested
types and annotation types for the documentation corpus, and the harness runs
`type_equivalence.py` as a single self-contained file from the base branch, so the scanner
cannot import a parser from the working tree without letting the audited session change it.

## Private-link hook

`block-private-url` applies the root `CLAUDE.md` rule "Never Publish a Private Link". It
blocks a claude.ai page (other than the public `claude.ai/api/` endpoints), a bare
`session_...` id, or a URL whose host is a project machine (a `[lab-hosts]` entry of
`exfil-allowlist.txt`, or a private, link-local or shared-address IP; loopback is
allowed) in text about to be published:

- a `gh pr`/`gh issue`/`gh release`/`gh api`/`gh gist` or `git commit`/`git tag`/
  `git notes`/`git merge` command, including the file a `--body-file`, `--file`, `-F` or
  `--message-file` names;
- an ar-manager `github_*` tool that writes (creates a pull request, replies, comments,
  edits);
- a write to a file named `commit.txt`.

Claude Code harnesses can be configured to remind the model to end commit messages and
pull request descriptions with a link to its session. Such a link opens only for its
owner, and `attribution` in `.claude/settings.json` does not suppress the reminder, so
this hook is what keeps the link out.

## Pull request gate

Every pull request runs the whole CI pipeline on the shared runner fleet, so one round of
work split into several small pull requests multiplies its runner cost. `pr-gate.sh` (core
`pr_gate_check.py`) puts that in front of the agent when it reaches for a new pull request:

- `record` (PostToolUse on ar-manager `github_list_open_prs`) stores the repository's open
  pull requests as ar-manager returned them.
- `gate` (PreToolUse on `github_create_pr`) denies the call unless that repository's open
  pull requests were listed in the last ten minutes. It keys that freshness by the same
  context the call resolves the repository against — an explicit `org`/`repo` pair, else a
  `workstream_id`, else the token context — so a listing and a create made the same way
  share a key, and the normal workstream and token-context forms are supported rather than
  rejected. When another pull request is open on a different branch, the first attempt is
  denied with a reminder naming it: commit to the open branch unless the change truly must
  be reviewed separately. The identical call repeated within ten minutes goes through and
  is appended to `.claude/pr-gate-overrides.local.json` for the developer.
- `bash` (PreToolUse on Bash) denies `gh pr create`, which would bypass the gate — including
  quoted, escaped, or dynamically-expanded spellings the shell still runs as `gh pr create`
  but the gate cannot prove are something else.

State is kept in `.claude/pr-gate.local.json`; both files are git-ignored.

## Testing a hook

- Core: `python3 -m pytest .claude/hooks/lib/test_<core>.py`.
- opencode wiring: `node .opencode/plugins/__tests__/<plugin>.smoke.cjs`, and
  `bun test .opencode/plugins/__tests__/<plugin>.test.ts` where present. The exfiltration
  guard blocks agent sessions from running scripts that spawn processes, so these smoke
  tests are run by hand.
