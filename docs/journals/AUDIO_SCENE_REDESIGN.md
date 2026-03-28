# AudioScene Pipeline Redesign — Decision Journal

This file is a running log of decisions, observations, and reasoning made
during the implementation of the pipeline redesign described in
[../plans/AUDIO_SCENE_REDESIGN.md](../plans/AUDIO_SCENE_REDESIGN.md).

**Context:** This journal begins where the `AUDIO_SCENE_LOOP` optimization
journal ends. That effort spent a week on expression-level optimizations
(LICM factoring, exponent strength reduction, copy elimination) and
conclusively proved they have no measurable impact on GPU kernel execution
time. The GPU compiler performs the same optimizations independently. The
remaining path to real-time rendering requires architectural changes.

**Purpose:** Capture the reasoning behind architectural decisions as they
are made. When evaluating whether a change is worth the risk, this journal
should make it possible to understand what was considered and why alternatives
were rejected.

## How to Use This File

**Before starting work**, add a dated entry with:
- What you're investigating or implementing
- What you understand the current state to be
- What approach you're considering and why

**After making a change**, update the entry with:
- What you actually did
- What you observed (measurements, generated code, behavior)
- What you decided NOT to do and why

**When you hit an obstacle**, document:
- What you tried and why it didn't work
- What you're going to try instead

**Format:** Reverse-chronological order (newest entries first).

---

## Entries

### 2026-03-06 — Initial Architecture Analysis

**Goal:** Understand the current architecture deeply enough to propose a
redesign focused on per-channel parallelism and reduced memory overhead.

**Context:** The `feature/audio-loop-performance` branch established that:
- The monolithic kernel (2783-line inner loop, 6 channels sequential) is
  memory-bandwidth bound at 10.4M array operations per tick
- Expression-level optimizations (LICM, strength reduction, copy elimination)
  have zero measurable GPU kernel impact
- prepareBatch() adds 53ms (27.5%) of Java-side overhead per tick
- Total budget is 192ms vs 92.9ms target (2.07× over)

**Research conducted:** Deep exploration of three architectural layers:

1. **Cell graph layer** (CachedStateCell, SummationCell, CellAdapter, MultiCell,
   BatchedCell, FilteredCell) — traced the push/receptor data flow pattern,
   identified that CachedStateCell double-buffering creates the copy chains
   (535 copies + 103 zero resets per iteration), confirmed the existing
   SummationCell optimization shows copy elimination is viable

2. **AudioScene pipeline** (AudioScene, PatternAudioBuffer, AutomationManager,
   EfxManager, MixdownManager) — traced end-to-end from scene definition
   through buffer consolidation to the per-tick OperationList assembly.
   Identified the four phases: reset → prepareBatch × 24 → Loop(4096) → advance

3. **Compilation pipeline** (OperationList, Loop, Repeated, ComputableParallelProcess,
   ComputationScopeCompiler) — understood why the monolithic kernel exists:
   CellList builds one OperationList, uniformity check requires all ops same count,
   Loop wraps the entire tick, memory consolidation is global

**Key architectural constraints identified:**
- CellList.getAllTemporals() produces a single flat ordered list — no channel grouping
- OperationList.get() compiles uniform lists to a single kernel — all-or-nothing
- Memory consolidation (PackedCollection.range()) is global across all channels
- Loop wraps the entire tick body — can't have per-channel loops
- Parent-child CellList hierarchy determines tick ordering — works within one list

**Four design options evaluated:**
- **A: Channel-Scoped CellLists** — one CellList per channel, each with own Loop
- **B: Flat Buffer Pipeline** — replace Cell graph with direct buffer transforms
- **C: Hybrid** — Cell graph for structure, graph analysis for optimized execution
- **D: Pull Model** — reverse data flow direction (push → pull)

**Recommendation:** Start with Option A (channel-scoped CellLists) as it requires
the least framework disruption while delivering the most impactful change
(per-channel parallelism, silent channel skipping, better cache locality). Follow
with Option C (copy chain elimination via graph analysis) once channels are
independent.

**Open questions requiring investigation:**
- Q1: Are channels truly independent? (cross-channel effects?)
- Q2: What is GPU dispatch overhead for 6-12 small kernels vs 1 large?
- Q3: Can memory consolidation be scoped per-channel?
- Q4: Does CellList fluent API support multiple return lists?
- Q5: Do feedback loops cross channel boundaries?
- Q6: How much prepareBatch() work is redundant across ticks?

**Next steps:** Begin investigating Q1 and Q4 by reading AudioScene.getCells()
to understand how the cell graph is currently constructed and whether channel
boundaries are already visible at construction time.

---

*(Add new entries above this line, newest first)*
