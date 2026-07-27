# Stale Evaluable Investigation — Silent Zero Output from Long-Lived Evaluables

**Status:** root cause localized to a specific object with a paired in-situ proof; the
exact stale internal field is not yet identified. **No fix is landed.** This document
exists so the investigation can resume from its evidence rather than from its symptom.

**Symptom that started it:** `AudioSceneBufferConsolidationTest.genomeIndependence`
failing reliably on `test-media-mac` ("Different genomes should produce different
audio", rmsDiff ~1e-6..1e-5, maxDiff frequently exactly 0.0) on
`feature/setmem-policy-phases/10`, not reproducing on master or in a clean JVM.

---

## 1. The finding, in one paragraph

An `Evaluable` that is compiled once and held for a long time (the pattern the
hardware README explicitly recommends: "holding onto the Evaluable avoids cache
lookup") can silently enter a state where `evaluate()` runs without any exception,
without any Metal command-buffer error, and **writes nothing to its destination** —
the destination retains its prior contents (zeros, for a fresh allocation). A freshly
constructed `Evaluable` of the *identical computation*, invoked at the *same moment*
with the *same arguments* into the *same destination buffer*, writes the correct
values. The broken object is therefore the long-lived `Evaluable` instance itself,
not the computation, the arguments, the memory, the backend, or the signature-keyed
instruction cache. The state only arises in JVMs with a large accumulated compilation
history (a full CI test group); no single test causes it.

The concrete victim in the observed failure is
`ProjectedGene.REFRESH_KERNELS` (`domain/heredity/.../ProjectedGene.java`) — the
static map of refresh kernels keyed by `length:sourceLength` (the failing key was
`3:16`). Its silent failure leaves every pattern-parameter gene's `values` collection
at zero, so `ParameterSet.fromGene` (`studio/music/.../ParameterSet.java:97`) yields
`(0, 0, 0)` for every gene, pattern layouts become a function of scene structure only
(frozen against genome changes), and two different genomes render identical audio —
the genomeIndependence failure. A byproduct tell: `findWorkingGenomeSeed` always
reports "Best seed: 42" in a poisoned JVM, because all candidate seeds produce
identical layouts and the first seed wins the tie.

## 2. The decisive experiment

`ProjectedGene.refreshValues()` was temporarily instrumented so that whenever the
statically cached evaluable produced an all-zero destination while `weights` and
`source` were both non-zero, it logged the sums and then rebuilt the identical
kernel fresh (`buildRefreshKernel`) and evaluated it with the same arguments into the
same buffer:

```
refreshDiag      valuesSum=0.0 weightsSum=-1.4580... sourceSum=7.0011... key=3:16
refreshDiagFresh valuesSum=1.4602...                                    key=3:16
```

Run `3860880d` produced **14,078 such pairs**: stale instance zero, fresh instance
correct, every time. Because the diagnostic repaired the buffers as a side effect,
genomeIndependence *passed* in that run — incidental confirmation of the entire
causal chain from the stale evaluable to the test failure.

## 3. Eliminations (each with the run that proves it)

All runs are ar-test-runner runs on `studio/compose` (Metal, this repo's mac).
"Union" = the executed classes of CI group 1 in one JVM:
`AudioScenePopulationLatchTest, MultiDevicePlaybackTest, BufferedAudioPlayerTest,
MoonbeamValueDistributionTest, MoonbeamFineTuningTest, MidiTrainingTest,
MoonbeamComponentTest, PrototypeDiscoveryApiTest, DelayFeedbackBankPdslTest,
MixdownLayerPerformanceTest, AudioLibraryCacheTest, MigrationClassLoaderTest,
DiskStoreAudioLibraryTest, AudioLayerPitchTest, AudioLibraryMigrationTest,
MixdownManagerFilterAutomationTest, ProducerEvalCachesKernelTest,
AudioSceneMultiGenomeTest, AudioSceneBufferConsolidationTest`
(profile `pipeline`, no depth limit).

| Hypothesis | Verdict | Evidence |
|---|---|---|
| The assertion is fragile / measuring noise | **No** | Clean JVM margin is ~500x the threshold (rmsDiff 0.0527 vs 0.0001; run `9aa95115`) |
| Genome params fail to propagate (assignTo/refresh of the *scene genome*) | **No** | Checksums in `ProjectedGenome.assignTo`: params and consolidated values change correctly in the failing JVM (run `f8d75e13`). Note: the scene genome's chromosomes are a *different gene population* from the pattern-parameter genes that break |
| Pattern layout ignores gene values by design | **No** | Clean JVM: layouts vary per seed; poisoned JVM: `assignGenomeDiag` layout checksums bit-identical across all seeds and genomes (run `fb647db1`) |
| Signature-keyed instruction-cache reuse returns a wrong kernel per evaluation | **No** | With `ScopeSettings.enableInstructionSetReuse=false`, fresh-compiled `fromGene` reads are also zero (run `a3654f2a`) — because the *buffer* was zero; and `readDiag` shows host read == kernel read == 0, `delegateDepth=0` (run `1bcc0725`) |
| Metal watchdog kill / GPU error (silent never-ran buffers) | **No** | New `commandBufferStatus`/`commandBufferError` JNI + per-drain check: **zero** errored buffers in a failing union run (`adf7b996`) |
| JUnit-timeout interrupts corrupt the command runner | **Not causal** | The single interrupt warning occurs *after* the zero regime begins (`adf7b996`); Moonbeam timeouts also occur in passing runs (`7a856734`, `baa00312`) |
| Pure FrequencyCache eviction pressure | **Not sufficient** | `AR_INSTRUCTION_CACHE_SIZE=60` (temporary knob in `DefaultComputer`), single class alone: heavy eviction, passes with correct reads (run `299fd8a5`) |
| A single culprit test class | **No** | Every proper subset tried passes: `adef2003` (MidiTraining+scene), `7a856734` (prefix), `5901c2bd` (suffix), `baa00312` (Moonbeam+suffix), `c69347ca` (MidiTraining+suffix). Only the full union fails (`17befe09`) |
| Skipped classes' static initializers | **No** | The union contains no skipped classes and still fails (`17befe09`) |

Additional facts:

- The zero regime begins at the **first scene test** of the poisoned JVM and is
  permanent for the rest of the JVM's life; large render kernels in the same JVM
  keep producing correct non-zero audio throughout.
- `MetalCommandRunner.await()` previously swallowed `InterruptedException`
  silently, so a caller could proceed as though GPU work had completed; it now
  warns (kept change, see §6). Not the cause here, but a real silent path.
- `MTL.waitUntilCompleted` never checked `MTLCommandBuffer` status before this
  work; a watchdog-killed buffer was indistinguishable from success (kept change,
  see §6). Not the cause here, but exactly the class of silent failure this
  codebase must be able to see.

## 4. What is NOT yet known

Which internal state of the long-lived evaluable goes stale. The prime suspect is
the interaction between the holder and its `ScopeInstructionsManager` after the
`FrequencyCache` (capacity 500, `DefaultComputer`) evicts and destroys the manager's
`InstructionSet` and a recompile occurs (`ScopeInstructionsManager.getOperator`
recompiles via the retained scope supplier): the holder's retained argument/output
binding (`setupArguments` results, `ProcessArgumentMap` substitutions,
`outputArgIndices`/`outputOffsets` — which are keyed by `ScopeSignatureExecutionKey`,
i.e. shared by *every* operation with the same signature) may no longer match the
recompiled kernel, so the kernel's output lands in the wrong slot and the intended
destination is never written. Destroy+recompile alone is *not* always fatal (the
capacity-60 run passed), so some further ingredient — most plausibly another
same-signature holder touching the shared manager between destroy and reuse — is
part of the recipe.

## 5. How to reproduce and how to resume

1. Reproduce: run the union class list above in one JVM
   (`test_group`-equivalent; ~12 min). genomeIndependence fails; with the
   `refreshDiag` instrumentation the stale/fresh pairs appear en masse.
2. Resume point: instrument `AcceleratedComputationEvaluable` /
   `ScopeInstructionsManager` to record, for the `3:16` refresh evaluable
   specifically: manager identity, InstructionSet identity/destroyed flag,
   output argument index/offset, and argument list, at first compile and at each
   evaluate — then diff a healthy evaluation against a stale one from the same run.
   One union run should pin the exact stale field.
3. Try to build a minimal deterministic repro: hold an `Evaluable` over
   `Input.value` placeholders; flood the instruction cache (small
   `AR_INSTRUCTION_CACHE_SIZE`, many distinct kernels); interleave evaluations of a
   *second* holder with the same signature; re-evaluate the first and assert
   non-zero output. If this reproduces, it becomes the regression test for the fix.

## 6. Fix plan

1. **Loud guard (do first — the owner's explicit priority):** it must be impossible
   for a defective Evaluable to return silently. Options, in increasing precision:
   - In `ScopeInstructionsManager`, when `getOperator`/`getInstructionSet` finds its
     `InstructionSet` destroyed and recompiles, notify/invalidate holders (a
     generation counter on the manager; holders record the generation at
     argument-binding time and `evaluate` throws `HardwareException` on mismatch
     instead of executing with bindings from a previous generation). This targets
     the suspected mechanism directly and is cheap.
   - The already-added Metal status check (§3) covers the sibling failure class
     (killed buffers) and should be kept and eventually escalated from `warn` to
     `HardwareException` once its false-positive rate is confirmed to be zero.
2. **Actual fix:** once the stale field is pinned (§5.2), rebind on recompile —
   the holder refreshes its argument/output binding from the recompiled scope
   rather than throwing. The generation counter from the guard is the natural
   trigger for rebinding.
3. **Regression coverage:** the minimal repro from §5.3, plus re-enabling the union
   run as the integration-level check.

## 7. State left behind by the investigation

- **Kept, staged-candidate changes (no behavior change, new observability):**
  `MTL.cpp` + `MTL.java` `commandBufferStatus`/`commandBufferError`;
  `MTLCommandBuffer.isError()`/`getError()`;
  `MetalCommandRunner.drainOldestCommitted` status check with
  `errorCompletions` counter; `MetalCommandRunner.await` interrupt warning.
  `libMTL.dylib` was rebuilt via `base/hardware/src/main/cpp/compile.sh`.
- **Diagnostics that must NOT land** (all log lines named `*Diag`; unstaged):
  checksum logging in `ProjectedGenome.assignTo` and
  `ProjectedChromosome.diagValueSum`; layout checksum in
  `AudioScene.assignGenome`; extraction logging + fresh-eval probe in
  `ParameterSet.fromGene`; `ProjectedGene.logReadDiag` and the
  `refreshDiag`/`refreshDiagFresh` probe (the latter *repairs* the buffers as a
  side effect and therefore masks the failure — it is proof apparatus, not a fix);
  the `AR_INSTRUCTION_CACHE_SIZE` property in `DefaultComputer` (useful; could be
  kept deliberately, but was added for the experiment).
- `~/.m2` contains instrumented `ar-hardware`, `ar-heredity`, `ar-music` built with
  `-Dcheckstyle.skip`; run `mvn clean install -DskipTests` before trusting
  unrelated local results.
- The phase-10 `sineSignal` test cleanup that made CI green by reducing kernel
  load was deliberately **removed** (owner decision): it concealed the trigger.
  Its patch is preserved outside the tree; do not land it as a "fix" for this.
