---
status: in-progress
created: 2026-11-24
closed:
tracker: [2a8fe992-0352-45b4-8b82-0ca7dd097d70]
---

# Reproduce Shiu et al. on MaleCNS against a Brian2 reference — the connectome Phase 1 gate

**Tracker task:** `2a8fe992-0352-45b4-8b82-0ca7dd097d70` — *Reproduce Shiu et al. on
the MaleCNS connectome against a Brian2 reference (connectome Phase 1 gate)*.
**Release:** the release under development, whose name matches the version in the root
`pom.xml`.

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

## Outcome (first real run, 2026-11-24)

Run on the macOS/Metal host (Apple GPU, `Hardware[MTL]`, `AR_HARDWARE_MEMORY_SCALE=6`) with
`AR_LONG_TESTS=enabled`. **Status: the comparison half of the gate is met; the negative control is
not, and the reference model fails it too, so it needs an owner decision before this plan can
close.**

### Identities settled (scope item 1)

Checked against the converted `neurons.tsv` and the MaleCNS v1.0 annotation table
(`body-annotations-male-cns-v1.0-minconf-0.5.feather`):

- **MN9** — body 10331 (`MN9`, `cb_motor`, side L) and body 16949 (`MN9`, `cb_motor`, side R). These
  are the only two `MN9` bodies, so `MALE_CNS_MN9 = {10331, 16949}` and the
  `NAMED_NEURONS["male-cns"]` pair are correct. No change.
- **`"gustatory"`** is the annotation's `class` value for the gustatory sensory neurons (1,416
  neurons, types `LB*`, `LgAG*`, `LgLG*`, `PhG*`, `WG*`, `*_tpGRN`, `SNch05`, ...). Correct. No change.
- **Sugar-sensing GRNs: `LB3a, LB3b, LB3c, LB3d`, both sides (77 neurons).** Evidence: all four carry
  `flywireType = LB3` in the MaleCNS annotations (the FlyWire labellar bristle type), and they are,
  by a wide margin, the gustatory types with the strongest signed two-synapse input to MN9 (LB3d L
  1128, LB3c L 750, LB3c R 560, LB3a L 528 ... against at most 488 for any other gustatory type).
  Both sides are driven because MaleCNS has two MN9s and the FlyWire experiment's right-only choice
  reflects the right-side annotation available to Shiu et al., not the biology. `LB4b` (also
  `flywireType = LB3`) and the single `LB3` body are not driven; this remains open to the owner's
  ratification.

### Assets produced (scope item 2)

