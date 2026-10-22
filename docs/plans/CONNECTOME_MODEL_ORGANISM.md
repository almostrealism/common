# A Connectome Model Organism for Brain–Computer Interface Research

**Status:** Proposal. Nothing here is implemented. The tracker tasks for Phase 1 are filed in the
Common project (see "Phase 1 tasks"); later phases are described but not yet filed.

## Goal

Build, on this platform, a **model organism of a brain–computer interface**:

- a simulation of the complete *Drosophila* male central nervous system, constrained by the
  MaleCNS connectome;
- a small artificial network, written in PDSL, that reads from a chosen set of simulated neurons
  and writes stimulation back into another set;
- a simulated body and environment that give the combined system a task to perform.

The research question is how much a bandwidth-limited artificial network, coupled to a biological
circuit at fixed sites, can improve that circuit's performance, and what that suggests about
intelligence augmentation in the coming years. A null or small effect is a valid result. The
deliverable is a set of curves (improvement against channel count, interface site, network size and
training regime), not a yes/no answer.

The immediate goal of this document is narrower: **run the connectome on the AR framework**. That
is Phase 1, and it is mostly general capability work (sparse representations, integer indexing,
recurrent simulation) that benefits the platform well beyond this project.

## The dataset

MaleCNS is an electron-microscopy connectome of the whole adult male fly CNS: central brain, optic
lobes and ventral nerve cord. It was released by FlyEM (HHMI Janelia) with Cambridge, the MRC
Laboratory of Molecular Biology and Google Research, under CC-BY 4.0.

| Property | Value |
|---|---|
| Neurons | about 166,000 |
| Synapses | about 125 million (the neuron-to-neuron edge list is much smaller, since many synapses join the same pair) |
| Cell types | 11,710 |
| Annotations | cell types, hemilineages, eyemaps, predicted neurotransmitters |
| Access | neuPrint (`male-cns:v1.0`), Clio, bulk downloads |

Including the ventral nerve cord matters. The earlier whole-brain connectome (FlyWire) stopped at
the neck, so a simulation of it could only end at descending neurons. MaleCNS reaches motor
neurons.

What the connectome does **not** give, and the simulation must therefore assume:

- electrical synapses (gap junctions), which this kind of EM cannot see;
- neuromodulation;
- each cell type's intrinsic dynamics;
- synaptic plasticity;
- true synaptic strengths. Synapse count is the usual proxy, and the predicted neurotransmitter
  gives the sign.

## Prior work this builds on

- **Shiu et al., Nature 2024.** A leaky integrate-and-fire model of the whole FlyWire brain, with
  synapse counts as weights and neurotransmitter predictions as signs, run in Brian2. Activating
  sugar-sensing neurons drove the proboscis-extension motor neuron MN9, and the model predicted
  feeding and grooming pathways that were then confirmed experimentally. Its code is public and is
  the natural parity reference for Phase 1.
- **Lappalainen et al., Nature 2024.** A connectome-constrained network of the fly visual system,
  whose unknown parameters were trained on a task, predicted recorded neural activity. This is the
  template for the trainable (rate-based) form of the model in Phase 3.
- **NeuroMechFly v2.** An embodied fly in MuJoCo with vision, olfaction and navigation tasks. It is
  the obvious body for Phase 2.

## Why this belongs on this platform

1. **PDSL's intervention vocabulary is already a brain–computer interface.** The constructs in the
   PDSL research plan map directly:

   | PDSL interpretability construct | Connectome counterpart |
   |---|---|
   | `record("residual_18")` | an electrode recording a set of neurons |
   | a linear probe | a decoder |
   | `accum { add(direction, strength) }` | stimulation by current injection |
   | splitting a `stack` at a layer | choosing where the interface sits |

   "The page is the model" becomes "the page is the implant". The interface site, the channel count
   and what the network can see are visible lines in the file.
2. **The simulation can be differentiable.** If the simulated brain is a Producer graph, the implant
   can be trained with gradients that pass *through* the brain. No real interface could ever do
   that, so it gives an upper bound. Comparing it with training by reinforcement learning or
   black-box optimization alone measures how much the inaccessibility of the brain costs. That gap
   is probably the finding that transfers best to real intelligence augmentation.
