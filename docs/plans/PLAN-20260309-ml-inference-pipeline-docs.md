# Document the ML Inference Pipeline Internals

**Category:** Documentation
**Date:** 2026-03-09
**Estimated Complexity:** Medium

---

## Motivation

The Almost Realism platform has a working ML inference pipeline — it can load transformer
weights, build computation graphs, compile to native code, and generate text autoregressively.
This pipeline represents one of the platform's most impressive capabilities and is the
direct bridge to proof-of-value work (model replication, self-hosted training, self-understanding).

However, while the ML README and CLAUDE.md provide good API-level documentation (how to
use `AttentionFeatures`, `AutoregressiveModel`, `StateDictionary`), there is no internals
documentation explaining **how the inference pipeline actually works** — specifically:

- How KV caches are allocated, written, and read during attention
- How autoregressive generation coordinates position tracking, token embedding, and model
  forward passes
- How GQA expansion is performed at cache write time vs. read time
- How the prompt phase differs from the generation phase
- How position embeddings (RoPE) are computed and applied per-step

These are exactly the topics that a developer (or a model) needs to understand before they
can extend, optimize, or debug the inference pipeline. Without this documentation:

- Performance optimization of attention is guesswork
- Adding new model architectures requires reverse-engineering `AttentionFeatures`
- The KV cache strategy (expanded caches, zero-initialization, GQA expansion at write time)
  is undocumented tribal knowledge
- Future proof-of-value work (new model replications, inference optimization) will be slower

This documentation also advances the self-understanding vision: if we want models to reason
about the platform's ML capabilities, the inference pipeline must be documented in a way
that is precise, complete, and mechanically understandable.

---

## Scope

Create one new internals document:

### `docs/internals/ml-inference-pipeline.md`

**Sections:**

1. **Overview** — High-level flow from loaded weights to generated tokens. ASCII diagram
   showing the full pipeline: `StateDictionary → Model Build → Compile → AutoregressiveModel → Token Generation`.

2. **KV Cache Architecture** — The most complex and least documented part:
   - Cache allocation: shape `(seqLen, heads, headSize)` for expanded caches
   - Zero-initialization and why it matters (prevents numerical explosions)
   - GQA expansion strategy: expand at cache write time, not read time
   - Cache write: how Q/K/V projections feed into position-indexed cache slots
   - Cache read: how attention reads the full cache up to the current position
   - Causal masking: how future positions in the cache are masked out
   - Memory layout: how caches are stored as `PackedCollection` with `TraversalPolicy`

3. **Autoregressive Generation Loop** — How `AutoregressiveModel` orchestrates generation:
   - Two-phase operation: prompt phase vs. generation phase
   - Prompt phase: feed prompt tokens to build KV cache, cache logits
   - Generation phase: sample from cached logits, feed sampled token, cache new logits
   - Position tracking: how `step` callback updates position for RoPE and cache indexing
   - Token embedding: how `token` callback maps token IDs to embedding vectors
   - Temperature sampling: greedy vs. softmax sampling

4. **Attention Computation Flow** — Step-by-step through a single attention layer:
   - QKV projection (separate Q, K, V or fused QKV)
   - RoPE application to Q and K
   - Optional QK-Norm
   - GQA expansion of K and V
   - Cache write at current position
   - Scaled dot-product attention with causal mask
   - Output projection
   - Diagram showing data flow through attention with cache

5. **Cross-Attention** (for DiffusionTransformer):
   - How cross-attention differs from self-attention
   - Context KV projection and caching
   - Prepended conditioning strategy

6. **Related Files** — Links to key source files and other internals docs.

---

## Approach

1. **Read source code thoroughly** — `AttentionFeatures.java` (the `attention()` method
   around line 780+), `AutoregressiveModel.java`, relevant test files like
   `BranchCacheTest.java`, and `DiffusionTransformer.java`.

2. **Follow existing internals doc style** — Match the format of `computation-graph-to-process-tree.md`,
   `process-optimization-pipeline.md`, and `backend-compilation-and-dispatch.md`:
   - ASCII diagrams for data flow
   - Code snippets from actual source (with file:line references)
   - Cross-references to related docs
   - "Related Files" section at the end

3. **Verify accuracy** — Cross-reference every claim against the source code. The attention
   method has subtle details (GQA expansion strategy, cache indexing, causal masking) that
   must be precisely described.

4. **Build verification** — Run `mvn clean install -DskipTests` to ensure the doc doesn't
   break the build (unlikely but good practice).

---

## Success Criteria

- [ ] `docs/internals/ml-inference-pipeline.md` exists and covers all 6 sections above
- [ ] KV cache lifecycle is explained with ASCII diagrams showing cache state at each step
- [ ] Autoregressive generation loop is documented with both prompt and generation phases
- [ ] GQA expansion strategy (write-time vs. read-time) is explicitly documented
- [ ] All code references point to real file:line locations
- [ ] Document follows the style of existing internals docs
- [ ] Build passes with `mvn clean install -DskipTests`

---

## Dependencies

- Compilation pipeline docs (completed in prior session on branch `project/plan-20260308-114252`)
- No code changes required — this is pure documentation

---

## Connection to Vision

This document directly supports the platform's trajectory toward proof-of-value ML work
and self-understanding. The inference pipeline is where the platform's computation graph
abstraction meets real-world ML workloads. Documenting it:

- Enables future model replication tasks (new architectures can reference this doc)
- Supports inference optimization work (you can't optimize what you don't understand)
- Provides training data for models that will eventually reason about the platform
- Bridges the gap between the compilation pipeline docs (now complete) and the
  application-level ML documentation
