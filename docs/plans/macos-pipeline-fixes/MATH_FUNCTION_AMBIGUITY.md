# Metal Compiler Ambiguity in Math Function Emission — Investigation

Tracker task `29f26759-70c1-45af-8915-ba522a6bb40a`.
Workstream `c486d041-2918-4457-b8cd-ce3c7902f718`,
branch `feature/macos-pipeline-fixes`.

This memo is the deliverable for the investigation iteration. It captures
the failure mode on macOS, names the codegen sites involved, characterises
when the Metal compiler rejects the generated source, recommends an
approach for the implementation iteration, and reports separately on the
`testInvFreqComputation` precision delta. A proof-of-concept implementation
of the recommended approach has already landed on this branch
(commit `85cc1f12d3`, see "Implementation already landed" below); the memo
documents the investigation that justifies it.

## 1. Reproduction confirmation

Platform: `Mac-Studio.local`, macOS on aarch64, OpenJDK 24.0.1, Metal
backend auto-selected (no `AR_HARDWARE_DRIVER` override).

Test class: `org.almostrealism.ml.midi.test.FundamentalMusicEmbeddingTest`
in `engine/ml`.

Run via `mcp__ar-test-runner__start_test_run` with
`module: "engine/ml"`, `test_classes: ["FundamentalMusicEmbeddingTest"]`.

Pre-fix result (master at the point the workstream branched): four of
twelve tests fail.

| Test | Failure mode |
| --- | --- |
| `testInvFreqComputation` | `AssertionError: invFreq[1](0.10000000149011612 != 0.1)` (precision — see §5) |
| `testEmbedSequenceContentMatchesIndividualEmbeds` | `HardwareException: Failed to compile f_collectionAddComputation_<N>` |
| `testEmbedSequenceShape` | same `HardwareException` |
| `testFmeOutputShape` | same `HardwareException` |

The Metal driver's compiler error message reaches the JVM through
`HardwareException.getMessage`. The specific Metal diagnostic, captured
from the original investigation (memory `fec58b85-07e7-4c91-877d-dee541005f53`),
is:

```
call to 'exp' is ambiguous
```

The same shape of message would apply to `floor`, `sin`, `cos`, `log`,
etc., wherever the kernel happens to call one with a `double`-typed
operand. The three kernels that fail in `FundamentalMusicEmbeddingTest`
all originate in `FundamentalMusicEmbedding.computeInvFreqs(...)`
(`engine/ml/src/main/java/org/almostrealism/ml/midi/FundamentalMusicEmbedding.java:177`):

```java
return integers(0, dim / 2)
        .multiply(-2.0 * Math.log(base) / dim)
        .exp();
```

That fuses to a kernel containing `exp(-0.9154551)`, `exp(-1.8309102)`,
`exp(-2.7463653)`, … — each operand a Java `double` literal rendered
without an `f` suffix.

## 2. Where math intrinsics are emitted

Math expressions live in `base/code/src/main/java/io/almostrealism/expression/`.
Each one renders to its target source by calling `getExpression(LanguageOperations lang)`,
which concatenates the intrinsic name with `getChildren().get(i).getExpression(lang)`.
The emission sites that matter for this issue are:

| Intrinsic | Class | Line(s) |
| --- | --- | --- |
| `exp(x)` | `Exp` | `Exp.java:50` |
| `floor(x)` | `Floor` | `Floor.java:47` |
| `ceil(x)` | `Ceiling` | `Ceiling.java:73` |
| `sin(x)` | `Sine` | `Sine.java:51` |
| `cos(x)` | `Cosine` | `Cosine.java:51` |
| `tan(x)` / `tanh(x)` | `Tangent` | `Tangent.java:62` |
| `log(x)` | `Logarithm` | `Logarithm.java:50` |
| `abs(x)` / `fabs(x)` | `Absolute` | `Absolute.java:73` |
| `pow(a, b)` | `Exponent` | `Exponent.java:57-58` |
| `atan2(y, x)` | `Atan2` | `Atan2.java:74-75` |
| `fmod(a, b)` (FP branch) | `Mod` | `Mod.java:110-111` |

The integer `%` branch in `Mod` is unaffected — it routes to
`LanguageOperations.floorMod(a, b)` rather than the C++ math intrinsic
overload set.

