# Loop-Invariant Hoisting Moves Loop-Variant Declarations

**Status:** confirmed bug, not fixed. Currently latent: it only changes results
when the hoisted declarations are actually substituted into the loop body.

## How it was found

A commit on `feature/cl-semaphore-issues` (`ff7f2bd2f`) changed
`Scope.processReplacements` to consider replacement targets in the order the
expression cache ranks them (by estimated savings) instead of `HashSet` order.
`MixdownManagerPdslVerificationTest.compareJavaAndPdslMixdownPaths` went from an
energy ratio of 2.2 to about 2,600 (on native, CL and Metal), and
`AudioSceneRealTimeCorrectnessTest.multiBufferWithEffects` produced silence on
Metal. Bisecting the branch identified that commit; restoring `HashSet` order for
targets (while keeping declaration-ordered application) restored both tests.

## What the generated code shows

Profiled with an `OperationProfileNode` around the PDSL render, native backend.
Kernel `f_loop_2198` ("Loop x11025", the CellList tick):

- Declarations produced by replacement inside a child assignment scope
  (`double f_assignment_1554_0` … `_11`, `f_assignment_2032_*`,
  `f_assignment_2034_*`) are emitted **before** `for (int _2198_i = 0; ...)`.
- Those expressions read `_v789[_v789Offset + 35]` (a clock incremented at the end
  of every iteration) and `_v789[_v789Offset + 142]` (filter state assigned inside
  the loop).
- With savings-ordered targets the loop body uses them, e.g.
  `_v789[154] = _v789[154] + f_assignment_1554_0;`, so every iteration adds a
  value computed from the state before the loop.
- With `HashSet`-ordered targets the same declarations are still emitted before
  the loop, but the loop statements keep the inline expressions (the inner targets
  were replaced first, so the outer ones no longer matched), and the stale
  declarations go unused.

## Where the hoisting happens

`Repeated.hoistLoopInvariantStatements` (Phase 3) moves child-scope declarations
whose names are not in the loop-variant set. Variance is name-based:
`collectBaseVariantNamesRecursive` records the destinations of non-declaration
assignments, and `Expression.isLoopInvariant` checks dependencies, indices and
references against those names. These declarations read the same buffer the loop
writes, yet were judged invariant.

Not yet established: why the analysis misses the write. One hypothesis to test is
aliasing — argument aggregation lets one buffer be reached through variables with
different names, so a write recorded under one name does not mark a read through
another as variant.

## Why it matters

Any change that makes replacement substitution more effective (a different target
order, a higher replacement cap, smarter matching) can silently corrupt loop
kernels that today only work because the hoisted declarations are unused. The
correct order of fixes is: make the variance analysis sound first, then revisit
target ordering by savings.
