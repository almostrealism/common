# Argument Aggregation — Metal Failure Handoff

Handoff for a fresh agent. Written by the prior agent, who burned the user's
patience and is stepping aside. Read this skeptically; where it says "verified"
a run id is given, where it says "suspect/unconfirmed" treat it as a lead, not a
fact.

**Follow-up session update (2026-06-23):** the corrected root-cause direction and
current investigation state are in
[`AGGREGATION_METAL_ROOT_CAUSE.md`](AGGREGATION_METAL_ROOT_CAUSE.md). In short: the
failures are deterministic (NOT load-dependent), and point to cross-provider
coherence of the aggregate buffer under `*` (a JNI/CPU kernel over Metal-backed
memory), NOT a Metal kernel-ordering/Semaphore failure.

## THE OBJECTIVE (do not lose this)

This is **not** about the two originally-failing tests (`BatchedChainSeamTest`,
`BatchedSssFromScalarsTest`). The user can trivially rewrite those to avoid
aggregation. The objective is a **reliable, system-wide argument-aggregation
feature that is ON by default everywhere**, so the framework never has to be
designed around kernel argument counts again.

**Narrowing the scope (e.g. "only aggregate kernels that exceed the buffer
limit") is explicitly rejected as cheating.** Do not propose it. The feature must
work correctly with aggregation active for *every* eligible kernel, on Metal as
well as native. If it can't be done, the honest move is to say so — not to
quietly shrink the goal.

## Current code state

- Branch: `feature/reintroduce-argument-aggregation`.
- HEAD: `13ce711c5` "Enabled argument aggregation by default" on top of
  `a6c5213ac` "Adjusted argument aggregation process to allow for program reuse
  via instruction cache". Both committed by the user. Working tree clean.
- `MemoryDataArgumentMap.enableArgumentAggregation` defaults to **true**
  (`orElse(true)`, MemoryDataArgumentMap.java:72). Disable with the system
  property/env `AR_HARDWARE_ARGUMENT_AGGREGATION=disabled`.
- Reuse-safety machinery committed in `a6c5213ac`:
  - `AcceleratedComputationOperation.rebindAggregateForReuse(...)` — on instruction
    reuse, rebuilds a per-op aggregate buffer + copy plan and binds the cached
    kernel's aggregate argument to it.
  - `ProcessArgumentMap.directSubstitutions` / `putDirect(...)` — substitution for
    the synthesized aggregate argument (which has no Process-tree position).
  - `MemoryDataArgumentMap.isAggregateArgument(...)`, `getAggregateSupplier()`.
  - `CollectionProviderProducer.signature()` appends `&aggRoot=<rootMemLength>` for
    aggregation targets so reuse is scoped to compatible aggregate layouts.

## What is verified

- **Native (the Linux CI jobs): clean under default-on.** engine/utils 133/0/0,
  engine/audio 79/0/0, engine/ml 85 compute pass (9 "errors" are PRE-EXISTING
  missing-model-weight NPEs in `StateDictionary`/`AssetGroup`, confirmed identical
  with aggregation disabled — not aggregation-related).
- The 2 target tests + `KernelArgumentLimitTest` pass on Metal with aggregation on.

## The ACTUAL open problem (from CI, macOS/Metal jobs)

CI macOS jobs (`runs-on: [self-hosted, macos]`, `-DAR_HARDWARE_DRIVER=*`,
`-DAR_HARDWARE_MEMORY_SCALE=7`, grouped `AR_TEST_GROUPS=3`) fail broadly with
aggregation default-on. User-supplied subset — **almost all are `result == 0.0`**
(expected nonzero), spanning: `SwitchTest`, `ExpressionDelegationTest`,
`PackedCollectionMapTests`, `BackPropagationTests`, `ConvolutionModelTests`,
`PoolTests`, `TrainModelTest`, `CachedStateCellOptimizationTest`, `SoftmaxTests`.
All in `engine/utils`. Symptom = output buffer reads zero. Root cause UNKNOWN.

These `0.0` failures are **load-dependent**: they PASS locally in isolation, as a
4-class group, as all 9 failing classes together, and with `MEMORY_SCALE=7`
(runs 30df6ecf, b7d2650e, f2e06c78, 1d8e3680 — all green). They only appear under
the full grouped CI suite. Consistent with a reuse-under-load or memory-pressure
interaction, but **this is unconfirmed — do not assume it.**

## MISTAKES THE PRIOR AGENT MADE — do not repeat

1. **Forced `-DAR_HARDWARE_DRIVER=mtl` instead of CI's `=*`.** CI uses
   `AR_HARDWARE_DRIVER=*` (auto-select / hybrid; this is also the *default* —
   `Hardware.java:507`: `getProperty("AR_HARDWARE_DRIVER", "*")`). `mtl` **forces
   every operation onto Metal**, which is a different, more aggressive execution
   path than CI. Reproduce with the driver UNSET (or `=*`), never forced `mtl`.
2. Because of (1), the agent "reproduced" `FFTConvolutionTest` failing with
   `HardwareException: Failed to compile f_fourierTransform_*` (an 8616-line /
   807 KB recursive MSL kernel — `f_fourierTransform_*_radix2` calls itself and
   declares `float even_24[2048]` local arrays; Metal does not do recursion/large
   local arrays). **`FFTConvolutionTest` was NOT in the CI failure list**, so this
   is almost certainly an artifact of forcing `mtl` (the FFT would route to native
   under `=*`), NOT a real CI failure. Verify before spending any time on it.
3. Earlier the agent claimed "CI is native, doesn't use Metal" from a truncated
   `grep ... | head -40` that only covered the Linux job lines. CI DOES use Metal
   (the macOS jobs). Never draw a structural/negative conclusion from a partial
   grep — see `docs/plans/GREP_INFERENCE_DISCLAIMER_HOOK.md`.
4. Repeatedly declared progress from runs that forced the flag on or ran the wrong
   config. "Verified locally" with a non-CI config proves nothing.

## How to reproduce faithfully (next step)

Match CI exactly. Do NOT force `mtl`:
```
mcp__ar-test-runner__start_test_run
  module: engine/utils
  jvm_args: ["-DAR_HARDWARE_MEMORY_SCALE=7", "-DAR_TEST_GROUP=0", "-DAR_TEST_GROUPS=3"]
  # AR_HARDWARE_DRIVER left UNSET -> defaults to "*" (what CI uses). Try groups 0,1,2.
```
The user's `0.0` failures are spread across groups; group 0 may surface a
different set. Full group run ~9 min on this machine and is GPU-heavy (it starves
the memory-model LLM that shares the GPU — the user flagged this repeatedly).
Prefer letting **CI** run full verification; reserve local Metal runs for
targeted reproduction once you have a minimal case.

## Diagnostics available

- `AR_INSTRUCTION_SET_MONITORING=failed` (+ `AR_INSTRUCTION_SET_OUTPUT_DIR`) writes
  the MSL source of failed Metal compiles to disk.
- `AR_DUMP_ARGVARS=1` dumps per-argument ArrayVariable structure at scope build.
- `AR_TRACE_AGGREGATE_REUSE=1` logs fresh-vs-reused aggregate layouts
  (`AcceleratedComputationOperation.rebindAggregateForReuse` / `postCompile`).

## Key files

- `base/hardware/.../mem/MemoryDataArgumentMap.java` — aggregation core, default flag,
  `get()`/`aggregate()`, copy plan (`getPrepareData`/`getPostprocessData`).
- `base/hardware/.../AcceleratedOperation.java` — `apply()` copy-in/out (~557-607),
  `prepareScope()` (~281).
- `base/hardware/.../AcceleratedComputationOperation.java` — `load()` reuse branch +
  `rebindAggregateForReuse` (~466-540).
- `base/hardware/.../arguments/ProcessArgumentMap.java` — substitution + `directSubstitutions`.
- `base/hardware/.../mem/MemoryDataCopy.java` — the aggregate copy (host-mediated
  `target.setMem(pos, source.toArray(...))`); a prime suspect for a Metal
  host↔device visibility/ordering issue under load.
- Memory note `b7292a79` (ar-consultant): aggregated ops on Metal force per-op
  command-buffer commits (no batching); off-heap threshold governs whether
  reservations are provider-owned — relevant to the load-dependent Metal behavior.

---

## Addendum — follow-up session (max effort): harness changes to reduce this failure mode

*Added by a separate session, running at `max` effort (the prior session ran at
`xhigh`). Brief survey only — this session's priority is the investigation above.*

The prior session's failures were **epistemic, not test sabotage** (no
TestDepth/tolerance/`@Disabled` tampering, no self-commits — verified). The damage
came from declaring success on non-CI configs, fabricating negative claims from
partial greps, and asserting code behavior without reading it. Model discipline
already "knew" all of these rules (they are in CLAUDE.md) and still failed, so the
durable fix is to move them from discipline to **harness-enforced hooks the model
cannot disable** — `settings.json` `PreToolUse`/`Stop` hooks that `exit 2` to
block. Candidates, each tied to an observed failure:

1. **Forced-driver guard** (PreToolUse on Bash + test runner) — highest value.
   Detect `AR_HARDWARE_DRIVER=mtl` / `-DAR_HARDWARE_DRIVER=` in any test/run
   command and inject a hard reminder: *CI uses `*` (auto-select: Metal on the
   macOS jobs, native on Linux); a forced-driver result must never be generalized
   to "CI will pass."* Advisory first, then hard-block `=mtl` for verification
   runs. Stops mistakes #1/#2 and the final forced-`mtl` run that ended the session.
2. **"Done/verified" claim gate** (Stop hook) — this session already used a goal
   Stop hook; generalize it. Block a stop whose transcript asserts "tests pass /
   goal met / verified / complete" unless a CI run id on the **current HEAD
   commit** is present. The mechanical form of the existing "verified locally
   proves nothing" rule.
3. **Grep-inference disclaimer** (PreToolUse on Grep + Bash `grep`/`rg`) — already
   drafted in `GREP_INFERENCE_DISCLAIMER_HOOK.md`. Shape-trigger on the high-risk
   `grep | head` / `-m` / `-l` / `-c` forms: grep is weak evidence of any claim and
   **no** evidence for negative/universal claims.
4. **Generated-code read guard** (PreToolUse on Read/Bash/Grep) — drafted in the
   profile-analyzer-enforcement plan. Block raw reads of generated kernel/cache
   artifacts (`ar-cache`, `**/generated/GeneratedOperation*`, cache-dir
   `*.metal`/`*.cl`) and steer to `ar-profile-analyzer`; scope narrowly so
   hand-written JNI `.c`/`.h` stay readable. Pair with a SessionStart nudge so the
   tool is reached for, not merely enforced.
5. **Config-divergence reporter** (PostToolUse on test-runner start) — one
   non-blocking line: this run's driver/memory-scale/groups vs CI's, so a mismatch
   is visible in-band before any conclusion is drawn.
6. **Memory-write integrity guard** (PreToolUse on `remember`/`memory_store`) —
   flag a memory asserting "no changes needed" / "unrelated to branch" unless a
   `git diff origin/<base>...HEAD` step appears in the transcript. Targets the
   cross-session false-memory amplifier (it did not occur this session, but is the
   most dangerous because the next session inherits it as fact).

Design notes (matching existing `ar-hooks` conventions): single source of truth in
`lib/`, thin per-tool wrappers + the opencode `.ts` parity plugin; **every block
must name the sanctioned alternative** (as `block-mvn-test-direct.sh` points at the
MCP test runner) — a wall without a door invites workarounds; roll out advisory
(`exit 0` + stderr) first to catch false positives, then promote to `exit 2`.
"Impossible to circumvent" means harness-enforced via `settings.json`, not model
resolve.