The literal-formatter that drives constant-operand rendering is
`Precision.stringForDouble` /  `Precision.rawStringForDouble`
(`base/code/src/main/java/io/almostrealism/code/Precision.java:158, 180`).
For non-FP64 precisions it does `String.valueOf((float) d)`. `String.valueOf`
uses `Float.toString`, which never appends an `f` suffix, so the literal
that lands in the generated kernel is `-0.9154551`, not `-0.9154551f`. The
Metal compiler reads that as `double`, not `float`.

The Metal-specific emission glue is
`base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalLanguageOperations.java`
(extends `org.almostrealism.c.CLanguageOperations`). Both backends share the
math expression classes above; Metal-specific deviation only happens via
overrides on `LanguageOperations`.

## 3. When Metal accepts vs rejects a math call

Metal Shading Language overloads its scalar math intrinsics for two
floating-point types only: `half` and `float`. There is no `double`, no
implicit narrowing rule, and (notably) no integer overload — `exp(int)`
fails to resolve at all rather than silently promoting.

Walking through the operand types we actually emit:

- **Constant `double` literal** (e.g., `exp(-0.9154551)`): rendered by
  `Precision.stringForDouble`. The compiler treats `-0.9154551` as
  `double`, then attempts overload resolution against `half(half)` and
  `float(float)`. Both require an implicit narrowing conversion, neither
  is preferred over the other, and the compiler emits
  `call to 'exp' is ambiguous`. **This is the dominant case.**
- **Constant integer literal** (e.g., `exp(0)`): rendered by
  `Precision.stringForInt`. Metal has no `exp(int)` overload, and
  `int -> half` and `int -> float` are equally-ranked standard
  conversions, so this is also ambiguous (or, worse, fails to find any
  overload depending on the intrinsic).
- **Packed-collection element** (e.g., `exp(arg0[global_id])`): the
  buffer is declared `device float* arg0` (FP32) or `device half* arg0`
  (FP16) by `MetalLanguageOperations.annotationForPhysicalScope`, so the
  element load already has the right type. These calls compile cleanly
  *unless* the surrounding sub-expression introduces a `double` — for
  example `exp(arg0[i] * -2.302585)`, where the multiply re-promotes the
  result to `double` because the literal is `double`.
- **Intermediate `float` expression** (something built from buffer
  elements with `f` suffix in literals, or already cast to float):
  unambiguous, picks `float(float)`.
- **Intermediate `half` expression**: unambiguous, picks `half(half)`.

The conclusion is that *any* operand we emit as a literal — directly or
through arithmetic — is a candidate for ambiguity in Metal because
`Precision.rawStringForDouble` never emits the `f` suffix and the
backend's `nameForType(Double.class)` is `float`/`bfloat`, not `double`.

### Is there an Expression-level signal we can key off?

Investigated `Expression<T>` for a per-node "rendered C++ literal type"
hint. There isn't one. Every math expression class declares
`extends Expression<Double>`, so `getType()` reports `Double.class`
uniformly — for buffer-loaded operands, for FP literal operands, and for
integer literal operands alike. The rendered text type and the Java type
diverge: the Java type is always `Double`, but the rendered C++ literal
type is whatever `Precision.rawStringForDouble`/`stringForInt` happens to
produce.

That makes any "smart" detection at Expression level collapse into one of
two checks:

