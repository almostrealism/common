# Git branch guard + a controlled git replacement

Status: guard implemented (`block-branch-track-master`); replacement is a design brainstorm.

## 1. The failure mode this exists to stop

The recurring mistake, stated mechanically so it can be reproduced and guarded:

> "I need to work against current master, so I'll branch off the remote ref:
> `git checkout -b <name> origin/master`."

The start point `origin/master` is a **remote-tracking ref**, so git's
`branch.autoSetupMerge` (default `true`) sets the new branch's **upstream to
`origin/master`**. The branch is named `<name>` but tracks `master`. The
consequences:

- `git push` under `push.default=simple` (git's default) *refuses* with an
  upstream/name mismatch — the branch "cannot be pushed" as itself.
- Under `push.default=upstream`, or via `git push origin HEAD`, the push would
  land the branch's commits **on master**.

This is produced by a fixed chain of reasoning ("current code → branch off
`origin/master`"), which is why it recurs regardless of session memory. The
correct forms never set the dangerous upstream:

```
git fetch origin && git checkout -b <name> --no-track origin/master
git checkout master && git pull --ff-only && git checkout -b <name>
# then:
git push -u origin <name>
```

## 2. The guard (implemented)

A new Class A git policy, `block-branch-track-master`, added to the existing
hook system. It is a **deny** hook: the offending command never runs.

### What it blocks (exit 2, with an instructive message)

- Branch creation from a remote master/main start point without `--no-track`:
  `git checkout -b NAME origin/master`, `git switch -c NAME origin/main`,
  `git branch NAME origin/master`, and the `-B`/`-C`/`--create` variants.
- Setting a non-master branch's upstream to a remote master/main:
  `git branch -u origin/master [NAME]`, `git branch --set-upstream-to=origin/master`.
- Pushes that would overwrite a protected branch from another ref:
  `git push origin HEAD:master`, `NAME:master`, `:master` (delete), pushes to
  `main`, and force-pushes (`--force`/`+`) to master/main.

### What it allows

- The corrected `--no-track` form, branching off **local** master, recreating
  master itself, branch names that merely end in `/master`, `git push origin master`
  (master→master), and `git push -u origin <feature>`.

### Architecture (mirrors the existing Class A hooks)

- Shared core: `.claude/hooks/lib/git_command_check.py` — new policy
  `block-branch-track-master`. Unlike the three grep policies, it **tokenizes**
  the command (split on shell separators, then `shlex` per segment, skipping
  `git -C <path>` style global options) so flag order and compound
  `cd … && git …` lines can't evade it. It remains a pure function of the
  command string.
- Claude Code adapter: `.claude/hooks/block-branch-track-master.sh` (thin
  `exec python3 … --stdin block-branch-track-master`).
- opencode adapter: `.opencode/plugins/block-branch-track-master.ts` +
  `PolicyId` extended in `_lib/command_pattern.ts`.
- Wiring: added to the `Bash` `PreToolUse` matcher in `.claude/settings.json`.
- Tests: `BlockBranchTrackMasterTests` (+ stdin/argv contract cases) in
  `.claude/hooks/lib/test_git_command_check.py` (86 pass), plus the opencode
  `.smoke.cjs` and `.test.ts` parity files.

### Layered defense and the one residual

The hook sees only the **command string**, so a bare `git push` on a branch
that *already* tracks master (created before the guard, or by a tool that
bypasses the hook) is invisible to it. Close that residual with one-time repo
config — a second, independent layer:

```
git config push.default current        # bare push always goes to the same-name remote branch
git config branch.autoSetupMerge false # branch creation never auto-sets an upstream
```

With `push.default=current`, even a mistracked branch's bare `git push` targets
`refs/heads/<branch>`, never master. (Not applied automatically — it changes
the repo's git behavior and is the owner's call.)

## 3. Brainstorm: a controlled git replacement (`arg`)

The hook is a *negative* control (block bad shapes). A stronger model is a
*positive* one: a binary we own that exposes only safe, intent-level
operations, with raw `git` mutations blocked by the hook so everything is
funneled through it.

### Shape

A small binary `arg` (e.g. Go/Python) earlier on `PATH` than `git`, or invoked
explicitly. It does **not** reimplement git — it orchestrates it (and existing
tooling) behind intent-based verbs:

| Intent | `arg` command | What it guarantees |
|---|---|---|
| Start work on current master | `arg start <name>` | fetch, branch off master with **no** master tracking, switch to it |
| Publish the branch | `arg publish` | push to the branch's **own** remote name, set upstream, optionally open a PR |
| Catch up | `arg sync` | rebase/ff the branch onto latest master; never touches master's remote |
| Land | `arg land` | merge via PR only; never a direct push to master |
| Status | `arg status` | current branch, upstream, ahead/behind, protected-branch warnings |

There is **no** `arg` verb that can push a non-master branch onto master, set a
master upstream, or force-push a protected branch — the dangerous operations
simply do not exist in the surface area.

### Behind the scenes — reuse what already exists

The `ar-manager` MCP already encodes much of the correct workflow:
`project_create_branch`, `project_verify_branch`, `project_commit_plan`,
`github_create_pr`, `github_pr_*`. `arg` can be a thin CLI over those tools
(plus plain git for read-only/local ops), so the binary inherits the server's
guardrails and audit trail rather than duplicating policy.

### Enforcement model

- The `block-branch-track-master` hook (and a broader `block-raw-git-mutation`
  policy) deny raw `git checkout -b`/`switch -c`/`push`/`branch -u` from the
  Bash tool, with the message "use `arg <verb>` instead."
- Read-only git (`status`, `diff`, `show`, `log`-for-SHA) stays direct.
- A protected-branch list (`master`, `main`, release branches) is config, not
  code.
- Every mutation `arg` performs is logged (who/when/what), giving a reviewable
  history of agent git activity.

### Rollout (incremental, each step useful alone)

1. **(done)** Deny hook for the specific footgun.
2. Recommend/apply the repo git config (`push.default=current`, no auto-merge).
3. `arg start` + `arg publish` over `ar-manager` — cover 90% of day-to-day flow.
4. Broaden the hook to steer all branch/push mutations to `arg`.
5. `arg sync`/`arg land`; protected-branch config; mutation audit log.

### Open questions / tradeoffs

- **Worktrees and submodules**: `arg` must handle (or explicitly refuse) these,
  not silently misbehave.
- **Escape hatch**: humans need a documented bypass (e.g. `AR_GIT_RAW=1`) for
  genuine edge cases; agents must not have it.
- **PATH-shadowing vs explicit binary**: shadowing `git` is the most thorough
  (nothing slips past) but riskiest (every tool that calls `git` hits `arg`);
  an explicit `arg` + hook is safer to adopt and is the recommended start.
- **Cross-harness**: keep the policy in the shared Python core (as the hook
  does) so Claude Code and opencode agree.
