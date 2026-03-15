# TODO/FIXME Triage and Dead Code Cleanup

**Category:** Code Quality
**Date:** 2026-03-12
**Estimated Complexity:** Medium

---

## Motivation

The codebase contains 506 TODO/FIXME comments across 192+ files and several files of
confirmed dead code with auto-generated method stubs. The Manager Log's 2026-03-10 entry
explicitly identified this as the next task after @TestDepth standardization:

> "Review the 506 TODO/FIXME comments — triage into actionable bugs, performance
> opportunities, and speculative noise. Remove or address the critical ones."

This matters because:

1. **Known bugs are hiding in TODOs.** `ImageResource.clip()` has a confirmed bug where
   the `cx` parameter is declared but never used (uses `cw` instead at line 286), producing
   incorrect pixel clipping results. This was flagged with a TODO years ago but never fixed.

2. **Dead code creates false complexity.** `HybridJobFactory` (126 lines, 10 auto-generated
   stubs) is never instantiated anywhere. `AlmostCache` has a single method that throws
   `RuntimeException("Not implemented")`. Multiple physics classes
   (`VolumetricDensityAbsorber`, `SpanningTreeAbsorber`, `AtomicProtonCloud`,
   `SpanningTreePotentialMap`, `PotentialMapHashSet`) and `SpanningTreeColorBuffer` contain
   auto-generated stubs. These classes add noise for both humans and AI systems trying to
   understand the codebase.

3. **Speculative TODOs obscure real issues.** Of the 506 TODOs, many are aspirational notes
   ("Should this wrap instead of being continuous?") or IDE-generated placeholders
   ("Auto-generated method stub"). These dilute the signal from genuine bugs and create a
   sense of technical debt that is larger than reality.

4. **Advancing self-understanding.** A clean codebase — free of dead code and with TODOs
   that represent genuine work items rather than noise — is easier for models to reason
   about. This cleanup directly supports the long-term vision.

---

## Scope

### Phase 1: Fix Confirmed Bugs (High Priority)

**1a. Fix `ImageResource.clip()` (CRITICAL)**
- **File:** `flowtree/src/main/java/io/flowtree/fs/ImageResource.java:261`
- **Bug:** The `cx` parameter (x-offset for clipping) is declared but never used in the
  pixel copy loop. Line 286 uses `(i + cw)` where it should use `(i + cx)`.
- **Fix:** Replace `cw` with `cx` in the coordinate calculation at line 286.
- **Verification:** Review the full clip method logic to ensure the fix is correct.

**1b. Document the `CollectionVariable` generics issue**
- **File:** `base/code/src/main/java/io/almostrealism/collect/CollectionVariable.java:56`
- **Issue:** The TODO notes that generics are wrong — `ArrayVariable<T>` is a
  `Variable<Multiple<T>>`, so `CollectionVariable` creates redundant `Multiple<Collection<Double>>`.
- **Action:** This is a deep type system issue that requires careful analysis. Add a clear
  javadoc comment explaining the known limitation and why it hasn't been fixed (changing it
  would cascade through the expression tree hierarchy). Convert the TODO to proper javadoc.

### Phase 2: Remove Dead Code (High Priority)

**2a. Remove `HybridJobFactory`**
- **File:** `flowtreeapi/src/main/java/io/flowtree/job/HybridJobFactory.java`
- **Evidence:** 126 lines, 10 auto-generated method stubs, zero instantiations in the
  entire codebase. Implements `HashSet<JobFactory>` but all interface methods are empty.
- **Action:** Delete the file. Verify no compile errors.

**2b. Remove `AlmostCache`**
- **File:** `graphpersist/src/main/java/io/almostrealism/persist/AlmostCache.java`
- **Evidence:** 30 lines, single method throws `RuntimeException("Not implemented")`.
  Never instantiated or referenced.
- **Action:** Delete the file. Verify no compile errors.