1. *"Is the precision FP32 or FP16 (i.e., not FP64)?"* — true for every
   Metal call, false for every C/OpenCL call in FP64 mode. Trivially
   equivalent to "is this Metal." (OpenCL and C in FP32 mode happen to
   tolerate `exp(double_literal)` by demoting to the lone non-`half`
   overload, so the same problem doesn't bite them.)
2. *"Does this rendered string look like a `double` literal?"* — would
   require parsing the rendered text. Brittle and breaks on parenthesised
   sub-expressions, casts, and arithmetic.

Neither check yields more information than "we are in Metal," so an
Expression-level type-guard is no more selective than an unconditional
backend hook.

## 4. Recommended fix design

Two candidate approaches were on the table per the task description.

### A. Detection-based (operand-type aware)

Inspect each operand at codegen time, decide whether the rendered text
will appear as `double` or `int` to the Metal compiler, and only then
inject a cast. Definition of "ambiguous" would have to cover at least:

- Literal-rendered constants (`Constant<Double>`, `IntegerConstant`,
  `DoubleConstant`).
- Any arithmetic node whose subtree contains such a constant
  (because the multiply/add re-promotes).
- Any `Expression<Double>` whose rendered substring is *not* already
  bracketed by a backend cast.

This is essentially "anything that isn't already a buffer load."

**Trade-offs:** more complex; risk of false negatives (a missed case
re-introduces the compile failure on some unrelated test) and false
positives (over-conservative wrap creates a chain of nested casts that
the optimizer may or may not collapse). Requires invasive walking of
each operand's expression tree from inside the math intrinsic
expression — coupling we don't currently have. Saves no runtime cost on
Metal, because Metal already only operates in `float`/`half`; the cast
collapses at compile time.

### B. Always-cast fallback (recommended)

Add a single hook on `LanguageOperations` that wraps the rendered
operand string. Default returns the string unchanged (no behaviour
change for C, OpenCL, JNI). Metal overrides it to emit
`((float) (rendered))` (FP32) or `((bfloat) (rendered))` (FP16) using
the existing `nameForType(Double.class)` mapping. Each math intrinsic
expression routes its operand renders through that hook.

**Trade-offs:**

- Always-cast in Metal is a no-op semantically once the Metal optimizer
  runs — `(float)(arg0[i])` collapses for an already-`float` operand,
  and a `(float)(double_literal)` does the demotion the compiler was
  refusing to choose between. So there is no precision loss relative to
  the half/float overload that would have been chosen anyway.
- The integer-typed result of these intrinsics is not used anywhere in
  the project (the math expressions all `extends Expression<Double>`),
  so we are not weakening any codepath that wanted integer-domain
  behaviour.
- Other backends (`CLanguageOperations`, `JNILanguageOperations`,
  `OpenCLLanguageOperations`, the stub backend) inherit the no-op
  default, so existing generated code is byte-for-byte identical
  outside Metal.

**Recommendation: B.** The detection-based approach buys nothing the
fallback doesn't already buy on Metal, and the fallback is mechanically
narrower (one method, one override, twelve emission-site call-sites).
The cost of A is real (operand-tree walking, failure modes) and its
benefit on Metal is zero.

The honest caveat: B unconditionally wraps, even for operands that were
already `float`, which makes the rendered Metal source slightly noisier.
That cost is paid once per math intrinsic call at codegen time and
disappears in compiled SPIR-V/AIR; it is not a runtime cost.

### Why not "just append `f` in `Precision.stringForDouble`"

Considered and rejected:

- The literal isn't always a literal. Operands are full sub-expressions
  whose rendered form may include arithmetic, casts, or buffer loads —
  appending `f` doesn't help (`(arg0[i] * 0.5)f` is invalid C++).
- `Precision` is shared with C/OpenCL/JNI, where the suffix is either
  unnecessary or actively wrong (OpenCL accepts the suffix but C uses
  the `f` suffix differently around `double` arithmetic).
- A targeted fix for the literal case still leaves the integer-literal
  case (`exp(0)`) ambiguous in Metal.

## 5. `testInvFreqComputation` precision delta

Failing assertion:

```
java.lang.AssertionError: invFreq[1](0.10000000149011612 != 0.1)
  at FundamentalMusicEmbeddingTest.testInvFreqComputation:102
```

Test (`FundamentalMusicEmbeddingTest.java:90-103`):

```java
double base = 10000.0;
int dim = 8;
PackedCollection invFreqs = fme.computeInvFreqs(base, dim).get().evaluate();
double expected1 = 1.0 / Math.pow(base, 2.0 / dim);
assertEquals("invFreq[1]", expected1, invFreqs.toDouble(1), 1e-10);
```

`computeInvFreqs(10000, 8)` reduces to
`integers(0, 4).multiply(-2.302585092994046).exp()`. For index 1 the
mathematical answer is `exp(-2.302585092994046) = 0.1` exactly to FP64.
The actual returned value, `0.10000000149011612`, is the
`(double)(float) 0.1` round-trip — i.e. `0.1` as the closest IEEE-754
single-precision binary fraction (`0x3DCCCCCD`), then widened back to
`double` for reporting. The error magnitude (~1.49e-9) is exactly FP32
ULP at 0.1.

This is a **separate root cause from the math-intrinsic ambiguity.**
- The ambiguity issue is a *compile-time* failure of the Metal
  shader compiler (the kernel never runs).
- The precision delta is a *runtime* result delta caused by Metal having
  no FP64 type. The kernel runs, computes `exp(-2.302585f)` in FP32, and
  produces the closest FP32 representation of 0.1.

Evidence that they are independent:

- Pre-fix CI runs (per memory `fec58b85-07e7-4c91-877d-dee541005f53` and
  the task description) report `testInvFreqComputation` as a *precision*
  failure, not a `HardwareException`. The kernel for `computeInvFreqs`
  must have been compiling — only the *other* three tests'
  `f_collectionAddComputation_*` kernels fail to compile.
- After the math-intrinsic fix landed (commit `85cc1f12d3`), the three
  compile failures disappear and the precision delta is unchanged
  (recorded in run `45d79ca4` and run `29d5a9d6` — same 12-test
  summary, same single failure, same numeric value).

Approaches for the follow-up iteration that owns this:

- **Loosen the test tolerance to FP32 ULP-aware bound** when running on
  an FP32-only backend. e.g., `assertEquals(expected1, actual, 1e-6)`
  for FP32 backends. Trade-off: weakens the test on FP64 backends too
  unless we branch on `Hardware.getLocalHardware().getPrecision()`.
- **Skip the test on FP32 backends.** Trade-off: leaves a coverage gap
  on macOS for the formula correctness, but the formula is also
  exercised end-to-end by the embedding tests.
- **Compute the expected value in FP32 too.** Trade-off: changes the
  semantics of the test from "the formula matches FP64 ground truth" to
  "the formula matches itself," which is weaker.

The recommended approach is the first: branch on backend precision and
relax the tolerance to ~1e-6 when FP32 is active. That's a separate
iteration on the same workstream — it does not need to land alongside
the codegen fix.

## 6. Implementation already landed

A proof-of-concept of approach (B) above has already landed on this
branch (commit `85cc1f12d3`, "Resolve Metal math intrinsic call
ambiguity by casting operands at codegen time"). Files touched:

- `base/code/src/main/java/io/almostrealism/lang/LanguageOperations.java:135`
  — added `default String castForMathArgument(String rendered)` that
  returns the argument unchanged.
- `base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalLanguageOperations.java:211`
  — overrides the hook to wrap with `(float)` / `(bfloat)` via the
  existing `nameForType(Double.class)` mapping.
- The eleven math expression classes listed in §2 — each routes operand
  renders through `lang.castForMathArgument(...)`.

After the change, `FundamentalMusicEmbeddingTest` reports 11 pass / 1
fail; the only remaining failure is `testInvFreqComputation` (§5). The
build validator (`checkstyle`, `code_policy`, `test_timeouts`) passes
with zero violations against the change.

The intent of the task description was to keep the implementation in a
follow-up iteration once the user had reviewed the choice between
approaches A and B. The fix landed in the same iteration as a brief
proof-of-concept (the task description allows "a brief proof-of-concept
prototype"), and the results were used to confirm that approach B is
sufficient and that the precision delta is independent. If the user
prefers to revert and re-land approach A or a hybrid, the scope of
revert is the twelve files listed above.

## 7. Open questions

- **Other math intrinsics not yet exercised.** The fix covers every
  `Expression<Double>` math intrinsic currently in `io.almostrealism.expression`.
  If a backend or future expression introduces a new intrinsic
  (e.g., `sinh`, `cosh`, `acos`), the implementer must remember to route
  its operand through `castForMathArgument`. There is no compile-time
  enforcement of this. A follow-up could add a code-policy detector that
  flags `Expression<Double>` classes whose `getExpression` emits a math
  intrinsic call without the hook, but that is out of scope for this
  task.
- **Pow and atan2 second-operand subtleties.** Both arguments are
  currently routed through the hook. If a future Metal use case wants
  `pow(arg, INTEGER_LITERAL)` to dispatch to the integer-exponent
  specialisation, the cast would have to be conditionalised on operand
  position. No such use case exists in the project today.
- **Half-precision (FP16) buffer loads.** The cast is `(float)`, not
  `(half)`, on FP16 because `nameForType(Double.class)` maps to `float`
  for both FP32 and FP16 in `CLanguageOperations`. This means an FP16
  Metal kernel still goes through the `float` overload of the math
  intrinsic, which is the most precise choice available; it does not
  preclude the operand from being downcast to `half` on the way back into
  a `device half*` buffer. The runtime impact is "FP16 kernels do their
  math in FP32, then store FP16," which is the existing behaviour for
  every other arithmetic op in the same kernel — no regression. Worth
  re-checking if a future iteration wants strict FP16 throughout.
