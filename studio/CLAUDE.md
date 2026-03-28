# studio/ — Multimedia Composition

## CRITICAL: NEVER Create New Maven Modules

**Agents MUST NEVER create new Maven modules.** The Maven module structure is externally controlled. If a task requires a new module, **STOP and abandon the task**. Do not create new `pom.xml` files, add `<module>` entries to parent POMs, or create directory structures constituting a new module. Document the requirement in completion notes instead — the project owner handles module creation.

---

High-level multimedia orchestration layer for pattern-based music, audio scene
arrangement, spatial visualization, effects routing, and automation. Builds on
engine/audio for synthesis and compute/time for temporal operations.

## What Belongs Here

- Musical patterns, notes, scales, composition primitives (`music`)
- Audio scene composition, arrangement, effects processing, mixdown (`compose`)
- Spatial audio, 3D sound visualization, composition integration (`spatial`)
- AudioScene, PatternSystemManager, MixdownManager
- High-level arrangement and sequencing of audio elements
- Effects routing, automation curves, mix bus management

## What Does NOT Belong Here

- Low-level audio synthesis or signal processing — those belong in `engine/audio`
- ML model definitions or training — those belong in `engine/ml` or `engine/optimize`
- Basic signal operations like FFT — those belong in `compute/time`
- Core musical math (frequencies, intervals as raw numbers) — those belong in `compute/`
- 3D scene geometry or rendering — those belong in `domain/space` or `engine/render`

## Key Conventions

- `compose` is the integration point that ties music and spatial together
- `music` handles patterns, notes, scales — the "what to play"
- `spatial` handles 3D visualization and spatialization of audio data
- Composition works at a higher abstraction than synthesis — it arranges, not generates
- Audio generation primitives (oscillators, filters, envelopes) live in `engine/audio`

## Modules

- [music](music/README.md) — Musical patterns, notes, scales, composition
- [compose](compose/README.md) — Audio scene composition, arrangement, effects processing
- [spatial](spatial/README.md) — Spatial audio, 3D sound, composition integration
