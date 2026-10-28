# Almost Realism Biology Module (`ar-biology`)

The biology module models living systems: the natural-science domain where the physical and
chemical models of `ar-chemistry` meet the network and graph structure of `ar-graph`.

## Purpose

This module exists to:

1. **Model biological structure** in its own terms: neurons, synapses, cell types,
   connectomes.
2. **Express biological dynamics as computation graphs**, so a simulation compiles to native
   kernels like every other model on the platform.
3. **Bridge the natural sciences and network theory**, giving one home to types that are both
   physical (membranes, conductances, neurotransmitters) and graph-structured (connectivity,
   pathways).

## What belongs here

- Biological entities and their parameters (neuron models, synapse models, cell types).
- Dynamics of biological systems, written as PDSL or as `CollectionProducer` graphs.
- Connectome structure: connectivity, signs, delays, and the populations they join.

## What does not belong here

- Anything that needs `ar-ml` (transformers, trained networks coupled to a simulation,
  third-party formats read through the engine). Put it in `studio/bioscience`.
- Loops that drive a simulation over time. The loop belongs to the platform's loop owner,
  not to the model.
- General sparse or tensor operations. Those belong in `compute/` or `base/`, where every
  domain can use them.

## How biological dynamics are expressed and stepped today

The population models this module builds on are in `ar-graph`
(`LeakyIntegrateAndFirePopulation`, `RatePopulation`), and `SimulationRunner` in `ar-time` steps
them. [Simulating a Spiking Neural Population](../../docs/internals/neural-population-simulation.md)
walks through a tested example from state to recorded spikes: the step, synaptic delay,
connectivity, reproducible Poisson drive, the loop owner, and the known limits.

## Dependencies

- `ar-chemistry` (and through it `ar-physics`)
- `ar-graph`

## Tests

Like the other `domain/` modules, this module has no test sources of its own. Tests that
extend `TestSuiteBase` live in `engine/utils`.
