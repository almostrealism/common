# Plan: Spiking population dynamics as Producers (leaky integrate-and-fire)

- **Tracker task:** 04470395-234c-4e3b-912d-6feb902c3067 — "Spiking population dynamics as Producers: leaky integrate-and-fire with reset, refractory period and synaptic delay"
- **Project / release:** Common / Common 0.76 (the release `pom.xml` names at the repository root)
- **Estimated complexity:** Medium
- **Blocked by:** 18c76a7a (counter-based random numbers generated inside kernels) — **landed**; see Dependencies.

## Motivation

This task is Phase 1 task 6 of the connectome model organism
([CONNECTOME_MODEL_ORGANISM.md](CONNECTOME_MODEL_ORGANISM.md)). The Phase 1 gate
is to run the MaleCNS connectome on the AR framework and reproduce the Shiu et
al. (Nature 2024) leaky integrate-and-fire (LIF) result. The neuron model is the
one piece of that gate that is *not* general linear algebra: it is the forward
dynamical rule each neuron follows every time step.

The requirement — and the reason it belongs on this platform at all — is that one
time step of a whole population must be expressed **entirely as Producer math on
persistent state collections**: no Java arithmetic, no per-element host access.
Only then is a step a single compiled operation (or a short list of them) that
every backend can run, that caches once and re-dispatches per step, and that is
differentiable for the trainable rate model in Phase 3. A step written as a Java
loop over `PackedCollection` elements would be a thousand times slower, would not
hardware-accelerate, and would break automatic differentiation — the exact
failure the project's fundamental rule forbids. At a 0.1 ms step one simulated
second is 10,000 steps, so the per-step cost has to be one dispatch of compiled
native code, not host arithmetic.

## Scope

Concretely, this task delivers the **dynamics of one time step of a population of
LIF neurons**, built from `CollectionProducer` compositions over persistent state
collections, following the Shiu et al. model (its public Brian2 code is the parity
reference). In scope:

1. **State collections** for a population of `n` neurons, held device-resident and
   persistent across steps: membrane potential `v[n]`, synaptic conductance
   `g[n]`, a refractory countdown `refractory[n]`, a spike ring buffer
   `ring[delaySteps, n]`, and the ring's dynamic write index (a single-element
   collection). Plus the seed/counter collections that drive the Poisson input
   (reused from the counter-based generator).

2. **The step, as Producer math**:
   - synaptic conductance updated with a fixed time constant (exact exponential
     decay `g · exp(-dt/τ_syn)` plus the step's input, or forward Euler — whichever
     the Shiu et al. reference uses; confirmed against the reference before the
     parity test, see Open Questions);
   - membrane potential updated with a fixed time constant (leak toward rest plus
     the conductance-driven current), held at reset while the neuron is refractory;
   - spike detection `greaterThan(v, threshold, 1, 0)` (the exact
     `ComparisonFeatures.greaterThan(a, b, trueValue, falseValue)` the task names);
   - reset of `v` to `V_reset` where a spike occurred, and (re)load of the
     refractory countdown there, with the countdown decremented elsewhere — all as
     masked Producer expressions, no branching on the host;
   - the current spike vector written into the ring buffer at the write index, and
     the write index advanced — device-side, via the slot-update / `into`
     assignment pattern (see Approach); the delayed spike vector (the row
     `delaySteps` behind the write index, ≈ 1.8 ms = 18 steps at dt = 0.1 ms) read
     back out as a producer.

3. **Poisson drive** on a chosen set of input neurons, using the in-kernel
   counter-based random generator (`rand` with `AR_RANDOM=counter` →
   `CounterRandom`): a neuron in the input set spikes this step when its uniform
   draw is below `rate · dt`. The draw is a pure function of `(seed, counter,
   index)`, so it is reproducible and, for the uniform, bit-identical across
   backends — which is what lets the parity test use deterministic drive.

4. **Synaptic input as a producer argument.** The product of the weight matrix and
   the delayed spike vector enters the step as a `Producer<PackedCollection>`
   argument, so the *same* step works with a dense matmul (the small tests here,
   and later the cell-type rate model) and with the sparse matrix–vector product
   once task 66b57639 lands. Building that product is **not** in this task.

