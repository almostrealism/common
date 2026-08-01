# macOS Pipeline Fixes

Tracker workstream `c486d041-2918-4457-b8cd-ce3c7902f718` /
branch `feature/macos-pipeline-fixes`.

This branch collects the smallest set of fixes required to get the macOS
build (Metal backend, aarch64) running cleanly through the common-side
pipeline so the desktop app release can ship on Mac. Each iteration
addresses one concrete failure surface; an investigation memo lives in
`docs/plans/macos-pipeline-fixes/<TOPIC>.md` for any iteration that
required non-trivial digging.

## Scope

In scope:
- Metal codegen fixes for issues that block compilation of generated
  kernels on macOS.
- Test failures on macOS that are caused by Metal-only behaviour (e.g.,
  missing `double` type, stricter overload resolution) and are not
  reproducible on other backends.
- FP32-vs-FP64 precision asymmetries that surface on Metal because Metal
  has no FP64, when a test was written assuming FP64-grade tolerance.

Out of scope:
- ringsdesktop pipeline issues. Those get their own workstream once the
  common-side base is stable.
- Discovery of new failures unrelated to the items below — those become
  separate tracker tasks.
- Refactoring of the codegen layer for reasons unrelated to the above.

## Iterations

Each iteration here tracks one tracker task. Investigation iterations
land a memo without production code changes (except brief
proof-of-concept prototypes when they help confirm a hypothesis); fix
iterations land code and a status update.

### 1. Metal compiler ambiguity in math function emission

Tracker task `29f26759-70c1-45af-8915-ba522a6bb40a`.

Metal's math intrinsics (`exp`, `floor`, `sin`, `cos`, `tan`, `tanh`,
`log`, `ceil`, `abs`/`fabs`, `pow`, `atan2`, `fmod`) overload only on
`half` and `float`; Metal has no `double`. C++ floating-point literals
default to `double`, so an emitted call like `exp(-0.9154551)` is rejected
with `call to 'exp' is ambiguous`. This blocks the macOS Metal pipeline
on `FundamentalMusicEmbeddingTest` and any other code that fuses a math
intrinsic over `double`-typed operands.

- **Investigation iteration (current):** memo at
  `docs/plans/macos-pipeline-fixes/MATH_FUNCTION_AMBIGUITY.md`.
- **Implementation iteration:** to be planned after the user reviews the
  investigation and chooses an approach.

### 2. `testInvFreqComputation` FP32 precision delta

Tracked separately within the same workstream once the math-intrinsic
ambiguity is resolved. Confirmed in the math-ambiguity investigation as
a *separate* root cause (FP32 vs FP64 representation of `0.1`, not a
codegen ambiguity), so it gets its own iteration.

## How to add a new iteration

1. Open or reference a tracker task for the failure surface.
2. Add a sub-section to "Iterations" linking the task and the
   investigation memo (or fix commit) location.
3. For investigation iterations, drop the memo at
   `docs/plans/macos-pipeline-fixes/<TOPIC>.md` so it ends up next to
   this index.
