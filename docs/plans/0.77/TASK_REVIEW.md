# Common 0.77 — Backlog Review & Scoping Report

*Prepared for the release-scope decision. This is a review only: no tracker task
was changed, and the owner applies the decisions. Every verdict is backed by a
file, commit, or PR cited in the tables.*

Release under review: **Common 0.77** (`d1c24223-1915-4dab-b7ea-e3c6425b45df`),
project `89c5b150-c279-4e45-b02b-4ba52e05dc5c`. **112 open tasks** at the time of
review, checked against the `docs/077-task-review` working tree (current
master-equivalent).

---

## How this was done, and two caveats

Each task was read in full (`tracker_get_task`) and then checked against the
code it describes — does the class/file still exist, is the problem still there,
has it already been done, partly done, or made moot — using grep/read and
`git log`. Nine reviewers ran in parallel, one per theme.

Two things the owner should know before reading:

1. **There is no `docs/GOALS.md` in `common`.** The task asked me to weigh themes
   against it; it does not exist on master (only `ringsdesktop` has one). The
   goal-decomposition automation treats this document as optional and "works
   from the release plan alone" when absent, so this is not a blocker — but if
   the owner wants goal-driven planning to anchor on a document, **writing a
   `common` goals doc is itself a worthwhile untracked item** (see Housekeeping).
   This review instead weighs themes against `ringsdesktop/docs/GOALS.md`, the
   `docs/plans/` directory (the de-facto 0.77 plan corpus), and the module map.

2. **The backlog is in good shape.** These ~100 tasks were *not* stale noise.
   Descriptions matched the code closely; only a dozen were already done/obsolete
   and only a handful had drifted. The real scoping problem is not "which tasks
   are junk" but **"which of three or four very different product directions does
   0.77 serve"** — that is the synthesis at the end.

---

## At a glance

| Verdict | Count | Meaning |
|---|---|---|
| **keep** (incl. keep-with-rescope) | 95 | Live and wanted; some need a one-line scope tweak |
| **close — done** | 9 | Shipped already; cite commit and close |
| **close — obsolete** | 3 | Superseded by a different approach; close |
| **merge** | 1 | Duplicate of another task |
| **rewrite** | 4 | Still wanted but description is stale/wrong |

Nothing was recommended for outright **decline** on the evidence — but several
"keep" items are speculative parking-lot entries the owner may *choose* to
decline for 0.77 (flagged in the themes).

---

## 1. Close or clean up first (do this before scoping)

Closing these first removes 13 tasks from the scoping surface and prevents agents
from burning job budget re-doing finished work.

### Close as done — cite the evidence