5. **Javadoc** on every new class/method, and a test suite (see Success criteria).

Explicitly **out of scope** (named as dependencies or later tasks, not widened
into here):

- The **simulation loop** that calls the step repeatedly. One class owning the
  recurrent loop is Phase 1 task 7 (6d8bbb62), a *separate* task that is not a
  blocker of this one. This task delivers the step and its persistent state; the
  loop owner will drive it. (Mirrors how `ModelOptimizer` owns training and
  `DiffusionSampler` owns sampling — the loop is never written inline.)
- The **sparse matrix type and sparse matrix–vector product** (task 66b57639) and
  **MaleCNS ingest** (task 2218ffdf). The step consumes synaptic input as an
  argument precisely so it does not depend on these.
- The **full MaleCNS / FlyWire parity runs** (tasks d72c5ca4, 2a8fe992) and the
  **throughput benchmark** (1cd4083d). This task proves the dynamics on a single
  neuron and a small hand-wired network, which is what "Done when" asks for.
- Any change to `CounterRandom` / `Random` themselves.

## Approach

### Placement — confirmed `domain/graph`, justified

The CONNECTOME placement table assigns "Spiking and rate neuron population
dynamics" to `domain/graph` ("Neural computation graphs; no format knowledge"),
and the module's own `CLAUDE.md` lists "Neural network layers using the
Cell/Receptor/Transmitter pattern" as belonging there while routing core math
types to `compute/` and training/inference to `engine/`. The LIF step is neural
computation-graph structure carrying no data-format knowledge, so `domain/graph`
is correct. The reusable building blocks it leans on already live at or below that
layer: `ComparisonFeatures.greaterThan`, `exp`/`max`/`min`/`bound`/`rectify`, and
the slot-update machinery (`CollectionSlotUpdateComputation` in `compute/algebra`,
surfaced through `LayerFeatures.cacheWrite`/`cacheRead` in `domain/graph`).

