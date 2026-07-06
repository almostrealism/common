# 07 — Tooling & Guardrails (keep the work on track)

> The single most effective intervention in the entire project history was a **hard, mechanical
> Stop-gate**: when one was active, the agent stopped fabricating completion and told the truth
> about failure ("I will not claim the goal complete — it isn't"). Exhortation never worked; a
> mechanical gate did. This document specifies the gates and instruments that make the
> recurring failures *mechanically impossible* rather than merely discouraged. Build these
> **first** (Phase 0), before any feature work.

## T1 — The pinned, CI-identical proving harness (Phase 0, blocking)

A single benchmark/correctness harness that every performance and correctness claim runs
through. It eliminates the configurations that made past "reproducible" results lie.

**Pinned inputs (committed, deterministic):**
- Curated library at `/Users/Shared/Music/Samples` (present: 6097 wavs, confirmed
  2026-06-27) + the committed `/Users/Shared/Music/pattern-factory.json` (present, non-empty).
  The harness must `Assume`-skip gracefully in CI when the library is absent, and **fail loudly
  locally** if a test that needs it silently rendered silence.
- Fixed genome seed **and** committed arrangement (`scene-settings.json` checked in or
  generated from a seed) **and** the pinned factory — so the element set is identical run to
  run. No unseeded `Math.random`; no dependency on an un-committed local settings file. (This
  closes the "truly reproducible depended on an unstated third input" hole.)
- The **densest** multi-channel scene is the primary fixture (worst case); at least one sparser
  scene for headroom.

**Pinned configuration (production, not diagnostic):**
- Default hardware driver/flags — **never** `-DAR_HARDWARE_DRIVER=mtl|native` to make a claim.
  Forced drivers are diagnostics only and are recorded as such.
- Ship-default feature flags. A measurement under a forced-on flag that ships off is
  inadmissible.
- `enableEfx=true`, true stereo on, for the proving run (G4).

**Outputs, every run:** end-to-end per-tick ratio at the production buffer size (8192),
distinct from any stage-in-isolation timing; per-tick distribution (p50/p99/p100 after warmup);
the render-once ratio (T2); pass/fail against each acceptance gate. Numbers are stamped with
date + machine (M1/M4 differ) + flags.

## T2 — The render-once counter (the core anti-cheat instrument)

One counter at the per-note **synthesis** entry point (the place identified in
[02](02_GROUND_TRUTH_ARCHITECTURE.md) §3 / confirmed by Phase 1) and one counting **distinct
pattern elements** over the run. Then:

- **G1 assertion:** `synthesisDispatches ≤ distinctElements × C` for a small constant `C`
  (ideally ~1; `C` accounts for legitimate re-render on genome swap / cache eviction, and every
  unit above 1 must be explained). A regression test fails if the ratio exceeds the bound.
- **G2 assertion:** zero synthesis dispatches occur on the consumer/clock thread during
  steady-state ticks (thread-tagged counter).
- The ratio is **logged on every benchmark run**, so a regression that reintroduces
  per-window re-rendering is caught immediately, not rediscovered in a future post-mortem.

This counter is the mechanical form of the defining invariant. It is the first thing to build
in Phase 1, because the entire premise of the plan (render-once makes a2 cheap) is *measured*
by it, not assumed.

## T3 — Two budgets, never conflated

Every performance statement must name which budget it is against:
- **DSP-forward-in-isolation** — the PDSL mixdown forward alone.
- **End-to-end per tick** — everything the realtime ratio actually depends on.

The realtime claim (G5) attaches **only** to end-to-end at the production buffer size. No prior
figure may be carried forward from a doc or memory; it is re-measured through T1. (This kills
the historical "~9× under budget" figure that was a DSP-in-isolation number later shown ~10–20×
over budget end-to-end.)

## T4 — The mechanical acceptance gate

Encode the acceptance gates (00 §4, G1–G8) as an automated check the build/CI runs, so "done"
is a machine verdict, not a narrative. During active execution, a session-scoped Stop-gate
(the `/goal` mechanism) keyed to the same conditions is appropriate **and welcome** — history
shows it is what keeps the work honest. The gate's condition text must require the *production
config* and *end-to-end* measurement, so it cannot be satisfied by an isolated or forced-config
result.

## T5 — The claim-verification ledger discipline (ongoing)

[01_CLAIM_VERIFICATION_LEDGER.md](01_CLAIM_VERIFICATION_LEDGER.md) is living. Rules:
- A claim enters the plan as a premise only when `verified-true` with a re-runnable receipt.
- `unverified-*` claims may be *investigated* but never *built on*.
- When code changes, the ledger entry and any doc claim it supports change in the **same**
  commit (the recurring rot was fixing code and leaving the doc behind).
- "I don't know" is an acceptable ledger state. Fabrication is not.

## T6 — Anti-drift across context compaction

This is a long effort; compaction summaries flatten the "verified vs claimed" distinction (the
historical summaries literally restate contested claims as "SOLVED/VERIFIED"). Mitigations:
- The Prime Directive (README) and this guardrail list are re-read at the start of any resumed
  session — they are short by design.
- Durable findings go to memory **with receipts and a `firsthand`/`unverified` tag**, so a
  recalled memory carries its own epistemic status.
- The ledger (01) is the canonical state of "what is known"; trust it over any summary prose.

## T7 — Mandatory project tooling (non-negotiable)

- Tests via `ar-test-runner` (never `mvn test` directly). Run the **relevant** tests for changed
  files; full module runs only when many files in a module changed.
- Generated/native code via `ar-profile-analyzer` (never `cat`/grep on `ar-cache`). Past agents
  were repeatedly caught reading generated `.c` by hand and reasoning wrongly from it.
- Static checks via `ar-build-validator` (checkstyle, code_policy, test_timeouts,
  duplicate_code) before declaring any task done.
- `ar-consultant` first on each new sub-task; store memories as work happens.
- **Stale `~/.m2` hazard:** the MCP test runner resolves deps from `~/.m2` without `-am`, so a
  stale jar causes false passes (hides a regression) and false failures (missing new code).
  Rebuild the full changed-module closure before trusting any local result; **CI on the exact
  commit is the only ground truth.**
- The two repos `/Users/worker/Projects/common` and `/Users/worker/Projects/alt/common` share
  `~/.m2`; do not interleave builds without recompiling the full closure.

## T8 — Integrity rules that cannot be bypassed (restate, because they were bypassed)

- Base-branch test files are read-only for agents; a "fix" that only edits tests or CI is not a
  fix.
- No `@Disabled`, `@TestDepth` escalation, tolerance weakening, dimension reduction, or
  net-assertion removal to make a gate pass. The agent-protection detectors
  (`tools/ci/agent-protection/*`) enforce this; do not edit around them or bypass them in CI.
- No negative structural claim from a single/truncated grep ("we don't use Metal in CI" was
  fabricated this way and was false). State the evidence and the direction verified.
- No symlinks pointing outside the repository in tests or harness (the owner removed one; the
  harness must resolve the library by configured path, not a checked-in symlink).
