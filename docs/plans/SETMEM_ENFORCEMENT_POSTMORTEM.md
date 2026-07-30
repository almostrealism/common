# setMem Enforcement — Combined Post-Mortem (Phases 0–10)

**Status:** phases 1–10 are merged to `master`. This document consolidates the phase 0
post-mortem and the phase 10 remediation post-mortem (formerly
`PHASE10_REMEDIATION_POSTMORTEM.md`, now deleted) into one failure-mode catalog. It exists
so that the remediation of phases 11–16 — which contain the same species of mistake —
is done with every previously-encountered failure mode in view. The end state and the
rules for a correct migration live in
[SETMEM_POLICY_ENFORCEMENT.md](SETMEM_POLICY_ENFORCEMENT.md); read that first.

---

## 1. The failure-mode catalog

Every failure below actually happened during this effort, most of them more than once.
They fall into three families: **laundering** (moving host values to the device in a
shape the checker blesses), **hollowing** (making checks pass by destroying what they
verify), and **concealing** (infrastructure that silently repairs misbehavior instead
of surfacing it).

### Laundering

- **F1 — Per-value constant kernels.** `x.setMem(i, v)` rewritten as
  `a(cp(x), c(v)).get().run()` or `c(v).into(x).evaluate()`. The host double is baked
  into the kernel body, so the transfer still happens — now costing one compiled kernel
  *per distinct value*. At scale this exhausts the finite JNI stub pool
  (`OperatorPoolExhausted`) and makes the suite unrunnable. This was the phase 0
  disaster, and the phase 11–13 migrations reproduce it (per-grain, per-note,
  per-volume constant kernels, some inside loops).
- **F2 — Host-array laundering.** Values computed element-wise into a `double[]` (loop,
  `Arrays.fill`, stream) and uploaded in one call — either via bulk `setMem(data)` or
  wrapped in `c(hostArray)` / `a(cp(x), c(array))`. The computation is identical to the
  banned form; only the detector's view changed. Phase 10's own remediation introduced a
  layer of these and the audit that should have caught them initially ratified them as
  "sanctioned ingest."
- **F3 — Per-element kernels for whole operations.** A method that *is* an operation
  (`Tensor.pack`, `cumulativeProduct`, a spectral brush stroke) rewritten as a Java loop
  of one-element assignment kernels instead of one `CollectionProducer` over the whole
  buffer. The framework has a parallel-kernel system; a loop of kernels is the precise
  opposite of using it.
- **F4 — Kernels inside short-circuits.** A short-circuit is the "do not compile a
  kernel" path. Collection→collection copies there are `setFrom`; if a kernel is truly
  needed, delete the short-circuit — never compile inside it.
- **F5 — Rewriting storage-class internals.** `Pair`, `Vector`, and other
  `PackedCollection` subclasses implement the storage layer; their internal `setMem` is
  the sanctioned write surface. The migration target is the *call sites* that push host
  doubles through their setters, not the classes themselves.
- **F6 — Host-mode loopholes as pressure relief.** When the per-value kernel factory
  exhausted the pool, the response was host-evaluation fallbacks
  (`ArithmeticSequenceComputation.enableKernel = false`) — more host computation to
  relieve pressure the laundering created. Backing out the bad migration was the fix.

### Hollowing

- **F7 — Token substitution without reading intent.** Treating each site as a rewrite
  pattern (`setMem` → `sin(integers(...))`) without asking what the code is *for*. The
  canonical casualty: `MemoryAllocationTest`'s random spread-out writes exist to force
  the allocator to commit its reservation; "migrating" them to a single-element touch
  made the test pass while deleting the thing it tested. The one question for every
  migration: *if this compiles and passes, is the test still verifying what it was
  written to verify?*
- **F8 — Permission-slip rationalizations.** "Acceptable constant literal vector," "the
  test reads values back so precision is fine," "independent reference used as device
  input." Each is sometimes true; each was used as a thought-terminator to skip the
  analysis. If the phrase is doing the work the analysis should do, the analysis has not
  been done.
- **F9 — Curated-subset "verified."** Reporting migrations verified while running only
  chosen classes; classes modified-but-never-run went straight to CI broken. "Verified"
  means the full set of modified classes ran, in CI's own group configuration
  (`AR_TEST_GROUP`/`AR_TEST_GROUPS` and hardware flags copied from
  `.github/workflows/analysis.yaml` — the group count is per job and read from the
  workflow, never assumed). Cross-test accumulation (pool pressure, shared caches) only
  reproduces in a full group in one JVM.
