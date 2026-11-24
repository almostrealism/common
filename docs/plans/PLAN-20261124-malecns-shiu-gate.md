---
status: proposed
created: 2026-11-24
closed:
tracker: [2a8fe992-0352-45b4-8b82-0ca7dd097d70]
---

# Reproduce Shiu et al. on MaleCNS against a Brian2 reference — the connectome Phase 1 gate

**Tracker task:** `2a8fe992-0352-45b4-8b82-0ca7dd097d70` — *Reproduce Shiu et al. on
the MaleCNS connectome against a Brian2 reference (connectome Phase 1 gate)*.
**Release:** Common 0.78 (the release this repository's master is building).

This is Phase 1 task 11, the **gate**, of the connectome model organism design
(`docs/plans/CONNECTOME_MODEL_ORGANISM.md`): *"the neuron-level MaleCNS model runs
on the AR framework, and it reproduces the Shiu et al. result"*.

## Motivation

The headline result of Shiu et al. (Nature 2024, *"A Drosophila computational brain
model reveals sensorimotor processing"*) is that, in a leaky integrate-and-fire model of
the whole connectome driven only by synapse-count weights and neurotransmitter-predicted
signs, Poisson stimulation of the sugar-sensing gustatory receptor neurons drives the
proboscis-extension motor neuron MN9. Reproducing that result on the AR framework, for the
whole MaleCNS connectome, and showing the firing rates agree with an independent Brian2 run
of the same model on the same connectome, is the proof that the platform can run a real
connectome correctly end to end. MaleCNS matters specifically because, unlike the FlyWire
whole-brain connectome, it includes the ventral nerve cord and therefore reaches motor
neurons such as MN9.

A null result for the gate is not acceptable the way it is for the research question: the
whole point of this task is a *positive* match against the reference within a tolerance
agreed in advance. Spiking simulations diverge spike by spike even between two runs of one
simulator, so the comparison is on trial-averaged firing rates, not spike timing.

## Current state — what the blocking tasks already delivered

Every blocking task of this gate has closed and merged, and between them they already built
the entire pipeline this gate exercises. This task does **not** build that pipeline; it
drives it on the real MaleCNS dataset, confirms the match, and records the result. The
relevant, already-merged pieces:

- **Sparse connectivity** — the segmented-reduction sparse product
  (`CompressedRowTraversalOrdering` + `SegmentedWeightedSumComputation`, `compute/algebra`).
- **Spiking dynamics** — the leaky integrate-and-fire population. The model
  used by the connectome is `ExponentialSynapseNeurons` (with `PoissonInput`) in `ar-biology`
  (`org.almostrealism.biology.neuron`).
- **Simulation loop owner** — `SimulationRunner` (`compute/time`,
  `org.almostrealism.time`), which owns the step loop; recording goes through it, not host
  reads per step.
- **Connectome ingest** — the neuron-level CSR ingest:
  `studio/bioscience/scripts/connectome_csr.py --dataset male-cns` (with `male_cns.py` for the
  MaleCNS tables and sign rule) converts the Feather release to integer safetensors CSR, and
  `NeuronConnectome` (`studio/bioscience`, `org.almostrealism.bioscience.connectome`) loads it
  with per-neuron body id, type, class, transmitter and side, plus `indexOf`, `indicesOf`,
  `getNeuronClass`, `getBodyId` and `mask`.
- **FlyWire narrower gate** — the simulation driver and the comparison
  harness: `ConnectomeNetwork` (`studio/bioscience`) runs the Shiu et al. model with the
  model's constants, Poisson drive, replicas (trials run at once) and `meanRates`; the Brian2
  reference generator `brian2_reference.py` gained `experiment --name male-cns_<rate>Hz`, which
  runs the authors' own `model.run_exp` unchanged on any converted connectome; and
  `ConnectomeNetworkDatasetTest` gained the MaleCNS comparison methods and negative control.
- **CI guard** — `ConnectomeNetworkTest` runs the same network on the committed synthetic
  connectome and the committed FlyWire 630 sugar circuit against committed Brian2 references,
  in the pipeline profile, needing no external dataset.

What is **not** yet done, and is the substance of this task:

1. No MaleCNS full-dataset run has been performed or confirmed. The MaleCNS comparison tests
   (`ConnectomeNetworkDatasetTest.maleCns100Hz / maleCns150Hz / maleCns200Hz` and
   `maleCnsNonGustatoryInputLeavesMn9Silent`) are excluded from the pipeline profile and require
   the converted connectome and Brian2 references, which live outside the repository under
   `/Users/Shared/models-derived/male-cns/` and must be produced on a host that has the data.
2. The MaleCNS **sugar-sensing GRN identity** is undecided. On FlyWire the 21 sugar GRNs are
   fixed root ids; on MaleCNS the stimulated set is whatever `brian2_reference.py experiment
   --stimulate-types ...` was told to drive, recorded in the reference manifest and read back by
   the test. Which MaleCNS cell types are the sugar GRNs has to be settled and recorded ("LB3a–d"
   was flagged as a pending owner decision in the cell-type rate model work).
3. The hardcoded MaleCNS identities are **unverified** against neuPrint `male-cns:v1.0`: the two
   MN9 body ids (`ConnectomeNetworkDatasetTest.MALE_CNS_MN9 = {10331, 16949}`, also the
   `NAMED_NEURONS["male-cns"]` spot-check pair in `connectome_csr.py`) and the `"gustatory"`
   class string the negative control filters on.
4. The result, parameters and tolerance are not yet written up (this task requires them written
   up in this plan).

The internals page `docs/internals/neuron-level-connectome-simulation.md` already states (section
7) that the full whole-brain reproduction is "still open tracker work" — that open item is this
task.

## Scope

Concretely, carrying out this task will:

1. **Settle and record the MaleCNS stimulus and readout identities.** Choose the MaleCNS
   sugar-sensing GRN cell type(s) for `--stimulate-types` (verify against neuPrint `male-cns:v1.0`
   and the Shiu et al. / MaleCNS annotation vocabulary), verify the two MN9 body ids, and verify
   the class string the negative control uses to find gustatory neurons. Where the real data
   disagrees with a currently-hardcoded constant, correct it at its single call site:
   `MALE_CNS_MN9` and the `"gustatory"` filter in `ConnectomeNetworkDatasetTest`, and the
   `NAMED_NEURONS["male-cns"]` pair in `connectome_csr.py`. No new constants scattered elsewhere.
2. **Produce the MaleCNS assets** (owner-run on the host that holds the data; see Dependencies):
   convert the release with `connectome_csr.py --dataset male-cns`, and generate the Brian2
   references with `brian2_reference.py experiment --name male-cns_<rate>Hz --stimulate-types
   <sugar GRNs>` at 100, 150 and 200 Hz, storing them under the directories the test expects
   (`AR_MALE_CNS_CSR`, `AR_MALE_CNS_BRIAN2_REFERENCE`, defaulting under
   `/Users/Shared/models-derived/male-cns/`).
3. **Run the gate** on a macOS/Metal host with the data mounted: `maleCns100Hz`,
   `maleCns150Hz`, `maleCns200Hz` (MN9 and the recorded neurons vs the Brian2 reference), and
   `maleCnsNonGustatoryInputLeavesMn9Silent` (the negative control). De-risk first by running the
   FlyWire references (`sugar100Hz / sugar150Hz / sugar200Hz`,
   `nonGustatoryInputLeavesMn9Silent`), since a FlyWire mismatch is unambiguously the AR
   simulation while a MaleCNS-only mismatch points at the adapter.
4. **Fix whatever the first real run surfaces** inside the existing classes/scripts, honouring
   the Producer rule (`CodePolicyViolationDetector`) — all computation stays in Producer graphs,
   recording goes through `SimulationRunner`. Plausible surfaces: an edge-filtering or
   minimum-synapse-count mismatch in `connectome_csr.py`/`male_cns.py`, a sign-rule edge case (how
   non-GABA/glutamate and missing predictions are handled), or a manifest field the test reads.
5. **Record the outcome** in this plan: the MN9 rate against the reference at each rate, the
   median and 90th-percentile relative error over neurons above 10 Hz, the top-50 overlap, and
   the negative-control MN9 rate; the full parameter set; and the ratified tolerance. Keep the
   always-on CI guard (`ConnectomeNetworkTest`) green.

Out of scope (handled elsewhere or by follow-ups): the throughput benchmark on every backend
(separate task `1cd4083d`, blocked by this gate); the trainable rate-based model and
backpropagation through time (Phase 3); the full FlyWire 783 reproduction and any new neuron
models.

## Approach

The work lives entirely in files that already exist. No new Maven module, no new class unless a
genuinely new concept appears (none is expected), no `pom.xml` change.

- **Stimulus / readout identity and any fixes** — `ConnectomeNetworkDatasetTest` (the
  `MALE_CNS_MN9` constant, the `maleCnsNonGustatoryInputLeavesMn9Silent` class filter, and the
  manifest-driven `assertMatchesBrian2` / `assertRatesAgree` tolerance), and
  `NeuronConnectomeChecks` (the shared dataset accessors). The stimulated sugar-GRN set is not a
  Java constant: it is encoded in the reference manifest produced by `brian2_reference.py
  experiment`, and `assertMatchesBrian2` already verifies the manifest's `stimulated` body ids
  equal the connectome's at the manifest's `stimulated_indices`.
- **Reference generation** — `studio/bioscience/scripts/brian2_reference.py` (`experiment`
  subcommand, `--stimulate-types`/`--stimulate-ids`/`--stimulate-sides`), `connectome_csr.py`
  (`--dataset male-cns`), `male_cns.py` (MaleCNS table columns, the typed-body filter, the sign
  rule). These are the only places the biology-specific choices belong.
- **The run itself** — `ConnectomeNetwork.meanRates(trials, steps, seed)` drives the trials as
  replicas and returns per-neuron mean rates; `SimulationRunner` owns the step loop. The gate test
  keeps the reference `StateDictionary` alive for the network's lifetime (the drive and CSR
  schedule are views over its native tensors — a known gotcha from the FlyWire work).
- **Documentation** — the result/parameters/tolerance go in this plan's Outcome section. If any
  durable fact changes (e.g. a corrected constant or a biology note), update the durable page
  `docs/internals/neuron-level-connectome-simulation.md` rather than pointing durable docs at this
  plan.

## Success criteria

A reviewer confirms the gate is met when, on a macOS/Metal host with the MaleCNS data and Brian2
references in place:

1. `ConnectomeNetworkDatasetTest.maleCns100Hz`, `maleCns150Hz` and `maleCns200Hz` pass: at each
   rate, both MN9s are within the MN9 tolerance of the Brian2 reference, the median relative
   difference over neurons above 10 Hz is within the median tolerance, the 90th percentile within
   the p90 tolerance, and at least 48 of the 50 fastest neurons are shared with the reference —
   the tolerance currently encoded in `assertRatesAgree` (MN9 5%, median 3%, p90 8%, top-50
   overlap ≥ 48), ratified (or adjusted) **before** the comparison per the task's rule.
2. `maleCnsNonGustatoryInputLeavesMn9Silent` passes: the same input to as many non-gustatory
   neurons spread over the connectome leaves both MN9s silent (≈ 0 Hz).
3. The equivalent FlyWire comparisons (`sugar100/150/200Hz`, `nonGustatoryInputLeavesMn9Silent`)
   also pass, establishing that a MaleCNS-specific failure would be the adapter, not the
   simulation.
4. `ConnectomeNetworkTest` (synthetic + sugar circuit) still passes in the pipeline profile, so
   the gate stays guarded in CI without the dataset.
5. This plan's Outcome section records the measured MN9 rates, the median/p90 relative errors, the
   top-50 overlap, the negative-control rate, the full parameter set and the ratified tolerance.

## Dependencies and open questions

**Needs from the project owner (the task names these):**

- The MaleCNS v1.0 flat-connectome Feather tables downloaded to the host (per
  `CONNECTOME_MODEL_ORGANISM.md` → "Assets"), and a **macOS/Metal host** with that data mounted on
  which the full run and the Brian2 reference run happen. Hence the `requiredLabels: platform:
  macos` settings file beside this plan: the dataset lives on the shared Mac model directory and a
  whole-connectome spiking run needs the Metal backend to finish in reasonable wall-clock time.
- A **Brian2 reference run** of the Shiu et al. model on MaleCNS at 100/150/200 Hz, produced with
  `brian2_reference.py experiment`, with parameters, edge filtering and signs identical to the AR
  run. If agents cannot install `brian2`, the owner produces the references.
- **Agreement on the tolerance before the comparison is run.** The proposed tolerance is the one
  already encoded for FlyWire: MN9 within 5%, median relative error ≤ 3% and 90th percentile ≤ 8%
  over neurons above 10 Hz, and ≥ 48 of the 50 fastest neurons shared. Open question: is this the
  right tolerance for MaleCNS, which has more neurons and includes the VNC and motor neurons the
  FlyWire model lacks? It may need loosening or a per-rate statement; this must be fixed in advance.

**Open questions:**

1. **Which MaleCNS cell types are the sugar-sensing GRNs?** "LB3a–d" was flagged as a pending
   owner decision in the cell-type rate model work; it must be confirmed against the MaleCNS
   annotation vocabulary and neuPrint before generating the references, and whether to restrict by
   side (`--stimulate-sides`).
2. **Are `MALE_CNS_MN9 = {10331, 16949}` the correct MN9 body ids in MaleCNS v1.0**, and is
   `"gustatory"` the class string the negative control should filter on? Both are currently
   hardcoded placeholders unverified against real data.
3. **Has the FlyWire full gate itself been confirmed on real data?** The FlyWire simulation driver
   and comparison harness closed on the harness and the synthetic/sugar-circuit CI guard; the full
   FlyWire reference run (the `sugar100/150/200Hz` methods in `ConnectomeNetworkDatasetTest`) is in
   the same gated test class and appears not to have been confirmed either. Running it is the
   de-risking step for this gate and should happen first.

If the first real MaleCNS run surfaces a defect that is clearly outside this gate (for example a
deficiency in the sparse product or the ingest that also affects FlyWire), file it as a follow-up
task with `tracker_create_task` rather than widening this one, and note it here.

## Estimated complexity

**Medium.** The engine, the ingest, the simulation driver, the reference generator and the test
harness are all already merged, so there is little new code — the likely code changes are
correcting a few verified identities and whatever one real run surfaces. The weight of the task is
the validation campaign: it depends on owner-provided assets (a multi-gigabyte dataset, a Brian2
reference run) and on a scientific match that may need one or two iterations, on a specific host
class (macOS/Metal), and it ends in a written-up, reviewer-checkable result.
