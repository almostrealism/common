# Almost Realism Bioscience Module (`ar-bioscience`)

The bioscience module combines the biological models of `ar-biology` with the
machine-learning engine of `ar-ml`.

## Purpose

This module exists to:

1. **Couple simulated organisms to trained networks**, for example an artificial network that
   reads from a chosen set of simulated neurons and writes back into others.
2. **Ingest biological datasets** through the engine's model and tensor formats.
3. **Host bioscience applications** that need both a biological model and the ML engine.

## What belongs here

- Anything that needs both `ar-biology` and `ar-ml`.
- Loaders that read third-party biological data formats into the platform's collections.
- Experiments and applications that study a biological model with machine-learning tools.

## What does not belong here

- Biological models that do not need `ar-ml`. Put them in `domain/biology`, where lower
  layers can use them.

## Dependencies

- `ar-biology`
- `ar-ml`