**2c. Audit physics auto-generated stubs**
- **Files:**
  - `domain/physics/src/main/java/org/almostrealism/physics/VolumetricDensityAbsorber.java`
  - `domain/physics/src/main/java/org/almostrealism/physics/SpanningTreeAbsorber.java`
  - `domain/physics/src/main/java/org/almostrealism/electrostatic/SpanningTreePotentialMap.java`
  - `domain/physics/src/main/java/org/almostrealism/electrostatic/PotentialMapHashSet.java`
  - `domain/physics/src/main/java/org/almostrealism/electrostatic/AtomicProtonCloud.java`
  - `domain/color/src/main/java/org/almostrealism/color/buffer/SpanningTreeColorBuffer.java`
- **Action:** For each file, verify whether any other code references it. If unreferenced,
  delete. If referenced, convert auto-generated stubs to proper `UnsupportedOperationException`
  throws with javadoc explaining the class's intended purpose and incomplete status.

### Phase 3: Triage TODO/FIXME Comments (Medium Priority)

Systematically categorize the 506 TODO/FIXME comments into:

**Category A — Actionable Bugs** (fix or file as known issues):
- TODOs that describe incorrect behavior (e.g., "This is probably broken")
- TODOs with "hack", "workaround", "temporary" language

**Category B — Meaningful Enhancements** (convert to proper javadoc):
- TODOs that describe real features or improvements worth tracking
- Convert these from inline comments to javadoc with `@todo` or clear design rationale

**Category C — Speculative Noise** (remove):
- IDE-generated "Auto-generated method stub" comments (replace with
  `throw new UnsupportedOperationException()`)
- Aspirational questions that have been stale for years
- Empty TODO comments with no description (e.g., `// TODO`)

**Priority focus areas for Phase 3:**
1. `graphpersist/` — 40+ TODOs in GraphFileSystem alone, mostly permission-check placeholders
2. `flowtreeapi/` — Auto-generated stubs in HybridJobFactory (removed in Phase 2)
3. `studio/compose/` — Mixed quality TODOs in audio health and streaming code
4. `base/hardware/` — Performance-related TODOs that may inform future optimization work

### Phase 4: Clean Up GraphFileSystem TODOs (Low Priority)

`GraphFileSystem.java` alone has 26 TODO comments. Most are permission-check placeholders
and unimplemented NFS operations. This file should either be:
- Properly implemented with the TODO items addressed, or
- Marked as experimental/incomplete with consolidated javadoc explaining its status,
  replacing the scattered TODO comments with a single class-level note.

---

## Approach

1. **Start with Phase 1** — fix the confirmed `ImageResource.clip()` bug and document the
   `CollectionVariable` generics issue. These are concrete, high-confidence improvements.

2. **Execute Phase 2** — delete confirmed dead code files, verifying compilation after each
   removal.

3. **Proceed through Phase 3** systematically by module, starting with the highest-TODO
   modules (`graphpersist`, `studio/compose`, `base/hardware`). For each TODO:
   - Determine the category (A/B/C)
   - Take the appropriate action (fix/document/remove)
   - Track statistics for the Manager Log

4. **Phase 4** only if time permits — the GraphFileSystem consolidation is lower priority
   since the module is peripheral.

5. **Verify** with `mvn clean install -DskipTests` after all changes.

---

## Success Criteria

1. `ImageResource.clip()` bug is fixed and the method works correctly
2. `HybridJobFactory` and `AlmostCache` are deleted; build still compiles
3. Physics/color auto-generated stub files are audited — deleted if unused, properly
   documented if used
4. At least 50 TODO/FIXME comments are triaged (categorized and acted on)
5. Zero new `// TODO Auto-generated method stub` comments remain in triaged files
6. `mvn clean install -DskipTests` passes
7. All changes follow the project's code quality rules (javadoc, no `@SuppressWarnings`,
   no `var`, no duplication)

---

## Dependencies

- None. This is standalone code quality work.
- The @TestDepth standardization work (prior cycle) is complete and merged.

---

## Connection to the Vision

This is cleanup work, but it's essential cleanup. Every dead code file, every stale TODO,
every known-but-unfixed bug is friction — friction for human developers, friction for AI
agents, and friction for the future models we hope to train on this codebase.

The Almost Realism platform aspires to be understood by AI systems. A codebase littered
with auto-generated stubs and years-old broken methods sends a false signal about the
platform's quality. After this cleanup, the codebase's signal-to-noise ratio improves
measurably, and the foundation for proof-of-value work becomes that much stronger.