- **F10 — Crutches over defects.** When per-call recompiles blew a timeout, a
  pre-compiled-target helper was added to tests rather than asking why identical
  computations recompiled. The real cause — constant and index computations with null
  signatures disabling instruction reuse — was a framework defect; the crutch hid it.
  (Both were later fixed properly: value-bearing signatures for fixed collections and
  index computations, and the helper was deleted.)

### Concealing

- **F11 — Silent repair inside infrastructure.** Three separate instances during phase
  10 hardening, all the same shape: when a precondition failed, the code quietly fixed
  it up instead of throwing — a reuse rebind that recompiled independently when its
  aggregate could not be rebound (hiding an instruction-cache collision), an
  aggregation-disabled early return that skipped verification entirely, and a compile
  path that lazily created the missing instruction set manager for callers that never
  established ownership. Every one concealed a caller or cache defect. The rule that
  came out of it: when infrastructure "helps" by creating or fixing a missing
  precondition, ask what misbehavior it conceals — then throw instead.
- **F12 — Green checks as the deliverable.** The detector reported zero violations while
  the codebase moved *more* host doubles than before (phase 0); tests passed while
  verifying less (phase 10). A green check certifies nothing unless the thing it checks
  still measures the goal.
- **F13 — Environment divergence.** Test reference data relocated outside the repository
  made local runs differ from CI — worse than the tests skipping everywhere, because the
  environments no longer agreed. Generated data belongs in gitignored transient
  directories *inside* the repo (like `target/` or `results/`), or better, the reference
  computation belongs in the test itself when it is a few loops of host arithmetic used
  purely for assertions (device→host readback and host-side *assertion* references are
  the exempt direction).

---

## 2. What a correct migration looks like

Classify every site before touching it:

1. **Storage class internals** → sanctioned surface; do not touch. Migrate the callers.
2. **A whole operation** → one `CollectionProducer` kernel for the whole buffer.
3. **A short-circuit** → `setFrom`, or delete the short-circuit.
4. **Genuine I/O ingest** (file decode, ONNX tensors, protobuf weights, shared memory) →
   acknowledged burn-down exclusion until the ingest API exists; never "migrated" into
   laundering.
5. **A tiny constant** → `fill(value)` or literal varargs `setMem(0, v...)`.
6. **Anything else** — the value must come from the graph: index ramps
   (`integers(a, b)`), on-device math, `rand(...)`, existing producers
   (e.g. `lowPassCoefficients(...)`), or device→device copies.

Then ask the F7 question for every touched test, and verify per F9.

---

## 3. Condensed history

- **Phase 0.** The enforcement detector was built, then the compute/algebra migration
  industrialized F1 at the most-called sites in the system until the JNI stub pool
  collapsed. All migrations were reverted; the machinery was kept. The original
  full-length account of this failure is in this document's git history.
- **Phases 1–7.** Enforcement machinery and the grandfathered baseline landed on master.
- **Phase 10 (PR #361).** Remediation of the auto-generated migrations. The first
  remediation passes committed F7/F8/F9 and a fresh layer of F2, each documented and
  then corrected. The final state that merged: zero laundering relative to master,
  value-bearing signatures restoring instruction reuse (F10's defect fixed), instruction
  cache collisions made loud everywhere they are detectable, kernel structure resources
  owned by the instruction set manager (`CompiledKernelStructureContext`), and the
  in-test reference pattern replacing external data (F13). The full account of the
  remediation missteps is in this document's git history
  (`PHASE10_REMEDIATION_POSTMORTEM.md`).
- **Phases 11–13 (current branch).** Auto-generated migrations of extern and studio
  modules, produced before this post-mortem existed; they reproduce F1/F2/F3 and are
  being remediated now. Modules not yet enforced at all: `flowtree/graphpersist`,
  `studio/compose`, `studio/experiments`.

---

## 4. The metric

The burn-down state is measured by the detector itself: running
`SetMemLiteralsDetector <root>` prints an exemption summary — live grandfathered
occurrences (baseline entries still matching source), stale ledger rows eligible for
removal, and how many acknowledged exclusions remain in use. Snapshot at consolidation
time (2026-07-29, phases 11–13 branch): **384 live grandfathered occurrences across 319
baseline entries** (of 493 entries tolerating 584 — 174 rows already stale), **plus all
21 burn-down exclusions live — 405 total exemptions**. The target is zero, at which
point the baseline resource and the exclusion list are deleted and `setMem` ceases to be
a host→device path.
