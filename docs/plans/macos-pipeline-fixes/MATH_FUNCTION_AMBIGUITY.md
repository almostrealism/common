# Metal Compiler Ambiguity in Math Function Emission — Investigation (Re-do)

Tracker task `29f26759-70c1-45af-8915-ba522a6bb40a`.
Workstream `c486d041-2918-4457-b8cd-ce3c7902f718`,
branch `feature/macos-pipeline-fixes`.

This memo replaces the previous version. The previous investigation
asserted the failure was caused by "double-typed literals" without
capturing the generated source or the Metal compiler diagnostic. The
re-do below records the actual verbatim source and the actual Metal
error, then bases the analysis and fix proposal on that evidence.

## Phase 0 — Reversion

Commit reverted: `85cc1f12d3` — "Resolve Metal math intrinsic call
ambiguity by casting operands at codegen time."

That commit added `LanguageOperations.castForMathArgument(String)`
(no-op default) and an override in `MetalLanguageOperations` that wraps
operands in `(float)`/`(bfloat)`, then changed every math intrinsic
expression class (`Absolute`, `Atan2`, `Ceiling`, `Cosine`, `Exp`,
`Exponent`, `Floor`, `Logarithm`, `Mod`, `Sine`, `Tangent`) to route
its operand render through that hook.

The revert is staged in the working tree (and applied to the on-disk
sources). `git diff --cached` shows:

```
13 files changed, 13 insertions(+), 57 deletions(-)
```

Files touched by the revert are the thirteen `M` entries in
`git status` for `base/code/.../expression/*` and the two
`base/code/.../lang/LanguageOperations.java` plus
`base/hardware/.../metal/MetalLanguageOperations.java`.

### Failure confirmation on the reverted state

After installing the reverted base modules (`mvn install -pl
base/code,base/hardware -DskipTests`) so that `engine/ml`'s test run
actually links against the reverted bytecode (the `.m2` cache for
`ar-code`/`ar-hardware` had been populated by the previous job and
silently masked the revert until reinstalled), the failure reproduces:

Test run `adacefc0`, run config:

```
module: engine/ml
test_methods: [FundamentalMusicEmbeddingTest#testEmbedSequenceShape]
jvm_args: [-DAR_HARDWARE_DRIVER=mtl,
           -DAR_INSTRUCTION_SET_MONITORING=failed,
           -DAR_INSTRUCTION_SET_OUTPUT_DIR=/tmp/mtl-evidence]
```

Result: 1 test, 1 error.

```
org.almostrealism.hardware.HardwareException: Failed to compile f_collectionAddComputation_179
	at org.almostrealism.hardware.metal.MetalProgram.compile(MetalProgram.java:152)
	at org.almostrealism.hardware.metal.MetalOperatorMap.init(MetalOperatorMap.java:98)
	at org.almostrealism.hardware.metal.MetalOperatorMap.<init>(MetalOperatorMap.java:72)
	at org.almostrealism.hardware.metal.MetalComputeContext.deliver(MetalComputeContext.java:185)
	...
	at org.almostrealism.ml.midi.test.FundamentalMusicEmbeddingTest.testEmbedSequenceShape(FundamentalMusicEmbeddingTest.java:207)
```

The kernel number is `179`, not `212`; the counter is sequence-position
dependent and not deterministic across runs. The shape and origin of
the failure are the same as the one named in the task brief.