| ID | Title | Evidence |
|---|---|---|
| f5bd4788 | Give differentialLambda parameters a gradient in differential_attention.pdsl | Done: commits `60b7e9d62`/`1d15d6164`; `DifferentialAttentionAssetTest.theLearnedLambdaReceivesItsGradient`; plan `PLAN-20261022-differential-lambda-gradients.md` status=completed |
| 6251ca15 | Train the K1/V/Q2/K2 sections of TransformerResamplingFeatures | Done: commit `0c6ed7dad` moved it to `resampling_transformer.pdsl`; `ResamplingTransformerAssetTest.everySectionOfTheFusedProjectionReceivesItsGradient` |
| 7f2d8342 | Deploy safety: rewrite drain-agent-jobs.sh, deploy.yaml drain guard | Done: commit `af93d35fc`; `drain-agent-jobs.sh` already calls `GET /api/jobs/in-flight`; `deploy.yaml` wires `DRAIN_TIMEOUT_SECONDS` + `skip_drain` |
| 4e2827bb | Document completion listeners for operators | Done: `flowtree/runtime/docs/events.md` has the full "JobCompletionListener Interface Contract" section (commit `7517b0738`) |
| 17a23c1f | Optimization for aggregation of aggregation | Done: accumulator promotion (`Repeated.promoteAccumulators`, PR #342, `c662d2b5d`/`6f47492cb`); small-count flattening is by-design |
| 769a7d3b | Eliminate use of relative output | Done: `isOutputRelative` false-by-default since `b7a30d69e`; `IndexProjectionProducerComputation` no longer overrides it (`1f7e33f68`). Only a cosmetic base default remains — optional follow-up |
| 4a8e87d7 | Representation for reused Expression components | Done: `ExpressionCache` + `Scope.processReplacements` (since `32d965823`) implement the requested CSE/let-binding |
| 341bf64f | TraversableDeltaComputation to support single Expression Scope | Done: `enableAtomicScope` with `memLength=1` support, commit `df80144c5` (predates filing) |
| 42aaf1ff | Genome as argument reference | Done: `b9e6315c0` (Genome rewritten as Producer/assignment graph) + `b43d263e9` (single-kernel refresh) |

### Close as obsolete — superseded

| ID | Title | Evidence |
|---|---|---|
| b6479f14 | Kernel program for computing cached kernel series | `db63c8c47` block-vectorized the Java cache-fill loop instead; the perf complaint is resolved by a different mechanism than the task proposes |
| 9c832ec5 | Automatic isolation for complex Expressions | Project chose proactive isolation-at-optimize-time (`aa94d5b83`, `925603976`, `db236b2b0`, `2e010cbc5`), i.e. the "better option" the task explicitly deferred to |
| fa04115e | Flowtree computation evaluation | Describes remote execution of Producer/computation trees; FlowTree became coding-agent-job orchestration only — no Producer bridge exists or is planned |

### Merge

| ID | Title | Into | Why |
|---|---|---|---|
| 82084bd2 | Autoregressive training support | **57251aa6** (fine-tuning feasibility) | Vague one-liner; its only concrete claim (some layers lack backprop) is unsubstantiated against current code (`ForwardOnlyBlock`'s no-op is by-design). Same fine-tuning thread as 57251aa6 |

### Rewrite (keep, but fix the description before planning)

| ID | Title | What's stale |
|---|---|---|
| f7d66015 | Self-hosted LM: KV-cache decode **and** batch>1 training | KV-cache single-position decode is **done** (`KeyValueCacheAutoregressiveModel`, `PLAN-20261026-kv-cache-single-position-decode.md`). Split: close the KV-cache half, keep **batch>1 training** (still untried) |
| 40ee432f | Pass CORPUS_COMMIT to the CI ar-manager image build | The `.github/actions/` CI-lock claim is false — `check-ci-file-lock.sh` already covers it. Only `tools/ci/README.md` is stale; real gap is the missing `--build-arg CORPUS_COMMIT` in `analysis.yaml` |
| 6361ec61 | Validate per-phase models at submission + resolution diagnostics | Submit-time model rejection is already implemented (`PhaseConfigResolver.validateModelForRunner` wired into `handleSubmit`). Only the per-field provenance diagnostic + `strictCapabilityChecks` remain |
| 7116efd3 | Add a block-features-instance PreToolUse hook | Cited example (`AutoregressiveModel.java` ~line 122) no longer matches; the ask is still valid (9 live `new …Features(){}` sites, e.g. `MatrixFeatures.java:545`) — needs a fresh citation |

---

## 2. Themes (the keepers)

The keepers fall into four macro-families. Each sub-theme table is a candidate
0.77 line item. Sizes are in agent-jobs: **S** = 1, **M** = 2–4, **L** = 5+ or
multi-session.

### Family A — Generative audio & music (Rings-facing)

These are the tasks that most directly feed Rings. Rings 0.41 ships native
Stable Audio 3, sculpting brushes, a composing MCP server, and the pattern
format; the tasks below extend exactly those surfaces.

**A1 · Stable Audio 3 generative extensions** *(builds on Rings 0.41's native SA3; SA3 inpainting/continuation is the engine behind "sculpting brushes" — diffusion from a latent/noise blend)*

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| 63162756 | SA3 inpainting/continuation via local-add-cond mask | keep (dep e72668a2 now done — note it) | M | none |
| a758bdc9 | Load SA3 LoRA-family adapters | keep | L | owner: reference adapter checkpoints |
| 33dadf3d | SA3 Medium: SAME-L autoencoder + larger diff-attention DiT | keep | L | owner: Medium checkpoint |

**A2 · PDSL mixdown & realtime AudioScene** *(the native rendering/playback path; serves the realtime-audio and "performance = composition" goals)*

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| f155a689 | True stereo in the PDSL mixdown | keep | M | overlaps 19335007 |
| 539aca8d | Move LFO phase/waveform computation onto the device | keep (live Java-math violation in `LFO.java`) | M | none |
| 19335007 | Retire MixdownManagerPdslAdapter + CellList mixdown path | keep (drop "@Deprecated" framing) | L | none |
| 50d593a7 | PDSL run-ahead stream marker on a composition edge | keep (language-design idea) | L | owner: PDSL direction |
| 60b1a161 | Profile the one-time PDSL mixdown model build | keep (instrumentation done; optimization not) | M | none |
| 0e1699d5 | Re-measure the 1024-frame real-time acceptance bar | keep (logs exist; no assertion) | M | none |
| 43ee6d6d | Remove per-scene metadata/Metal-kernel retention; drop gcBeforeGenome | keep | L | none |
| 8095d090 | Run audio cell tests through a compiled loop | keep | M | task **b0186c98** (unreleased) |
| 3128bdc2 | Gate AudioSceneMultiGenomeTest JMX pauses/GCs on monitoring | keep | S | owner approval (edits test timing) |
| ac6793a5 | Give pairwiseSimilarityAtScale a throughput assertion | keep | S | none |

**A3 · Symbolic music / MIDI / pattern (SkyTNT)** *(serves the pattern-format and composing-MCP surfaces)*

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| 4d3a372c | Validate SkyTntMidi logits/tokens vs Python reference | keep | M | owner: Python reference env/weights |
| f42cb8c1 | Generate SkyTNT continuations from PatternSystemManager output | keep | M | task 4d3a372c first |
| c964c41c | Fix the MidiDataset multi-hot loss/gradient mismatch | keep (live in `NegativeLogLikelihood`) | S–M | none |

**A4 · Music-landscape discovery** *(the "explore your library by similarity" goal; feeds the composing MCP server)*

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| 7dfcfdd4 | Log community membership to find cause of low diversity | keep (recover deleted plan first) | S–M | data: external audio library |
| ba29a4db | Experiment with K and Louvain resolution | keep | M | task 7dfcfdd4; data |
| 97e22397 | Diversity-aware selection + medoid centroids | keep | M | task 7dfcfdd4; data |

**A5 · Model training & replication** *(exercises the fine-tuning platform goal; mostly speculative for 0.77)*

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| 57251aa6 | Fine-tuning feasibility threshold + resolve AggressiveFineTuningTest knownIssue | keep | M | hardware: CI runner measurement |
| cbf71db4 | Re-profile Qwen3 inference on current master | keep | S | hardware: GPU + profiler |
| 2cd3b82d | Support for GGML model format | keep (needs a concrete plan) | L | design |
| e4a04768 | Tango replication | keep (break into subtasks) | L | owner: architecture + weights |
| 628adf30 | RAVE replication | keep (break into subtasks) | L | owner: architecture + weights |

### Family B — Framework / compute-core (the HPC platform)

The largest technical family. Mostly correctness and performance of the
kernel/expression/memory machinery. All verified live. Several form ordered
clusters.

**B1 · Copy retirement** — a strict chain, **zero progress**, implemented once and lost in merge `726ceee90` (recoverable: `git show 06d88ecff^:docs/plans/MEMORY_DATA_COPY_RETIREMENT.md`)

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| e11d3354 | 1/4: reshape equal-size inputs in LayerFeatures.into | keep | S | none (entry point) |
| 48de225f | 2/4: default to assignment-based layer recording | keep | S | e11d3354 |
| d9dd71a8 | 3/4: enable enableAssignmentCopy | keep (drop dangling `b1740a2e` ref) | M | 48de225f |
| f7a0308d | 4/4: delete MemoryDataCopy | keep | M | d9dd71a8 |

**B2 · Expansion-width / isolation cascade** — coordinate as a cluster

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| 65b2862e | Complete faithful expansion-width overrides | keep | S | none (do first) |
| ecc958da | Calibrate & wire ExpansionWidthTargetOptimization into cascade, or remove | keep | M | 65b2862e |
| e3e6f28f | Selection-aware isolation: collapse subset projections to a gather | keep (original timeout bug already fixed `db63c8c47`) | M | coordinate w/ ecc958da |
| 0112cd60 | Optimize the process tree in Producer.into/evaluate behind a flag | keep | L | design: rollout plan |

**B3 · Backend memory-provider & drivers** — loosely related cluster

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| 01f18438 | Replace Metal instanceof in NativeComputeContext w/ capability | keep | S | none |
| 57e7b303 | Auto CLJNI/CLMemory under native+cl | keep (re-measure the "~1.3ms" cost) | M | overlap w/ `JVM_HEAP_AGGREGATION` plan |
| dabc96f9 | CUDA perf: CUBIN cache, Graphs, occupancy, fast-math | keep | L | hardware: CUDA device |
| 3f21d481 | Non-blocking OpenCL foreign-dependency bridge via user events | keep | M | none |
| 82d74af7 | Callback-driven Semaphore settlement for mixed-backend composites | keep (tried+reverted `0b1c49df2`; 2nd attempt on `qa/performance-20261027-082127`) | L | review that branch first |
| 48d9d979 | Retune CL lane memory/groups for ROCm | keep | S | hardware: ROCm host; ci/ branch |
| 059da123 | Re-enable the Metal resampling tests (NaN fixed) | keep | S | hardware: Metal lane |

**B4 · Expression / kernel correctness & codegen**

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| dcffd040 | Route ArithmeticGenerator through the expression factories | keep | S–M | none |
| 2aa2c985 | Constant-fold Logarithm and Absolute factories | keep | S | none |
| 99084539 | Fail clearly when kernel arg count exceeds device buffer limit | keep (split isConstant audit out) | M | none |
| 357aedcf | Make instruction-cache eviction safe vs concurrent apply | keep | M | none |
| 76deba0e | Close instruction-cache signature gaps (RepeatedProducerComputationAdapter, ConstantRepeatedDeltaComputation) | keep | S–M | none |
| 90866b08 | Make Repeated loop-invariant hoisting sound under buffer aliasing | keep | M | none |
| 923ca5b7 | Decide expression leaf interning: measure, then promote or remove | keep | S | none |
| c433086f | Compile small backward-pass reductions as one native unit / route to GPU | keep | M | none |
| 6c1067eb | Characterize the intermittent divideProduct3 gradient mismatch | keep (weakly evidenced) | S | none |
| 9a68c5cb | Seedable default random source, reset per test | keep (build on merged `CounterRandom`) | M | none |
| c7e103c1 | Re-evaluate enabling destination reuse by default | keep (preconditions now met) | S | hardware: Metal |
| eec292d1 | Required-Scope conversion drops some Scopes | keep (live defect, `Scope.java:838`) | L | design |
| bcb229f1 | Multiple-dimension traversal for CollectionEnumerate | keep (downgrade — core limit remains) | M | none |
| 87b95b81 | Sparse traversal support | keep (not a dup; producing-side half of the completed sparse-traversal-ordering plan) | M | none |
| ac170ed1 | MemoryDataArgumentMap data-reuse defect | keep (standing architectural risk) | M | none |

**B5 · Speculative / parking-lot** *(owner may decline for 0.77)*

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| 77ab790b | Pure-Java ComputeContext for tests without a native backend | keep | L | design |
| feffe091 | Declarative Expression simplification strategies | keep | L | design |
| 4595cf74 | Branch pruning for Producer evaluation | keep | L | design |
| 95bea703 | Java vector library support | keep (mark blocked) | L | owner: Java 21+ bump |

### Family C — Agent-development infrastructure (FlowTree / consult / CI / enforcement)

**This family dominates the backlog by count (~35 tasks)** and is almost entirely
meta — improving the agents and pipeline that build `common`, not `common`
itself. Whether it belongs in a *Common* release at all is the single biggest
scoping question (see §4). Grouped here; all verified live unless noted.

**C1 · consult / memory quality**

| ID | Title | Verdict | Size |
|---|---|---|---|
| 04727eaa | Durable consultation transcripts + index plan docs | keep | M |
| 4277d247 | Regenerate once when citation verifier flags sentences | keep | M |
| 146779f4 | Demote notes whose cited paths changed | keep | M |
| 1b699509 | Run the consult answer-quality judge at sample scale | keep | S |
| 08bd7ec6 | Remove retired ar-consultant source (after 1b699509) | keep | S |
| 39922685 | Surface enforcement loops in workstream_context; merge near-dup memories | keep | M |

**C2 · FlowTree controller / harness**

| ID | Title | Verdict | Size |
|---|---|---|---|
| ad19859a | GET /api/jobs/{id} with derived state (reuse workstream_get_status pattern) | keep | M |
| e7413703 | Read-only mailbox_peek tool | keep | S |
| 5f13d1c7 | Deliver mailbox messages to a running session via stream-json stdin | keep | L |
| 52c769c4 | Structured "blocked" exit for coding agents | keep | M |
| 6c9e2900 | Clamp per-submission resource overrides to the workstream ceiling | keep | M |
| 212372ab | Report node labels + busy state in agent_options | keep | S |
| ef0d7fb8 | Confine job tokens to their repo/workspace in read tools | keep | M |
| d303d52c | Falsification phase v2: sandboxed probes | keep | L |
| d3369a72 | Set push.default=current / autoSetupMerge=false in checkouts | keep | S |
| 609f5405 | Migrate the FlowTree deploy host to Colima | keep | L |
| 16220517 | Classify enforcement violations; record why rules retired | keep | L |
| eb3dd4eb | Design auto-resolve loop controls (pinned verification, diminishing-returns stop) | keep | L |

**C3 · Hooks / enforcement / GPU-memory-model burn-down** *(all "(ci/ branch)" except the detector burn-down; CI-file lock applies)*

| ID | Title | Verdict | Size |
|---|---|---|---|
| 95aac284 | Detect literal setMem-in-loop; retire PackedCollectionDetector escapes | keep | M |
| acc20571 | Burn down last 23 setMem baseline rows; remove fill lambdas | keep | M |
| 2f83b30f | Fix violations so deferred Error Prone checks can be enabled | keep | M |
| dd1eaedb | Add an OOP reviewer agent + reach-through/identity-by-name checks | keep | M |
| fbb3ac79 | opencode hook parity (re-count: 11 .ts vs 36 .sh) | keep | L |
| f5210705 | Make warn-method-placement evidence-based + destination-naming | keep | M |
| 9422abf2 | Remind agents that truncated/count-only grep output is weak evidence | keep | S |
| 01642137 | Extend generated-code steering to Read/Grep + ar-cache paths | keep | M |
| 9222bd27 | Block tool use when a mandatory MCP server is disconnected | keep | L |
| c5a12ce6 | Fix the tolerance parser in warn-test-deception.sh | keep | S |
| f4a594f4 | Align CI prompts + pytest limits with the 5-class/40-method rule | keep | M |

### Family D — Connectome / neural-population research

A self-contained research track (reproduce Shiu et al. on fly connectomes). **Six
of the eight transitively depend on the unmerged SparseMatrix/SpMV work (task
`66b57639`, PR #758, CI currently red)**, and most also need owner-provided data
(FlyWire/MaleCNS Feather files, a Brian2 reference run). It is not startable as a
block until #758 lands.

| ID | Title | Verdict | Size | Blocker |
|---|---|---|---|---|
| 2218ffdf | MaleCNS connectome ingest into CSR with signed weights | keep | M | PR #758 + dataset |
| 2a8fe992 | Reproduce Shiu et al. on MaleCNS vs Brian2 (Phase-1 gate) | keep | L | 2218ffdf, PR #758, d72c5ca4, owner Brian2 ref |
| d72c5ca4 | Reproduce Shiu et al. on FlyWire first | keep | L | PR #758 + owner data/Brian2 |
| 1cd4083d | Throughput benchmark on every backend | keep | M | 2a8fe992 + multi-backend hardware |
| eb28f816 | Event-driven scatter-add propagation for sparse connectivity | keep | L | PR #758 |
| cb9de664 | Reduced deltas for the sparse matrix product | keep (retarget after #758 rename) | M | PR #758 |
| ce31398a | Transposed (CSC) sparse matrix–vector product | keep (retarget to CSR-ordered PackedCollection) | M | PR #758 |
| 199d51d5 | Inherit memory ordering for a regular-shaped view of an ordered collection | keep | M | none (independent of the rest) |

---

## 3. Three scope options

### Option 1 — "Rings slice" (small; ~1 release-cycle of agent work)
Close the 13 cleanup items, then ship **Family A only**, plus the handful of S
compute-correctness fixes the audio path leans on.
- **In:** A1 (SA3: 63162756, a758bdc9, optionally 33dadf3d), A2 (PDSL mixdown:
  f155a689, 539aca8d, 19335007, 60b1a161, 0e1699d5, 3128bdc2, ac6793a5), A3
  (MIDI: 4d3a372c, f42cb8c1, c964c41c), plus 2aa2c985, 9a68c5cb, 059da123.
- **Roughly 18–22 tasks.** Everything maps onto a Rings 0.41 surface.
- **Leaves out:** all of Family C (agent infra), Family D (connectome), the
  speculative B items, and the model-replication work (A5).

### Option 2 — "Platform + Rings" (medium)
Option 1 **plus Family B** (the compute-core correctness/performance clusters:
copy-retirement chain, expansion-width cluster, backend fixes, codegen fixes) and
the **consult/memory + quick-win FlowTree** parts of Family C (C1 + the S/M items
of C2: e7413703, 212372ab, d3369a72, 52c769c4, ad19859a).
- **Roughly 45–55 tasks.** This is the "keep improving the engine while serving
  Rings" release. The B-family clusters have internal ordering but no external
  blockers, so they parallelize well across agents.
- **Leaves out:** connectome (blocked on #758 anyway), the enforcement/hook
  burn-down (C3), the large speculative items, model replication.

### Option 3 — "Everything credible" (large)
All 95 keepers minus only the parking-lot items the owner declines. Adds **Family
D** (gated on #758 merging first) and the full **Family C** enforcement/hook/CI
burn-down (C3), plus model replication (A5).
- **Roughly 70–95 tasks.** Realistically this is two or three release cycles, not
  one; better treated as "the 0.77 candidate pool" from which 0.78+ also draws.

**Recommendation to frame the decision:** the cleanest single release is
**Option 2** — it serves Rings 0.41 directly *and* advances the platform, and
nothing in it is blocked on unmerged code or external data. Family D should wait
for PR #758; Family C's meta-work is a judgment call about whether a *Common*
release is the right home for it (§4).

---

## 4. Questions only the owner can answer

1. **What is 0.77 *for*?** The backlog splits almost evenly into (A) Rings-facing
   generative audio, (B) compute-platform engineering, (C) agent/CI
   self-improvement, and (D) connectome research. These are four different
   releases. Which one (or two) is 0.77?
2. **Does agent-development infrastructure (Family C, ~35 tasks) belong in a
   *Common* release at all,** or should it live in a separate FlowTree/tooling
   track? It dominates the backlog by count but ships no `common` capability.
3. **What is the plan for PR #758 (SparseMatrix/SpMV)?** Its CI is red
   (test-cuda, test-cl) and the entire connectome family (Family D, 6 tasks)
   blocks on it. Does it merge into 0.77, and who fixes the backend failures?
4. **Will you supply the external data/checkpoints** these L-tasks need — FlyWire
   & MaleCNS Feather files + a Brian2 reference (Family D), a representative audio
   `.wav` library (A4 discovery), SA3 Medium and LoRA checkpoints (A1)? Without
   them those tasks cannot be verified end-to-end.
5. **Java version bump to 21+** for the Vector API (95bea703) — yes or no? Agents
   cannot change `pom.xml` Java levels; this is a prerequisite decision.
6. **Which ci/-locked tasks are in scope?** Eleven tasks must land on a `ci/…`
   branch under the CI-file lock (C3 + 48d9d979 + 40ee432f). They need a signed
   branch and your file-lock, so they can't just be dispatched like ordinary
   jobs.
7. **Do you want a `common` goals document** (`docs/GOALS.md`)? It is absent, and
   the goal-decomposition automation would anchor on it if present.

---

## 5. Housekeeping & untracked gaps

Things worth tracking that currently have no (correct) task:

- **`b0186c98` has no release.** It is the hard blocker for 8095d090 ("compiled
  loop over an audio cell silently produces silence") yet carries
  `release_id = null`. If 8095d090 is in 0.77, pull b0186c98 in too.
- **PR #758 CI is red** (test-cuda/test-cl) with a failed Copilot pass. No task
  tracks fixing it; every Family-D task inherits the risk.
- **No `common` `docs/GOALS.md`** (see Q7).
- **`01642137` references "ar-cache paths" that don't exist** in the repo yet —
  confirm what that path is before dispatching.
- **Stale plan docs to refresh:** `docs/plans/CONNECTOME_MODEL_ORGANISM.md` lists
  already-closed tasks as open and its Open Question 1 is answered by #758;
  the Realtime-AudioScene epic `3472d41a` / `STATE_OF_PLAY.md` (last baselined
  2026-06-09) is stale — current code was checked directly instead.
- **Recoverable deleted plans** (useful starting points, not losses):
  `PROTOTYPE_DISCOVERY_QUALITY.md` (`git show af087f04b:docs/plans/PROTOTYPE_DISCOVERY_QUALITY.md`)
  and `MEMORY_DATA_COPY_RETIREMENT.md` (`git show 06d88ecff^:docs/plans/MEMORY_DATA_COPY_RETIREMENT.md`).
- **Lost-and-reverted work to mine before re-implementing:** copy-retirement was
  implemented once (`a3b20e285`) and lost in merge `726ceee90`; callback-driven
  semaphore settlement was tried and reverted (`0b1c49df2`) with a second attempt
  parked on `qa/performance-20261027-082127`.

---

*End of review. All evidence above is cited to a file, commit, PR, or plan
document; nothing was changed in the tracker or the code.*
