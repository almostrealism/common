// .opencode/plugins/block-branch-track-master.ts
//
// opencode adapter for the "block-branch-track-master" policy.
//
// The decision logic lives in the shared core
//   .claude/hooks/lib/git_command_check.py
// (policy: block-branch-track-master). This plugin is the opencode counterpart
// to .claude/hooks/block-branch-track-master.sh: it pulls the bash command out
// of the opencode tool-call event, asks the shared core to decide, and
// translates the structured result into the opencode-native mechanism (throw
// to block).
//
// The structural plumbing (path resolution, subprocess spawn, callID cache,
// error handling) lives in
//   .opencode/plugins/_lib/command_pattern.ts
// so this file is just the policy wiring. See docs/plans/OPENCODE_HOOKS.md.

import { makeCommandPatternPlugin } from "./_lib/command_pattern"

export const BlockBranchTrackMasterPlugin = makeCommandPatternPlugin("block-branch-track-master")