The converted connectome was already on the host (`male-cns/csr`, 164,506 neurons, 25,135,616 edges,
122,318,556 synapses, `min_count` 1). The Brian2 references were generated with Brian2 (its version is
recorded as `brian2_version` in each reference's manifest) and the authors' `model.py` unchanged (sha256 `fc45837d…`):

    brian2_reference.py experiment --repo <Drosophila_brain_model> \
        --connectome <models-derived>/male-cns/csr --name male-cns_<rate>Hz \
        --stimulate-types LB3a,LB3b,LB3c,LB3d --rate <100|150|200> --trials 30 \
        --processes 15 --output <dir>/male-cns_<rate>Hz

Each took about 4 minutes of simulation (6 minutes with model construction). The shared directory
`/Users/Shared/models-derived/male-cns` is not writable by the agent account, so the references were
written to `/Users/worker/models-derived/male-cns/brian2-reference` and the tests were pointed at it
with `AR_MALE_CNS_BRIAN2_REFERENCE`. **Owner action:** copy that directory to
`/Users/Shared/models-derived/male-cns/brian2-reference` so the default path works.

### Parameters

Shiu et al. defaults throughout, identical in both simulators: dt 0.1 ms, trial 1 s, 30 trials (30
replicas in one AR pass), `v_0 = v_rst = -52 mV`, `v_th = -45 mV`, `t_mbr = 20 ms`, `tau = 5 ms`,
refractory 2.2 ms (0 for Poisson targets), delay 1.8 ms, `w_syn = 0.275 mV`, Poisson kick
`f_poi * w_syn = 68.75 mV`. Sign: -1 for GABA/glutamate, +1 otherwise. Seed 0 on the AR side.

### Results (scope item 3)

Tolerance applied as currently encoded in `assertRatesAgree` (MN9 within 5%, median ≤ 3%, p90 ≤ 8%
over neurons above 10 Hz, top-50 overlap ≥ 48). It has **not** been ratified by the owner.

| Test | MN9 AR / Brian2 (Hz) | Neurons > 10 Hz | Median | p90 | Top-50 | AR wall | Result |
|---|---|---|---|---|---|---|---|
| `sugar100Hz` (FlyWire 630) | 66.33 / 66.53 | 233 | 0.0113 | 0.0277 | 49 | 370 s | pass |
| `sugar150Hz` (FlyWire 630) | 85.33 / 82.73 | 269 | 0.0127 | 0.0387 | 50 | 386 s | pass |
| `sugar200Hz` (FlyWire 630) | 94.17 / 92.67 | 296 | 0.0105 | 0.0422 | 49 | 370 s | pass |
| `nonGustatoryInputLeavesMn9Silent` | 0.0 | — | — | — | — | — | pass |
| `maleCns100Hz` | L 145.60 / 143.73, R 31.60 / 30.70 | 13,910 | 0.0107 | 0.0367 | 49 | 536 s | pass |
| `maleCns150Hz` | L 154.77 / 156.10, R 37.47 / 37.93 | 13,789 | 0.0062 | 0.0248 | 48 | 541 s | pass |
| `maleCns200Hz` | L 159.40 / 159.37, R 40.27 / 40.53 | 13,797 | 0.0036 | 0.0209 | 49 | 528 s | pass |
| `maleCnsNonGustatoryInputLeavesMn9Silent` | L 3.77 (allowed 0 ± 0.5) | — | — | — | — | 538 s | **fail** |

So sugar GRN input drives MN9 on MaleCNS (L about 150 Hz, R about 35 Hz) and the AR rates match
Brian2's within the encoded tolerance at all three rates, on 13.8k neurons. On FlyWire the same holds
on the paper's 21 sugar GRNs, so the simulation is confirmed on both connectomes.

### The negative control (success criterion 2) — fails in Brian2 as well

The control drives 77 neurons spread evenly over the index range, skipping the gustatory class, at
150 Hz. On MaleCNS the authors' model in Brian2, given exactly that input, also fires MN9: **1.17 Hz,
and 1.50 Hz in an independent repeat** (left MN9; the right is 0.0 and 0.07). With the one premotor
neuron in the set (`GNG103`, body 162461, 37 synapses two steps from MN9) left out, Brian2 gives
6.97 / 1.33 Hz, so that neuron is not the cause.

The reason is a regime the FlyWire model does not reach: on MaleCNS, any 77-neuron input at 150 Hz
ignites widespread activity (about 13k neurons above 10 Hz, the mushroom-body APL/DPM/MBONs and
ExR6 near 200–380 Hz), starting 60–300 ms into the trial. Under sugar input it starts within
30–90 ms and is steady (1.10M ± 0.04M spikes per trial). Under the control it varies widely from
trial to trial (0.57M ± 0.40M). MN9 still separates sugar from control by a factor of 20–100, but it
is not silent. This is the model on a denser connectome (743 synapses per neuron against 414 in
FlyWire 630, at the same `w_syn` and with every 1-synapse edge kept), not an AR defect:

- Run against the Brian2 control as a reference, AR's control rates differ by median 0.209, p90
  0.215, top-50 overlap 34. Two independent Brian2 control runs differ from each other by median
  0.244, p90 0.250, top-50 overlap 31. AR is inside Brian2's own spread. The near-uniform ~20% gap
  is the trial-to-trial variation in how strong the widespread activity gets, shared by every neuron.
- The sign rule's MaleCNS-only edge cases were checked and do not drive it. MaleCNS labels 7,883
  neurons histaminergic, which the rule makes excitatory, but none of them is among the active
  neurons. `unclear` (2,469 neurons) carries 5.5% of the synaptic drive.

**Decision needed from the owner** (follow-up task `130005e1-0dad-45d6-ae16-2d050fcf4f99`): what success criterion 2
means on MaleCNS. The options are to restate it as selectivity (for example control MN9 ≤ 5% of the
sugar MN9 rate, which both simulators meet), or to change the MaleCNS adaptation (a minimum synapse
count per edge, a MaleCNS-specific `w_syn`, or restricting the bodies) so the model leaves the
widespread-activity regime. Both are scientific choices that have to be made before the comparison
is rerun, not tuned by the agent. Until then `maleCnsNonGustatoryInputLeavesMn9Silent` stays as
written and fails on this host.

### CI guard (success criterion 4)

`ConnectomeNetworkTest` in the pipeline profile: 8 tests run, 0 failures, 0 skipped (synthetic
connectome and FlyWire sugar circuit against their committed Brian2 references). No production code
changed in this run, so the guard is unaffected.

## Estimated complexity

**Medium.** The engine, the ingest, the simulation driver, the reference generator and the test
harness are all already merged, so there is little new code — the likely code changes are
correcting a few verified identities and whatever one real run surfaces. The weight of the task is
the validation campaign: it depends on owner-provided assets (a multi-gigabyte dataset, a Brian2
reference run) and on a scientific match that may need one or two iterations, on a specific host
class (macOS/Metal), and it ends in a written-up, reviewer-checkable result.
