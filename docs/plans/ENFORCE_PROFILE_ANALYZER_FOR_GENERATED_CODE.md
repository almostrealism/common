# Hooks: force generated-code reads through `ar-profile-analyzer`

## Problem

When inspecting **generated/compiled operation code** (native C in the
`ar-cache/Extensions` dir, `org.almostrealism.generated.GeneratedOperationN.java`,
Metal/OpenCL kernels), the agent defaults to grepping `ar-cache` / reading the raw
`.c` by hand instead of using the `ar-profile-analyzer` MCP tool. This is both
clumsy and unreliable: hand-reading raw `.c` led to repeatedly (and wrongly)
concluding generated code was "identical" during the `ResampleEquivalenceTest`
regression. The right tool exposes structure that raw files don't:

- `load_profile` → `search_operations` → `get_source` — authoritative generated
  source per operation node.
- `get_source_summary` — structural breakdown (math-op counts, duplication groups,
  loop counts) that catches differences eyeballing `.c` misses.
- `list_children` — the operation **tree** (split/merge/argument-mapping changes a
  single `.c` file can't reveal).

## Goal

Block (or steer) any attempt to read generated artifacts directly, and redirect to
`ar-profile-analyzer`. Model on the existing precedent `block-mvn-test-direct.sh`
(blocks `mvn test`, steers to the MCP test runner).

## Proposed hooks

All `PreToolUse`, wired in `.claude/settings.json`. One core decision module in
`.claude/hooks/lib/generated_code_check.py` (single source of truth), thin
per-tool wrappers, plus an `.opencode/plugins/*.ts` parity plugin (same pattern as
`block-git-worktree`).

1. **Read guard** (matcher `Read`) — block when `file_path` matches generated
   artifacts: `**/ar-cache/**`, `**/org/almostrealism/generated/GeneratedOperation*.java`,
   and `*.metal` / `*.cl` / `*.so` / `*.dylib` under cache/lib dirs.
2. **Bash guard** (matcher `Bash`) — parse the command; block when
   `cat`/`grep`/`rg`/`sed`/`head`/`tail`/`find`/`diff`/`less` targets `ar-cache`,
   `GeneratedOperation`, or those kernel/object extensions. (This is the one that
   stops the common `grep ar-cache/*.c` path.)
3. **Grep guard** (matcher `Grep`) — block when the Grep `path`/`glob` points at
   `ar-cache`, `.../generated/`, or kernel extensions.

Each blocks with `exit 2` and a message that **names the alternative** and the
recipe (`load_profile → search_operations → get_source/get_source_summary`).

## Design points

- **Scope precisely to avoid false positives.** Match `ar-cache` (basename — any
  `$TMPDIR` / `/var/folders` prefix) and `org/almostrealism/generated/GeneratedOperation`,
  plus kernels/objects **only under cache/lib/temp dirs**. Do **not** block all
  `*.c` — `base/hardware` has legitimate hand-written JNI `.c`/`.h` that must stay
  readable.
- **The block message must teach the recipe**, including how to capture a profile
  (`OperationProfileNode p = initKernelMetrics(new OperationProfileNode(name)); try { ... } finally { p.save("results/" + name + ".xml"); }`),
  so the redirect is actionable rather than a wall — mirroring how
  `block-mvn-test-direct.sh` points at the test runner.
- **Pair the block with a proactive nudge.** The root failure was not *defying* the
  tool but not *thinking* of it. Add a line to `capability-process-reminder.sh` /
  SessionStart: "to read generated operation code, use `ar-profile-analyzer`."
- **Lower the friction the block creates.** Blocking only works if capturing a
  profile is *easier* than grepping. Consider a cheap `AR_PROFILE_DUMP=results/x.xml`
  env hook (or a `TestFeatures` one-liner) so the sanctioned path isn't more work —
  otherwise blocks invite workarounds.
- **Optional bake-in.** Ship as advisory first (`exit 0` + stderr, like
  `steer-ls-grep.sh`) to catch false positives, then promote to hard `exit 2`.

## Status

Brainstorm only — not yet implemented.