**One important platform note for any future reproduction**: the
default driver auto-selection on this machine picks OpenCL ("Using GPU 0
for kernels" from `CLDataContext`). The Metal compile error only
surfaces with `-DAR_HARDWARE_DRIVER=mtl`. Without that flag the test
will pass because the kernel is never handed to Metal.

## Phase 1 — Constant-folding verification

The user's hypothesis stated in the task brief was: "we already
constant-fold `floor(literal)` and `exp(literal)` at construction time,
so if the previous agent's 'only happens with literals' claim were
true, the bug would be impossible."

**The hypothesis is wrong.** Constant folding is NOT in place for
`Exp.of` or `Floor.of` in master.

Citations from the master-state files (before this iteration's Phase 1
fix below):

`base/code/src/main/java/io/almostrealism/expression/Exp.java:95-97`:

```java
public static <T> Expression<T> of(Expression input) {
    return (Expression<T>) new Exp(input);
}
```

No `doubleValue()` check; the factory always wraps in a fresh `Exp`.

`base/code/src/main/java/io/almostrealism/expression/Floor.java:96-98`:

```java
public static Expression of(Expression in) {
    return new Floor(in);
}
```

Same: no folding. `Floor#getExpression` at line 44–47 has a partial
inlining (if the operand's `doubleValue()` is present it inlines the
literal as the argument to `floor(...)`) but the surrounding
`floor(...)` call itself is still emitted — so the literal still
reaches Metal code emission.

For contrast, the sibling classes do fold:

`base/code/src/main/java/io/almostrealism/expression/Sine.java:88-96`:

```java
public static Expression<Double> of(Expression<Double> input) {
    OptionalDouble d = input.doubleValue();
    if (d.isPresent()) {
        return new DoubleConstant(Math.sin(d.getAsDouble()));
    }
    return new Sine(input);
}
```

`Cosine.of` (`Cosine.java:88-93`), `Tangent.of` (`Tangent.java:102-107`),
and `Atan2.of` (`Atan2.java:113-118`) all do the same. Logically there
is no reason `Exp.of` and `Floor.of` would not — it appears to be a
plain oversight.

### Phase 1 fix

Constant folding added to `Exp.of` and `Floor.of` in this iteration:

`base/code/src/main/java/io/almostrealism/expression/Exp.java:95-104`:

```java
public static <T> Expression<T> of(Expression input) {
    OptionalDouble d = input.doubleValue();
    if (d.isPresent()) {
        return (Expression<T>) new DoubleConstant(Math.exp(d.getAsDouble()));
    }
    return (Expression<T>) new Exp(input);
}
```

`base/code/src/main/java/io/almostrealism/expression/Floor.java:96-104`:

```java
public static Expression of(Expression in) {
    OptionalDouble d = in.doubleValue();
    if (d.isPresent()) {
        return new DoubleConstant(Math.floor(d.getAsDouble()));
    }
    return new Floor(in);
}
```

### Empirical outcome of the Phase 1 fix

The task brief predicted: "This will almost certainly NOT resolve the
test failures (the actual bug is elsewhere)."

**That prediction is empirically wrong.** With Phase 1 in place,
`FundamentalMusicEmbeddingTest.testEmbedSequenceShape` PASSES, and the
full `FundamentalMusicEmbeddingTest` class result is 12 tests, 11
passed, 1 failed (the remaining failure is `testInvFreqComputation`,
which is the FP32 precision case discussed in §5 — not a compile
ambiguity).

The reason this resolves the failure is captured in §2 below and is
the load-bearing finding of this re-do: **every ambiguous call in the
captured Metal source has a literal operand**. There are no
ambiguous calls with non-literal operands. Folding `Exp.of` at
construction time removes every literal-operand `exp(...)` call before
it reaches code emission, and (per §4) there is no codegen path that
re-introduces an `exp(literal)` later.

### Gaps NOT closed by this iteration

`Absolute`, `Ceiling`, and `Logarithm` also lack constant folding in
their factories (`Absolute.java` has no static `of`; `Ceiling.java`
has no static `of`; `Logarithm.java:87` only folds the
`log(exp(x)) = x` algebraic identity). These are not exercised by the
failing tests and are left alone; they should be tracked as a follow-up
if any failing kernel ever surfaces an `abs(literal)`, `ceil(literal)`,
or `log(literal)` call.

## Phase 2 — Captured failure (verbatim)

### (A) The generated Metal source

Dumped from `MetalProgram.recordInstructionSet()` when
`enableFailedInstructionSetMonitoring=true`
(via `-DAR_INSTRUCTION_SET_MONITORING=failed`). File:
`/tmp/mtl-evidence/mtl_instruction_set_1.c`. 41 lines total. The kernel
function declaration is on line 16; the body assigns 12 named locals
plus one giant fused expression on line 39 (646,702 characters long).

Lines 1–18 (preamble and kernel signature start):

```
#include <metal_stdlib>
using metal::min;
using metal::max;
using metal::fmod;
using metal::floor;
using metal::ceil;
using metal::abs;
using metal::pow;
using metal::exp;
using metal::log;
using metal::sin;
using metal::cos;
using metal::tan;
using metal::tanh;

[[kernel]] void f_collectionAddComputation_179(device float *_114_v197 [[buffer(0)]], device float *_16_v1 [[buffer(1)]], device float *_179_v298 [[buffer(2)]], device float *_18_v7 [[buffer(3)]], dev...
int _114_v197Offset = (int) offsetArr[0];
int _16_v1Offset = (int) offsetArr[1];
```

All buffers are declared `device float *`. Line 39 begins
`_179_v298[((long)global_id) + _179_v298Offset] = ...` and runs to a
closing `;`. The compile errors all target column positions on this
one line, columns 637,954 through 646,333.

Verbatim excerpt from line 39 centered on the first error (column
637,954 in the original):

```
(((((((((long)global_id) - 48) % 48) + 48) % 48) < 8) ? ((_16_v1[(((((((((((long)global_id) - 48) / 48) * 8) + ((((((long)global_id) - 48) % 48) + 48) % 48)) * 8) + 1) % 64) + 64) % 64) + _16_v1Offset + 169] * (cos((_16_v1[_16_v1Offset + 168] + 100.0) * exp(0)))) + (_16_v1[(((((((((((long)global_id) - 48) / 48) * 8) + ((((((long)global_id) - 48) % 48) + 48) % 48)) * 8) + 2) % 64) + 64) % 64) + _16_v1Offset + 169] * (sin((_16_v1[_16_v1Offset + 168] + 100.0) * exp(-3.051517)))) + (_16_v1[(((((((((((long)global_id) - 48) / 48) * 8) + ((((((long)global_id) - 48) % 48) + 48) % 48)) * 8) + 4) % 64) + 64) % 64) + _16_v1Offset + 169] * (sin((_16_v1[_16_v1Offset + 168] + 100.0) * exp(-6.103034)))) + (_16_v1[(((((((((((long)global_id) - 48) / 48) * 8) + ((((((long)global_id) - 48) % 48) + 48) % 48)) * 8) + 5) % 64) + 64) % 64) + _16_v1Offset + 169] * (cos((_16_v1[_16_v1Offset + 168] + 100.0) * exp(-6.103034)))) + (_16_v1[(((((((((((long)global_id) - 48) / 48) * 8) + ((((((long)global_id) - 48) %
```

The ambiguous calls are the four `exp(...)` calls in this excerpt:
`exp(0)`, `exp(-3.051517)`, `exp(-6.103034)`, and another
`exp(-6.103034)`. Each takes a **pure literal** as the sole argument.
The pattern continues through the rest of line 39 — 10 such `exp()`
calls in total (matching the 10 error rows in §B).

`grep -o "exp([^)]*)" mtl_instruction_set_1.c | head -10` returns:

```
exp(0)
exp(-3.051517)
exp(-6.103034)
exp(-6.103034)
exp(-3.051517)
exp(-9.154551)
exp(-9.154551)
exp(0)
exp(-3.051517)
exp(-6.103034)
```

### (B) The Metal compiler diagnostic

Captured from the Maven surefire stdout for run `adacefc0`. The native
JNI side of `MTL.createFunction` (`base/hardware/src/main/cpp/MTL.cpp:137`)
printfs the localized description of the `NS::Error*` returned by
`device->newLibrary(...)`; this is verbatim:

```
Error: program_source:39:637954: error: call to 'exp' is ambiguous
program_source:39:639242: error: call to 'exp' is ambiguous
program_source:39:639790: error: call to 'exp' is ambiguous
program_source:39:641113: error: call to 'exp' is ambiguous
program_source:39:641712: error: call to 'exp' is ambiguous
program_source:39:643260: error: call to 'exp' is ambiguous
program_source:39:643672: error: call to 'exp' is ambiguous
program_source:39:644517: error: call to 'exp' is ambiguous
program_source:39:645499: error: call to 'exp' is ambiguous
program_source:39:646333: error: call to 'exp' is ambiguous
```

The localized description does not include the "candidate function"
notes that a typical clang error would carry — Metal's
`newLibrary(...:options:error:)` returns only the top-line summary in
`localizedDescription`. That is a tooling limitation of the Metal
runtime; the columns map cleanly onto the 10 `exp(LITERAL)` calls
above.

The Java side wraps this as `HardwareException("Failed to compile " +
func)` (`MetalProgram.java:152`), which is what the test sees.

## Phase 3 — Analysis (grounded in the captured evidence)

**Which line, which call, which operand.** All ten failing calls are
on line 39 of the generated Metal source. Every one is an `exp(...)`
call with a single literal argument:

- One `exp(0)` — the integer literal `0`, emitted by
  `IntegerConstant.getExpression` via `Precision.stringForInt(0)`
  (`Precision.java:127-133`), which returns `String.valueOf(0)` =
  `"0"`. There is no `f` suffix and no cast.
- Several `exp(<double literal>)` calls: `exp(-3.051517)`,
  `exp(-6.103034)`, `exp(-9.154551)`. These are emitted by
  `DoubleConstant.getExpression` via `Precision.stringForDouble`
  (`Precision.java:158-168`), which delegates to `rawStringForDouble`
  (`Precision.java:180-200`). For FP32 it returns
  `String.valueOf((float) d)` — Java's `Float.toString`, which never
  appends an `f` suffix. The MSL-side dialect treats the bare literal
  as a `double` literal.

**Why Metal reports the call as ambiguous.** Metal Shading Language
has no `double` type. The standard library's transcendental
overloads (`metal::exp`, `metal::floor`, `metal::sin`, ...) are
provided for `half` and `float` (and the corresponding vector types),
not for `double`. When a literal that the compiler types as `double`
is passed to such a function, both the `float` and the `half` overload
require an implicit conversion of equal rank from `double`, and the
compiler cannot choose between them. The integer-literal case
(`exp(0)`) is the same phenomenon: `int -> half` and `int -> float`
are both standard conversions of equal rank.

**Why only `exp(...)` is flagged, not the surrounding `cos(...)` and
`sin(...)`.** The captured source contains many `cos(...)` and
`sin(...)` calls whose innermost arguments include literal-looking
text (e.g., `cos((_16_v1[_16_v1Offset + 168] + 100.0) * exp(0))`). The
compiler does NOT flag those. The reason is that those calls take a
sub-expression that involves a typed lvalue. `_16_v1` is declared
`device float *`, so `_16_v1[i]` is `float`. The expression
`_16_v1[i] + 100.0` is typed by the compiler with `_16_v1[i]`'s type
(Metal's dialect implicitly treats unsuffixed floating-point literals
inside a typed binary expression as `float`, not as `double`), so the
whole argument resolves as `float` and `metal::cos(float)` is picked
unambiguously. Only the **bare-literal** case has no surrounding
typed context to anchor the conversion, and that is when the
ambiguity strikes.

**Where the literals come from in the source kernel.** The kernel
implements `FundamentalMusicEmbedding.encodeSinusoidal` for
`FundamentalMusicEmbeddingTest`'s test config. `encodeSinusoidal`
(`FundamentalMusicEmbedding.java:137-149`) multiplies `invFreqs` by a
biased value and applies `sin/cos`. `invFreqs` itself is
`integers(0, dim/2).multiply(c).exp()` where
`c = -2 * Math.log(base) / dim`
(`FundamentalMusicEmbedding.java:183-185`). For the test config base
and `dim`, the simplification system unrolls
`integers(0, dim/2)` and propagates the integer constants through the
multiplication, yielding `Exp(0)`, `Exp(-3.051517...)`, etc. — `Exp`
expressions whose only child is a `Constant`. Because `Exp.of` did
NOT fold (Phase 1), those expressions survive into codegen and render
as `exp(LITERAL)`.

## Phase 4 — Recommended fix

The Phase 1 constant-folding fix landed in this iteration is, on the
captured evidence, the right structural fix:

1. It closes the only path by which a literal can reach
   `exp(...)`/`floor(...)` codegen.
2. It is symmetric with the already-existing folding in `Sine.of`,
   `Cosine.of`, `Tangent.of`, `Atan2.of`. The lack of folding in
   `Exp.of` and `Floor.of` is, as far as the code suggests, an
   oversight rather than a deliberate choice — there is no algebraic
   reason a literal `exp` couldn't be evaluated eagerly when the other
   transcendentals are.
3. It is detection-based at the highest possible level: detected at
   construction, before the rest of the simplification pipeline ever
   runs. Detection cost is one `doubleValue()` call, identical to the
   sibling factories.
4. It does NOT widen the surface area of `LanguageOperations` or
   `MetalLanguageOperations` with a new string-mangling hook, which is
   what the reverted commit had to do.

The detection method we used is `Expression.doubleValue()`
(returns `OptionalDouble`). `DoubleConstant.doubleValue()` returns
`OptionalDouble.of(value)`, `IntegerConstant.doubleValue()` (via the
shared default path) returns `OptionalDouble.of((double) value)`, and
non-constant subtrees return `OptionalDouble.empty()`. This is the
exact distinction we want: only when the operand is reducible to a
known scalar at construction time should we fold.

### Why the always-cast fallback is unnecessary

The reverted commit `85cc1f12d3` was an always-cast — every operand of
every math intrinsic gets `(float)` (or `(bfloat)`) wrapped around its
rendered string on the Metal path, unconditionally. The captured
evidence shows this is overkill:

- The only failing cases are the bare-literal ones.
- Once those are folded away at construction, no remaining call site
  triggers the ambiguity (the §2 capture confirms there are zero
  ambiguous `cos`/`sin`/`tan` calls, despite many of them containing
  literals inside their sub-expressions, because the sub-expressions
  are typed by surrounding lvalues).

An always-cast also has a small cost: it complicates the generated
code, inhibits some constant-pooling that the Metal compiler would
otherwise do on the bare-literal cases, and burns one optimization
opportunity (a folded constant operand to `exp` would simply emit a
folded constant, with no cast and no call). The folding fix is
strictly better on every axis except one: it relies on
`doubleValue()` reporting accurately. The sibling intrinsics already
depend on `doubleValue()` reporting accurately and there is no
evidence of a regression.

### Why detection-based casting at codegen time is not necessary either

A second candidate fix would be: at codegen time in each math
intrinsic's `getExpression`, check
`getChildren().get(0).doubleValue().isPresent()` and, if so, wrap the
emitted literal in `(float)`. This works, but it is strictly inferior
to constant-folding because:

- It still emits a `exp(...)` call at runtime when the operand is
  known.
- It widens the responsibility surface of `getExpression` to know
  about per-backend quirks (it can't be implemented without checking
  the precision via `lang.getPrecision()` or similar), whereas the
  Phase 1 fix is language-agnostic.

The fallback would only become necessary if a future kernel
synthesised an `Exp` or `Floor` expression AFTER the
`Expression.of(...)` factory had already been called — i.e., a
late-stage simplification that produces an `Exp(Constant)` not via
`Exp.of`. There is currently no such code path: `Exp(Constant)`
nodes can only come from a `new Exp(...)` constructor call, and
every such call in `base/code` goes through `Exp.of` (verified by
grep). If someone later writes a `new Exp(literal)` directly, the
Phase 1 fix would not catch it; we accept that minor risk on the
basis that the failure mode is loud (Metal compile error) and easy
to diagnose.

### Recommendation

Keep the Phase 1 fix (`Exp.of` and `Floor.of` fold constants at
construction). Do not also apply the always-cast layer that was on
the branch as commit `85cc1f12d3`. Track `Absolute`, `Ceiling`, and
`Logarithm` as the "next gap if a failure surfaces"; do not fix them
pre-emptively here because the captured evidence does not exercise
them.

## Phase 5 — Precision delta in `testInvFreqComputation`

`testInvFreqComputation`
(`engine/ml/src/test/java/org/almostrealism/ml/midi/test/FundamentalMusicEmbeddingTest.java:90-103`):

```java
public void testInvFreqComputation() {
    double base = 10000.0;
    int dim = 8;
    FundamentalMusicEmbedding fme = new FundamentalMusicEmbedding(base, dim);
    PackedCollection invFreqs = fme.computeInvFreqs(base, dim).get().evaluate();
    ...
    double expected1 = 1.0 / Math.pow(base, 2.0 / dim);
    assertEquals("invFreq[1]", expected1, invFreqs.toDouble(1), 1e-10);
}
```

Expected value: `1.0 / Math.pow(10000, 0.25)` = `0.1` (exact in
double).

Observed value with the Phase 1 fix applied: `0.09999999403953552`
(captured in run `698fcda7` failure trace).

This is roughly `0.1 - 5.96e-9`. The assertion tolerance is `1e-10`,
so the failure is by a factor of ~60.

**This is NOT the same root cause as the Phase 0–4 compile-time
ambiguity.** The compile-time ambiguity is overload resolution in
codegen. The precision delta is FP32 storage and FP32 arithmetic
inside the Metal device.

`MetalDataContext.getPrecision()` (`MetalDataContext.java:178`)
returns `Precision.FP32` by default (unless
`AR_HARDWARE_PRECISION=FP16`). All `MetalMemoryProvider` allocations
are in `getPrecision().bytes()` (i.e., 4 bytes per element). The
`PackedCollection` returned from `evaluate()` is therefore backed by
FP32 memory. `invFreqs.toDouble(1)` reads element 1 as a float and
widens to a double — and the FP32 representation of any value close
to `0.1` is `0x3DCCCCCD` = `0.10000000149011612` in double, or, after
some FP32 arithmetic intermediate steps, a slightly-different nearby
representable float.

Concretely: the kernel that computes `exp(i * c)` for
`i = 0, 1, 2, 3` performs the `i * c` and the `exp` in FP32 on the
GPU. `c` in the source is `(float)(-2.0 * Math.log(10000) / 8) =
-2.3025851f`. Metal's FP32 `exp(-2.3025851f)` is a representable
float close to but not equal to `0.1`. With the Phase 1 fix in place,
`Exp.of` would fold `Math.exp(c)` only if `c` itself was reducible at
construction time; for this kernel the kernel-index multiplication
`integers(0, dim/2).multiply(c)` is NOT unrolled into per-element
constants (different code path than `testEmbedSequenceShape`), so the
`exp` call is left in the runtime kernel and Metal evaluates it in
FP32 at execute time. The 5.96e-9 delta is what comes out.

The Phase 1 fix therefore does not move `testInvFreqComputation`. The
delta is independent and unrelated.

The right resolution for `testInvFreqComputation` is one of:

1. Widen the assertion tolerance to something compatible with FP32
   (e.g., `1e-6` instead of `1e-10`) — this is the simplest and
   correct response, since the runtime is FP32 and the test cannot
   reasonably demand FP64-grade precision from a FP32 backend.
2. Recognise the `integers(0, n).multiply(c)` pattern in
   `ArithmeticSequenceComputation` (or wherever the kernel is
   emitted) and unroll it into per-element constants at construction
   so that `Exp.of` can fold. This is more invasive and overlaps with
   the `testEmbedSequenceShape` unrolled path; it would change the
   runtime arithmetic semantics on the FP32 backend and is not what
   this task asks for.

The right resolution is (1), and that is a test concern, not a
codegen concern. It should be tracked as a separate, smaller task.
The reading of the prior memo that this was a codegen bug is
incorrect.

## Working-tree state at the end of this iteration

- `git status` shows 13 staged files: the revert of `85cc1f12d3`.
- `git status` shows 2 unstaged files:
  - `base/code/src/main/java/io/almostrealism/expression/Exp.java`
    — Phase 1 constant-folding for `Exp.of`.
  - `base/code/src/main/java/io/almostrealism/expression/Floor.java`
    — Phase 1 constant-folding for `Floor.of`.
- This memo (`docs/plans/macos-pipeline-fixes/MATH_FUNCTION_AMBIGUITY.md`)
  is rewritten end-to-end.

Net behavioural change vs. the master commit before the workstream
branched: only `Exp.of` and `Floor.of` are different — they now fold
constants. All `LanguageOperations`, `MetalLanguageOperations`, and
the other math expression classes are at master state.

## What this re-do should change about the previous memo

The previous memo (replaced by this one) made several claims that the
captured evidence contradicts:

- "Java-style floating-point literals (e.g. `-0.9154551`) are emitted
  by `DoubleConstant.getExpression()` via
  `Precision.stringForDouble()` which uses `Float.toString` (no `f`
  suffix). C++ literal rules treat these as `double`, so Metal cannot
  pick between the `float` and `half` overloads → 'call to 'exp' is
  ambiguous'." — The mechanism part of this claim is correct (and the
  captured source confirms the no-`f`-suffix rendering); the
  evidence-free assertion about the fix path is what was wrong. The
  previous memo concluded the fix had to live in `LanguageOperations`;
  the captured source shows the only failing calls are literal-operand
  ones, and folding at the Expression level is sufficient.
- "Smart-detection trade-off: the Java type system says all math
  operands are Double, but rendered code may still produce C++ double
  literals that Metal cannot disambiguate. Smart detection that
  examines each rendered sub-expression would require parsing
  strings..." — This was the speculative justification for the
  always-cast fallback. The captured source shows non-literal
  operands never trigger the ambiguity, so "smart detection" can be
  done at the Expression level (`doubleValue().isPresent()`) with no
  string parsing.
- "11/12 pass [after the always-cast fix]; only `testInvFreqComputation`
  still fails." — The 11/12 result is preserved by the Phase 1
  constant-folding fix alone, with no always-cast. The always-cast
  was unnecessary work.