3. **The work is general.** Sparse matrices, integer-exact indexing and recurrent simulation are
   missing capabilities that many other workloads also need: graph models, sparse attention, and
   sparse Jacobians in automatic differentiation (`SPARSE_GRADIENTS.md`).

## Where the platform is today

The survey of 2026-10-21 found most of the parts this needs in dense form, and none of the sparse
or recurrent-training parts.

### Usable now

- **Gather by an index array.** `CollectionFeatures.c(shape, collection, index)` computes
  `out[i] = collection[index[i]]`. `CollectionFeatures.rows` gathers rows with integer address
  arithmetic, so it stays exact past 2^24 elements. `IndexExpressionTraversalOrdering` is the
  symbolic form of the same idea. A per-edge term `presynaptic[source[e]] * weight[e]` can be
  written today.
- **Forward time stepping with persistent state.** `Temporal`, `TemporalRunner` (compile setup and
  tick once, re-run with state preserved), `HardwareFeatures.loop` (a fixed-count loop inside the
  kernel) and persistent `PackedCollection` state, as in `AutoregressiveModel` and `GRUDecoder`.
- **Threshold and reset.** `greaterThan(v, threshold, 1, 0)` and the other comparison computations,
  plus `exp`, `max`, `min`, `bound` and `rectify`.
- **Backends.** Metal, OpenCL (including the AMD Halo runner), native, and CUDA on the DGX Spark.
  CUDA has its native bridge, code generation and an asynchronous runner. Breadth, performance and
  CI are still open (`CUDA_BACKEND.md`).
- **Trainable transformers, partially.** A small byte-level causal transformer trains end to end
  and beats its unigram baseline (`docs/internals/training-a-language-model.md`). Gradients reach
  the keys and values of sequence and differential attention. Matrix-multiply weight gradients are
  vector–Jacobian products rather than full Jacobians. That work is continuing as its own effort
  (the self-hosted language model plans) and is not repeated here. The implant network in this
  project is small, so it needs that effort to continue, not to finish.

### Two sparse representations, neither ready

- **`Tensor`** (`compute/algebra`) stores values in nested lists that grow as needed. It is
  practical for very sparse data only, becomes inefficient once the number of non-zero values is
  even modest, and lives on the host. It is used mostly to build test data. It is not a candidate
  for 50–125 million edges.
- **`PackedCollection` with a sparse `TraversalOrdering`** is the better design, but it is not
  reliably usable, because `TraversalOrdering` and `TraversableExpression` are poorly integrated:
  - `CollectionOrderingTests.compactOrdering` and `maskOrdering` are marked `knownIssue`;
  - `ReshapeProducer.containsIndex` and `SlicingFeatures.pad` throw for an ordered shape;
  - `IndexMaskTraversalOrdering.indexOf` needs a constant index and scans the mask on the host, so
    it can never appear in generated code;
  - computations strip the ordering from their output shape, so nothing can produce an ordered
    result;
  - `TraversableExpression.containsIndex` throws by default.

  This integration is also the subject of the existing tracker task "Sparse traversal support",
  which wants the same machinery for the large, mostly-zero outputs of delta computations.

### Missing

| Gap | Consequence |
|---|---|
| No sparse matrix type and no sparse matrix–vector product | A neuron-level step cannot be expressed. A dense 166k × 166k matrix is about 110 GB in FP32, so dense is not a fallback. |
| No reduction of per-edge values into their targets (segmented sum or scatter-add), and no atomics in any backend's code generation | Per-edge terms can be formed but not summed into postsynaptic neurons. |
| Index data is floating point, and FP32 backends (Metal) are exact only to 2^24 | Row offsets into a 50-million-edge array cannot be stored exactly on Metal. |
| No integer dtypes in `SafetensorsReference` (it reads BF16, F16, F32 and F64) and no integer payload in `collections.proto` | There is no way to bring an edge list in exactly. |
| `Random` generates values on the host and caches them until refreshed | Per-step Poisson drive would mean host work inside the simulation loop. |
| No owner for a closed simulation loop | The rule that one class owns each loop (`ModelOptimizer` for training, `DiffusionSampler` for sampling) has no counterpart for simulation. |
| No backpropagation through time, no surrogate gradients, and gather has no reduced delta (its gradient falls back to the full Jacobian) | Needed for Phase 3 only. |
| No bridge to an external Python simulator | Needed for Phase 2 only. |