**Generality of the element-wise dynamics.** The per-neuron update rule (leaky
decay, threshold, reset, refractory countdown) is pure element-wise Producer math
and is not connectome-specific. Where a reusable method is warranted it should be
expressed as a `CollectionProducer` composition on a `LayerFeatures`-style mixin
in `domain/graph` (the project's "no utility/helper classes; behaviour belongs on
a type" rule), not as private helpers on the owning class. The highest sensible
rung is the general neural layer, which is `domain/graph`; there is no more
general home, because the rule is specifically a *neuron* model.

### What shape the deliverable takes (the Cell/Block question)

The task flags the naming rule: a class ending in `Cell`/`Block` must implement
`Cell`/`Block`. The recommended shape is a **stateful owner class** in
`domain/graph` that holds the population's persistent state collections and builds
the one-step operation as Producer math — the established pattern for recurrent
systems that own device-resident state (`AutoregressiveModel` owns `position` and
its device-side advance; `GRUDecoder` owns a persistent hidden-state collection
read and rewritten each step). These precedents are deliberately **not** `Cell`s
or `Block`s, and this class should follow them, so it is named for what it *is* —
a population of neurons and its dynamics — rather than `...Cell`/`...Block`.

The reason the owner-class shape fits better than a single-signal `Cell`/`Block`
is the step's **dual data flow**: the step must both *emit* the delayed spike
vector (which the caller multiplies by the weight matrix) and *consume* the
resulting synaptic-input producer. A `Cell`/`Block` forward path carries one
signal protein, so modelling this as one `Block` would force one of the two flows
through a side channel. The owner class instead exposes the delayed-spike vector
as a producer and takes the synaptic-input producer as the step argument,
returning the step's compiled operations and the current spike vector. If a
reviewer prefers the `Block` shape (for composition with the rest of the layer
API), that is a design decision to settle at approval — see Open Questions — and
if taken, the class is renamed to `...Block` and implements `Block` so the naming
rule holds. Either way the pure dynamics stay reusable Producer compositions.

### The ring buffer and dynamic write index

The 18-step synaptic delay is a ring of recent spike vectors with a write index
that advances each step — structurally identical to the KV-cache write. Reuse the
existing mechanism rather than inventing one:

- the ring is a persistent `[delaySteps, n]` collection the owner holds;
- each step writes the current spike vector into the row selected by the write
  index, keeping all other rows, via `CollectionSlotUpdateComputation` assigned
  back into the ring with `into(...)` — exactly what `LayerFeatures.cacheWrite`
  does (one computation over the raw arguments, part of the compiled operation
  order, no host copy, nothing for `Process.optimize` to isolate);
- the delayed spike vector is read with the `cacheRead` pattern: the row
  `(writeIndex − delaySteps)` modulo `delaySteps`, gathered as a producer the
  caller feeds into the weight product;
  <!-- TODO(review): confirm exact D-step delay vs ring row count — with exactly delaySteps rows, (writeIndex − delaySteps) mod delaySteps is the current write row, so the delay is exact only if the read strictly precedes the write; otherwise delaySteps+1 rows are needed. -->

- the write index is a single-element device-resident collection advanced with the
  `a(cp(idx), …)` assignment pattern `AutoregressiveModel.advancePosition` uses,
  wrapping modulo `delaySteps`. The Poisson counter advances the same way (and may
  be the same step counter the future loop owner advances).

Whether to reuse `cacheWrite`/`cacheRead` directly or construct
`CollectionSlotUpdateComputation` and the gather read directly in the owner is an
implementation detail for the coding session; the plan's point is that **no new
slot-update computation is needed** — the capability exists.

### Poisson drive

The input-neuron drive uses the landed counter-based generator. A uniform draw
`u = rand(shape(n))` (counter-based) compared `lessThan(u, rate·dt)` gives a 1/0
spike mask on the input set; combine it with the threshold spikes (e.g. the input
set is forced to the Poisson outcome, or the drive adds current — matched to how
Shiu et al. inject the stimulus, confirmed against the reference). Because the
uniform is a pure, cross-backend-exact function of `(seed, counter, index)`, the
drive is deterministic for a fixed seed — which is what makes a spike-for-spike
Brian2 comparison under "deterministic drive" well defined.

### Reference files

| Concern | File / symbol |
|---|---|
| Spike threshold / reset selection | `ComparisonFeatures.greaterThan(a, b, trueValue, falseValue)` (`compute/algebra`), `Conditional` |
| Element-wise decay / leak / current | `CollectionProducer` `multiply`/`add`/`subtract`/`divide`, `exp`, `bound`, `rectify` |
| Counter-based random (Poisson) | `compute/algebra/.../collect/computations/CounterRandom.java`; `rand`/`randn` in `CollectionCreationFeatures`; `AR_RANDOM=counter` |
| Ring-buffer slot write | `compute/algebra/.../collect/computations/CollectionSlotUpdateComputation.java`; `LayerFeatures.cacheWrite` / `cacheRead` |
| Device-side state assignment / advancing an index | `LayerFeatures.into(...)`; `AutoregressiveModel` (`position`, `advancePosition`, `reset`) |
| Persistent recurrent state owner precedent | `engine/ml/.../ml/AutoregressiveModel.java`, `engine/ml/.../ml/midi/GRUDecoder.java` |
| Cell / Block contracts (if the Block shape is chosen) | `domain/graph/.../graph/Cell.java`, `domain/graph/.../model/Block.java` |

## Success criteria

A reviewer can confirm the task is done when:

1. **One step is Producer math, verifiable in the kernel.** The step compiles to a
   native operation (or a short list), with no Java arithmetic and no per-element
   host access in any class that participates in the computation; the build
   validator's `code_policy` check passes. `ar-profile-analyzer` on a profiled run
   shows the membrane/conductance update, threshold, reset and ring write in the
   generated source, not host-side.

2. **Single-neuron analytic firing rate.** One neuron under constant input current
   fires at the analytic LIF rate
   `r = 1 / (t_ref + τ_m · ln((R·I − V_reset) / (R·I − V_th)))` for `R·I > V_th`,
   within a stated tolerance, over a range of currents (including near threshold
   and well above). A dedicated test drives the neuron for a fixed number of steps
   and compares the measured rate.

3. **Small-network Brian2 parity, spike for spike.** A hand-wired network (tens of
   neurons, dense weights) under deterministic drive reproduces a reference Brian2
   run of the same model, spike for spike, within the agreed tolerance. The project
   owner supplies the reference spike raster if Brian2 cannot be run in the
   session (see Dependencies). The test asserts matching spike times per neuron.

4. **Refractory period** verified by a dedicated test: a neuron driven hard cannot
   spike again until `t_ref` has elapsed; inter-spike intervals are floored by the
   refractory period.

5. **Synaptic delay** verified by a dedicated test: a presynaptic spike affects its
   postsynaptic target exactly `delaySteps` (≈ 1.8 ms) later, not sooner — read
   directly off the ring buffer / the target's response timing.

6. All new classes and public methods carry **Javadoc**; the build validator
   (checkstyle, code_policy, test_timeouts, duplicate_code) passes; the targeted
   tests above pass. Broad backend breadth is left to CI.

Tests extend `TestSuiteBase`, carry explicit `@Test` timeouts, and live in the
`domain/graph` test sources (or `engine/utils` if they need cross-module test
utilities), following the existing test conventions.

## Dependencies and open questions

**Dependencies**

- **Counter-based random (18c76a7a) — landed.** `CounterRandom`, `RandomProducer`,
  and `CounterRandomTests` are present; `rand`/`randn` select the in-kernel
  generator via `AR_RANDOM=counter`. The Poisson drive consumes it directly. No
  further RNG work is needed here.
- **Brian2 reference output.** Success criterion 3 needs a reference spike raster
  from a Brian2 run of the Shiu et al. model on the small hand-wired network, for a
  fixed stimulus and seed. The project owner provides this if agents cannot run
  Brian2 (the CONNECTOME plan already lists this as a project-owner input). The
  implementation can proceed on criteria 1, 2, 4 and 5 before the reference lands,
  but criterion 3 is gated on it.
- **Sparse product / ingest (66b57639, 2218ffdf) — not required.** The step takes
  synaptic input as a producer argument; the small tests use a dense matmul.

**Open questions for the approver**

1. **Deliverable shape — owner class vs `Block`.** The plan recommends a stateful
   owner class (per `AutoregressiveModel`/`GRUDecoder`) because of the dual data
   flow. If the reviewer prefers a `Block` for composition with the layer API, the
   dual flow must be mapped (delayed-spike read as a companion producer, as
   `cacheRead` pairs with `cacheWrite`) and the class named `...Block` implementing
   `Block`. Decide before implementation.
2. **Exact-exponential vs forward-Euler integration.** The reference (Shiu et al.
   Brian2 code) fixes this. The implementation must match whichever the reference
   uses, so the generated dynamics are like-for-like; confirm which before the
   parity test is written. Both are expressible as Producer math.
3. **Parity tolerance.** Spiking simulations diverge spike by spike even between
   two runs of the same simulator; CONNECTOME open question 4 flags this.
   Deterministic drive with a fixed seed should make the small-network test
   exactly reproducible, but the tolerance (exact spike match vs mean-rate match)
   should be fixed with the project owner before the comparison is run.
4. **Poisson injection form.** Whether the drive forces the input neurons' spike
   output or injects current must match the reference's stimulus protocol; confirm
   with the reference.
5. **Refractory encoding.** A countdown-in-steps collection is proposed (decrement
   each step, reload on spike). Confirm this matches the reference's refractory
   handling (absolute refractory with `v` clamped at reset) rather than a
   threshold-adaptation scheme.

**Follow-ups filed:** none. All adjacent work (sparse product, ingest, loop owner,
parity runs, benchmark) is already filed as separate Phase 1 tasks in the tracker.

## Machine requirement

**None.** All success criteria — the single-neuron analytic rate, the refractory
and delay tests, and the deterministic small-network Brian2 parity — run on the
native backend; the Poisson uniform is reproducible and cross-backend-exact, so no
Metal-only behaviour is exercised. The implementation workstream therefore needs no
`requiredLabels` pin and no `*-workstream.yaml` file is written for this branch.
(Cross-backend spike parity and throughput on every backend belong to the separate
benchmark task 1cd4083d, which would carry its own machine requirements.)
