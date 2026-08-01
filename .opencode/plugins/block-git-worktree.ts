// .opencode/plugins/block-git-worktree.ts
//
// opencode adapter for the "block git worktree add" policy.
//
// The decision logic lives in the shared core
//   .claude/hooks/lib/git_command_check.py
// (policy: block-git-worktree). This plugin is the opencode
// counterpart to .claude/hooks/block-git-worktree.sh: it pulls the
// bash command out of the opencode tool-call event, asks the shared
// core to decide, and translates the structured result into the
// opencode-native mechanism (throw to block, mutate output.output to
// warn).
//
// The structural plumbing (path resolution, subprocess spawn, callID
// cache, error handling) lives in
//   .opencode/plugins/_lib/command_pattern.ts
// so this file is just the policy wiring. See docs/plans/OPENCODE_HOOKS.md.

import { makeCommandPatternPlugin } from "./_lib/command_pattern"

export const BlockGitWorktreePlugin = makeCommandPatternPlugin("block-git-worktree")