## Design direction for Phase 1

### Sparse connectivity: compressed rows plus a segmented reduction

Store the weight matrix in compressed sparse row (CSR) form, sorted by **postsynaptic** neuron:

- `rowOffsets[n + 1]`: where each neuron's incoming edges start;
- `sources[nnz]`: presynaptic neuron of each edge;
- `weights[nnz]`: synapse count × sign × a scale.

One step's synaptic input is then `input[i] = Σ weights[e] · activity[sources[e]]` for `e` in row
`i`. This is a gather (which exists) followed by a reduction over a data-dependent range (which does
not). Reasons to prefer it:

- it needs no atomics, so it is deterministic and portable to every backend;
- each output element is written once, which fits the framework's one-thread-per-output kernel
  model;
- the transposed form (CSC, sorted by presynaptic neuron) gives the input gradient of the same
  product. Both directions of the vector–Jacobian product therefore have a structural form, and
  neither needs a full Jacobian.

Fly neurons have very uneven in-degree (from a handful of inputs to several thousand), so a
reduction over a data-dependent range is the core new capability. If the kernel loop cannot take a
data-dependent bound, the fallback is **degree-binned padded rows** (ELL): group neurons by in-degree
into a few bins, each with a fixed loop length.

The sparse type is general linear algebra and belongs in `compute/algebra`, not in a
connectome-specific place. Whether it is backed by the `TraversalOrdering` machinery or holds its
arrays directly is a design question for the sparse-matrix task. The matrix–vector product above
does not need a compact ordering to work, so it is not blocked on fixing the ordering integration.
Fixing that integration is still filed in Phase 1, because it is what makes a sparse
`PackedCollection` usable as an ordinary collection everywhere else.

**Event-driven propagation** (touch only the outgoing edges of neurons that spiked) is cheaper at
fly firing rates, but it needs a scatter-add. It is an optimization for later, not a Phase 1
requirement.

### Neuron dynamics

Phase 1 reproduces the Shiu et al. leaky integrate-and-fire model:

- membrane potential and synaptic conductance updated each step with fixed time constants;
- spike when the potential crosses a threshold, then reset and a refractory period;
- a fixed synaptic delay (about 1.8 ms), held in a ring buffer of recent spikes;
- Poisson drive on stimulated neurons.

