---
status: proposed
created: 2026-11-02
closed:
tracker: [e77c0936-b2f9-4534-85f0-c7d2bc831182]
---

# Document the neuron-level connectome simulation pipeline

## Category

Documentation

## Motivation

In the last two days the platform gained its most distinctive scientific-computing
capability to date: it can load a real fruit-fly brain circuit as a signed compressed
sparse-row connectome and run a conductance-based spiking simulation over it that
reproduces, spike for spike, a published whole-brain-model experiment — validated against
a Brian2 reference committed beside the test. The subsystem that does this landed with
PRs #806 and #809:

- `org.almostrealism.bioscience.connectome.NeuronConnectome` — a signed synapse-count
  matrix in compressed sparse rows plus the body id and annotations of every neuron, read
  through `StateDictionary`, whose `[N, N]` weight collection carries a
  `CompressedRowTraversalOrdering` so `matmul(cp(getWeights()), spikes)` is a sparse product.
- `org.almostrealism.bioscience.connectome.ConnectomeNetwork` — a spiking network over a
  `NeuronConnectome`: every neuron an `ExponentialSynapseNeurons` neuron, every synapse a
  delayed, weighted connection, selected neurons driven by `PoissonInput`, composed one
  step at a time in `/pdsl/connectome.pdsl` and stepped by `SimulationRunner`. With its
  default constants it is the whole-brain model of Shiu et al. (2024). It supports several
  independent replicas stepped together as one batched sparse product, and
  `meanRates(...)` runs a number of trials and returns each neuron's firing rate.
- `org.almostrealism.biology.neuron.ExponentialSynapseNeurons` — leaky integrate-and-fire
  neurons whose synaptic potential decays exponentially, stepped by the exact solution of
  the coupled membrane and synapse equations in Brian2's time-step order, with a per-neuron
  refractory period; the neuron of the Shiu et al. model.
- `org.almostrealism.biology.neuron.PoissonInput` — independent, reproducible Poisson spike
  trains to a selected set of neurons, drawn from a counter-based generator indexed by the
  simulation's step counter.
- The ingest tooling in `studio/bioscience/scripts/`: `connectome_csr.py` (reduces MaleCNS,
  the FlyWire snapshots of Shiu et al., and a small synthetic dataset to one CSR safetensors
  form), `flywire.py`, `male_cns.py`, and `brian2_reference.py` (the Brian2 cross-validation
  oracle), with the synthetic connectome and the FlyWire "sugar circuit" committed as test
  resources together with their Brian2 reference spikes.

This is exactly the proof-of-value capability the self-understanding arc exists to produce
on the science side: the platform describing a living system at the resolution of single
neurons, and getting the same answer a dedicated neuroscience simulator gets. But **the only
prose that explains it is the two module READMEs and the classes' own Javadoc.** There is no
connective internals page that walks the whole pipeline — from a connectome dataset, through
`connectome_csr.py`, to a `NeuronConnectome`, to a `ConnectomeNetwork` step composed in PDSL,
to a `SimulationRunner` run whose spikes match Brian2. The one existing simulation page,
`docs/internals/neural-population-simulation.md`, is about the *aggregate* path — LIF and
rate **populations**, where a single collection stands for a whole population's state — which
is a different, coarser model than the neuron-level connectome. And `llms.txt` still lists
only the older `CellTypeConnectome` loader for the bioscience module, so a reader (human or a
model trained on the corpus) following the index never discovers the neuron-level subsystem
at all.

This is the project's recurring and well-understood documentation situation: the reference
material exists, the connective material does not. It is the same move the 2026-10-27 cycle
made for the population path, applied now to the newer, higher-fidelity neuron-level path
that reproduces a published result. Documenting it now — while the subsystem is fresh and
before the MaleCNS whole-brain reproduction (open for Common 0.77) builds further on it — is
far cheaper than reconstructing the pipeline from source later, and it is what lets a model
learning from the corpus reason about the most distinctive thing the platform can now do.

## Scope

Concrete deliverables:

1. **A new internals page, `docs/internals/neuron-level-connectome-simulation.md`**, that
   walks the pipeline end to end, in the register of the existing internals corpus (prose
   with short, real code/PDSL excerpts; stable identifiers, no source line numbers per
   `docs/CLAUDE.md`). It must cover, at minimum:
   - **What the model is and where each part lives** — the three-module split
     (`compute/algebra` for the CSR ordering and sparse product, `domain/biology` for the
     neuron and Poisson-input `Block`s, `studio/bioscience` for the connectome loader,
     network, PDSL step and ingest scripts) and why (the "what does not belong here" rule in
     the biology README).
   - **The CSR connectome representation** — what `connectome_csr.py` produces
     (`row_offsets`, `presynaptic`, `weights`, `signs` in one safetensors file, plus the
     neuron annotation table), rows as postsynaptic neurons, the sign/synapse-count
     encoding, and how `NeuronConnectome.getWeights()` carries a
     `CompressedRowTraversalOrdering` so the summed signed synaptic input is a single sparse
     `matmul` — linking back to `shape-and-traversal.md` and the sparse-ordering machinery
     rather than re-deriving it.
   - **One connectome step** — the `connectome_step` layer of `/pdsl/connectome.pdsl`: how
     `PoissonInput` adds kicks to the drive, how the delay line is read one step behind and
     written this step, how the delayed spikes become synaptic potential through the signed
     sparse product scaled by `SYNAPSE_WEIGHT`, how `ExponentialSynapseNeurons` takes
     membrane drive and synaptic input and emits spikes, and how two Java `Block`s are placed
     in a PDSL pipe by naming them.
   - **The neuron model** — the exact-solution exponential-synapse LIF step, the Brian2
     time-step order it follows, the refractory period, and the model constants
     (`ConnectomeNetwork`'s `DT`, potentials, time constants, `DELAY`, `SYNAPSE_WEIGHT`) with
     their units (ms, mV, Hz).
   - **Running it** — `ConnectomeNetwork` as a `Temporal` stepped by `SimulationRunner`
     (owner of the loop, cross-linking `simulation-loop-examples.md`), `Setup` returning
     neurons to rest and emptying the delay line so each run is a fresh trial, replicas as
     batched trials stepped together as one sparse product, and `meanRates(...)`.
   - **Cross-validation against Brian2** — what `brian2_reference.py` computes, how
     `ConnectomeNetworkTest` asserts the platform's spikes match the reference on the
     synthetic connectome and the FlyWire sugar circuit, and what "matches" means here
     (exact spike agreement under a fixed kick schedule).
   - **Known limits** — stated from evidence (e.g. neuron and Poisson dynamics are Java
     types because PDSL cannot yet express their comparisons and persistent state; the
     MaleCNS whole-brain reproduction and its performance benchmark are open tracker work for
     Common 0.77; any host-resource requirements for the larger curated datasets, reported as
     requirements, never gated into skips).
   - A **Related files** table (module-relative paths, no line numbers).

2. **A drift-guard examples test**, `NeuronLevelConnectomeSimulationExamplesTest` (in
   `engine/utils`, extending `TestSuiteBase`, modeled on
   `NeuralPopulationSimulationExamplesTest`), that runs the page's worked example on the
   **committed synthetic connectome** (40 neurons, 111 edges) through `SimulationRunner` and
   asserts the quantitative claims the page makes (e.g. a driven neuron fires, the synthetic
   connectome's spikes match its committed Brian2 reference, a reset run reproduces, replicas
   agree). It must run on **any backend** — the synthetic connectome and its Brian2 reference
   ship as test resources, so no curated download and no macOS/Metal node is required. Every
   PDSL/code excerpt the page presents as complete must be exercised by this test so the page
   cannot silently rot. The test must not reduce any dimension, loosen any tolerance, or gate
   a skip on an environment probe; if a documented claim cannot be asserted on an arbitrary
   backend, the page states it as a limit instead of the test hiding it.

3. **Index and cross-link refresh:**
   - `llms.txt`: update the `bioscience` line to name the neuron-level subsystem
     (`NeuronConnectome`, `ConnectomeNetwork`) and the dataset conversion scripts alongside
     the existing `CellTypeConnectome`, and add the new internals page to the internals
     listing.
   - `docs/internals/neural-population-simulation.md`: a short "See also" / disambiguation
     pointer distinguishing the aggregate population path from the neuron-level connectome
     path and linking the new page.
   - `domain/biology/README.md` and `studio/bioscience/README.md`: link the new page from
     their "how dynamics are expressed and stepped" / contents sections.
   - The internals index (`docs/index.html` internals listing, if it enumerates pages).

Out of scope (recorded as follow-ups, not done here): any change to the connectome code, the
PDSL step, the neuron model, or the ingest scripts; the MaleCNS whole-brain reproduction and
its benchmark (already open tracker tasks for Common 0.77); any new PDSL primitive to replace
the Java neuron/Poisson `Block`s. If the page-writing surfaces a genuine defect or rough edge,
it is recorded as an evidenced tracker task, not fixed on this branch — this is a
documentation cycle.

## Approach

1. **Study the subsystem as a whole before writing** (per the "STUDY X is a full
   investigation" rule): read `NeuronConnectome`, `ConnectomeNetwork`, `ExponentialSynapseNeurons`,
   `PoissonInput`, `/pdsl/connectome.pdsl`, the three `*Test` classes
   (`ConnectomeNetworkTest`, `NeuronConnectomeTest`, `ExponentialSynapseNeuronsTest`) and the
   `connectome_csr.py` / `brian2_reference.py` scripts and their committed synthetic
   resources. Confirm every claim the page will make against source and the tests. Use
   `consult` and `ar-profile-analyzer` only as needed to confirm how the sparse step
   compiles; do not theorize about mechanism.
2. **Write the page** following `neural-population-simulation.md` as the structural model —
   it is the sibling page for the aggregate path and sets the house style for a simulation
   walkthrough (state → step → delay → connectivity → drive → runner → limits).
3. **Write the drift-guard test** on the synthetic connectome, asserting only robust,
   backend-independent properties, and run it narrowly (`test_classes:
   ["NeuronLevelConnectomeSimulationExamplesTest"]`).
4. **Refresh the index and cross-links.**
5. **Validate**: `mcp__ar-build-validator__start_validation` clean (checkstyle, code_policy,
   test_timeouts, duplicate_code); `tools/tests/test_sweep_plans.py` passes (valid plan
   header); the new examples test passes narrowly. Confirm no durable file references any
   `docs/plans/` document (per `docs/plans/CLAUDE.md`).

## Success Criteria

- `docs/internals/neuron-level-connectome-simulation.md` exists and walks the full pipeline
  with accurate, stable-identifier references and no source line numbers.
- `NeuronLevelConnectomeSimulationExamplesTest` exists, runs on any backend against the
  committed synthetic connectome, exercises every complete excerpt the page shows, and
  passes — asserting robust properties without reducing dimensions, loosening tolerances, or
  gating skips on environment probes.
- `llms.txt`, `neural-population-simulation.md`, and the biology/bioscience READMEs cross-link
  the new page and name the neuron-level subsystem.
- Build validator clean; plan-header sweep passes; no durable reference into `docs/plans/`.
- `consult`, re-run after the page lands, finds the neuron-level connectome pipeline
  documented.

## Dependencies

- None external. The subsystem (PRs #806, #809) is merged to `master`, and the synthetic
  connectome plus its Brian2 reference ship as committed test resources, so the work and its
  drift-guard run on **any backend** — no owner-provided data, no download, no macOS/Metal
  node. No workstream settings file accompanies this plan.
- Builds on the already-documented aggregate path (`neural-population-simulation.md`,
  `simulation-loop-examples.md`) and the sparse-ordering machinery
  (`shape-and-traversal.md`), which this page links rather than restates.

## Estimated Complexity

Medium. The depth is in reading the whole subsystem accurately and writing one well-sourced
page plus a disciplined drift-guard test; the surface area (one new page, one new test, four
cross-link edits) is bounded and touches no production code.

## What comes next

- **Proof of value, connectome:** the MaleCNS whole-brain ingest and the Shiu et al.
  reproduction gate (open for Common 0.77), now with the pipeline it builds on documented.
- **Proof of value, self-hosted LM:** a sliding (rolling, position-remapped) key/value cache
  for long-form generation past `seqLen`, if the decode-throughput cap binds now that the
  cache path is faster than the sliding window at every length.
- **Proof of value:** conditional diffusion training.
- **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider
  than 1000) and a reduced form for the convolution input gradient.
- Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2; the
  `io.almostrealism.collect` package split; the unbuilt PDSL research/education features;
  letting a trained model read the platform's own *source*, the deepest self-understanding aim.