All of this is element-wise Producer math on state collections, plus the sparse product. One step
is one compiled operation, or a short list of them. The step loop runs on the host and only
dispatches. A global synchronization is needed between steps (every neuron's input depends on
other neurons' last spikes), so steps cannot be fused into one kernel across workgroups. Dispatch
overhead per step therefore matters: at a 0.1 ms step, one simulated second is 10,000 steps.

A **cell-type-level rate model** (11,710 types, one dense matrix of about 137 million entries)
fits today's dense matmul with no new capability. It is filed as an early, independent task. It
exercises the ingest path and the simulation loop before the sparse work lands, and it is the
natural starting point for the trainable model in Phase 3.

### Ingest

The connectome arrives as tables: neurons, with type and predicted neurotransmitter, and weighted
neuron-to-neuron edges. Following the PDSL plan's data decisions:

- the source tables are Apache Arrow Feather files, kept in the shared model directory (see "Assets");
- a one-time conversion (Python, with `pyarrow`) writes the edge list as **safetensors with integer
  arrays**, which the pure Java reader can then load once it accepts integer dtypes;
- the framework's own form is protobuf (`CollectionLibraryData`), which needs an integer payload
  for index arrays;
- signs come from the predicted neurotransmitter, using the same rule as Shiu et al.
  (acetylcholine excitatory; GABA and glutamate inhibitory), so the parity comparison is
  like-for-like.

### Placement

Decided with the project owner on 2026-10-21: **code that depends on a third-party data format
lives in a higher-level module, never in a core domain module**, in the same way `StateDictionary`
and `SafetensorsReference` live in `engine/ml`. The general types the connectome is loaded *into*
(a sparse matrix, neuron population dynamics) carry no format knowledge and stay in the core
modules.

A connectome is a new kind of data, so it may later get an `engine/` module of its own. That
decision is deferred, and the module would be created by the project owner (agents picking up
tracker tasks may not create modules). Until then the loader goes in `engine/ml`, written so that
it can move as a unit: no dependencies from other `engine/ml` code onto it.

| Piece | Module | Why |
|---|---|---|
| Sparse matrix type and sparse product | `compute/algebra` | General linear algebra; no format knowledge |
| Integer-exact index storage | `base/collect` / `base/code` | It is a property of collections and expressions |
| Counter-based device random | `compute/algebra` (next to `Random`) or `compute/stats` | General |
| Integer dtypes in safetensors and protobuf | `engine/ml` | Where both readers live today |
| Spiking and rate neuron population dynamics | `domain/graph` | Neural computation graphs; no format knowledge |
| Simulation loop owner | Where the type it extends lives (decided in its task) | General, not connectome-specific |
| Connectome loading | `engine/ml` for now; possibly a future `engine/` module | Depends on third-party formats |
| Feather → safetensors conversion | a Python script under `tools/` | Reading Arrow from Java would need a new third-party dependency |

## Assets

Agents share `/Users/Shared/models`. Agents running directly on the Mac read it in place, and the
Docker-based agents mount it as a volume. The MaleCNS tables go in
`/Users/Shared/models/male-cns-v1.0/`, with a `SHA256SUMS` file and a `README.md` recording the
source URLs and the CC-BY 4.0 attribution.

Phase 1 needs only the flat-connectome tables, about 2 GB in total, from
`https://storage.googleapis.com/flyem-male-cns/v1.0/connectome-data/flat-connectome/`:

| File | Size | Contents |
|---|---|---|
| `body-annotations-male-cns-v1.0-minconf-0.5.feather` | 13 MB | Classes, types and sides per body |
| `body-neurotransmitters-male-cns-v1.0.feather` | 42 MB | Predicted neurotransmitter per neuron |
| `body-stats-male-cns-v1.0-minconf-0.5.feather` | 780 MB | Synapse count statistics per body |
| `connectome-weights-male-cns-v1.0-minconf-0.5.feather` | 1.1 GB | Body-to-body connection weights; the full graph, including unannotated fragments, which the conversion filters |

The per-synapse tables (`syn-points`, 12.7 GB; `syn-partners`, 6.8 GB; `tbar-neurotransmitters`,
2.7 GB), skeletons and EM volumes are not needed until a later phase wants spatial or per-synapse
detail.

## Phases

Each phase ends at a gate. Work does not move on until the gate is met.

### Phase 1: run the connectome

**Gate:** the neuron-level MaleCNS model runs on the AR framework, and it reproduces the Shiu et al.
result. Activating sugar-sensing gustatory receptor neurons drives MN9, with firing rates matching
a Brian2 reference run of the same model on the same connectome, within a tolerance agreed before
the comparison. Throughput, in simulated seconds per wall-clock second, is measured and recorded on
every available backend.

**Needs from the project owner:**

- the MaleCNS neuron and connectivity tables, downloaded to a directory the agents can read;
- a Brian2 reference run of the Shiu et al. model on MaleCNS: spike rates of the recorded neurons
  for a fixed stimulus protocol and seed;
- a decision on where the connectome loader and the simulation loop owner live.

### Phase 2: closed loop with a body

- Couple the simulation to NeuroMechFly: sensory neurons in (photoreceptors through the eyemaps,
  mechanosensory, olfactory) and descending neurons out to its leg controllers.
- Show one behavior the unaugmented model performs imperfectly.
- The first target is **path integration in the central complex**. The fly's heading circuit is
  well characterized, path integration drifts measurably in the dark, and that drift is exactly
  what extra memory capacity could plausibly reduce. Augmentation then has a clean definition:
  homing error against time.

**Gate:** a stable heading representation in the simulated central complex, with baseline drift
measured, in closed loop.

### Phase 3: a trainable brain model and the implant

- A connectome-constrained rate model (the Lappalainen approach) whose unknown parameters are
  trained, with backpropagation through time, surrogate gradients for spiking validation runs, and
  structural vector–Jacobian products for the sparse product and gather.
- The implant as a PDSL file: a small transformer between a recorded population and a stimulated
  one, with bounded output.
- Constraints from day one, so the implant cannot simply perform the task itself:
  - the implant sees only neural activity, never the environment;
  - the channel count is limited, and is the experimental variable;
  - the interface sites are fixed, as a surgeon would fix them.
- Controls: the network alone with identical inputs and outputs; a degree-preserving shuffle of the
  connectome; a lesion of the region the implant connects to.

**Gate:** a measurable change in task performance attributable to the implant under the controls,
or a well-characterized null.

### Phase 4: the experiment

Sweep channel count, interface site, implant size, real against shuffled connectome, and the two
training regimes (gradients through the brain against black-box optimization). Optionally add
plasticity at the mushroom body's learning synapses, and study co-adaptation.

## Phase 1 tasks

Filed on 2026-10-21 in the Common tracker project, in the release under development. Blockers are
recorded on the tasks. The order below is the dependency order (tracker id prefixes in brackets):

1. Make sparse `TraversalOrdering` work through `TraversableExpression` [82bda0b7]. Related to
   the existing "Sparse traversal support" task [87b95b81].
2. Integer-exact index storage for gather and sparse addressing on FP32 backends [2c0793a7].
3. Integer dtypes in `SafetensorsReference` and an integer payload in `collections.proto`
   [3e0043d3].
4. A sparse matrix type and a sparse matrix–vector product with a segmented reduction (after 2)
   [66b57639].
5. Counter-based random numbers generated inside kernels [18c76a7a].
6. Spiking population dynamics as Producers: leaky integrate-and-fire with reset, refractory
   period and synaptic delay (after 5) [04470395].
7. A simulation loop owner for recurrent dynamical systems [6d8bbb62].
8. MaleCNS ingest into CSR form, with signs from predicted neurotransmitters (after 3 and 4)
   [2218ffdf].
9. A cell-type-level rate model of MaleCNS on dense matmul (after 7; independent of the sparse
   work) [186697ce].
10. Reproduce Shiu et al. on its own FlyWire inputs, using the authors' data files and Brian2 code
    (after 3, 4, 6 and 7) [d72c5ca4]. Any mismatch here is unambiguously in the AR simulation.
11. Reproduce Shiu et al. on MaleCNS against a Brian2 reference: the Phase 1 gate (after 4, 6, 7,
    8 and 10) [2a8fe992].
12. Throughput benchmark of the neuron-level simulation on every backend (after 11) [1cd4083d].

Not filed yet, because they belong to later phases: the transposed sparse product and the reduced
deltas of the sparse product and gather, backpropagation through time, surrogate gradients,
event-driven propagation, and the bridge to the body simulator.

## Ethics

The project owner has asked that the ethical framework be developed alongside the technical work
rather than inside this plan. Three design choices change both the moral risk and the science, and
should be settled deliberately before Phase 2:

- keep nociceptive pathways out of the task and stimulus protocols;
- decide explicitly whether to add plasticity, since it turns a fixed circuit into one that learns;
- cap and log simulation duration.

## Out of scope

- Claims about absolute augmentation capacity in humans. Findings on a 166k-neuron fly do not
  transfer quantitatively. The method and the qualitative relationships (bandwidth against site,
  the gradient against black-box gap, the effect of brain-side plasticity on co-adaptation) are
  what might.
- Competing with dedicated neural simulators on breadth of neuron models. Phase 1 needs one neuron
  model, reproduced exactly.
- Biophysically detailed (multi-compartment) neurons.

## Open questions

1. Is the sparse matrix backed by the `TraversalOrdering` machinery, or does it hold its arrays
   directly and rely on gather? (Decided in task 4.)
2. Can the kernel loop take a data-dependent bound, or does the sparse product need degree-binned
   padded rows? (Answered by task 4's first investigation.)
3. Where do the connectome loader and the simulation loop owner live?
4. What tolerance counts as parity with Brian2, given that spiking simulations diverge spike by
   spike even between two runs of the same simulator? Comparing mean rates over trials is the
   likely answer; it should be fixed before the comparison is run.
