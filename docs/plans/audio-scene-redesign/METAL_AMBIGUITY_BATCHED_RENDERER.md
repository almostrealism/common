# Metal `floor()` Ambiguity in `BatchedPatternRendererTest.testAcousticEquivalence`

**Status:** Investigation only — no fix in this iteration.

**Branch:** `feature/audio-scene-redesign` (HEAD `f494563d7`)

**Test:** `org.almostrealism.audio.BatchedPatternRendererTest#testAcousticEquivalence`

**Failure shape:** `org.almostrealism.hardware.HardwareException: Failed to compile f_collectionAddComputation_17`

---

## Phase 0 — Branch state

### Master is merged

`git log --oneline -10` shows merges from master are present:

```
f494563d7 Merge origin/master into feature/audio-scene-redesign
2380872df Merge branch 'master' into feature/audio-scene-redesign
4a72a740e Merge branch 'master' of github.com:almostrealism/common
f3dc9f578 Merge branch 'master' into feature/audio-scene-redesign
```

### Literal-folding fix is present

**`Floor.java`** has the `Floor.of(...)` factory with literal folding
(`base/code/src/main/java/io/almostrealism/expression/Floor.java:96-104`):

```java
public static Expression of(Expression in) {
    OptionalDouble d = in.doubleValue();

    if (d.isPresent()) {
        return new DoubleConstant(Math.floor(d.getAsDouble()));
    }

    return new Floor(in);
}
```

Additionally, `Floor.getExpression(...)` substitutes the folded literal at
emission time
(`base/code/src/main/java/io/almostrealism/expression/Floor.java:44-47`):

```java
public String getExpression(LanguageOperations lang) {
    OptionalDouble v = getChildren().get(0).doubleValue();
    return v.isPresent() ? "floor(" + v.getAsDouble()+ ")" : "floor(" + getChildren().get(0).getExpression(lang) + ")";
}
```

**`Exp.java`** has the analogous factory
(`base/code/src/main/java/io/almostrealism/expression/Exp.java:96-104`):

```java
public static <T> Expression<T> of(Expression input) {
    OptionalDouble d = input.doubleValue();

    if (d.isPresent()) {
        return (Expression<T>) new DoubleConstant(Math.exp(d.getAsDouble()));
    }

    return (Expression<T>) new Exp(input);
}
```

(Exp lacks the emission-time substitution that Floor has, but that does
not matter for the current failure — see Phase 2.)

The relevant call site, `ArithmeticFeatures.floor(...)`, builds via the
folding factory
(`compute/algebra/src/main/java/org/almostrealism/collect/ArithmeticFeatures.java:374-380`):

```java
default CollectionProducerComputationBase floor(Producer<PackedCollection> value) {
    TraversalPolicy shape = shape(value);
    return new DefaultTraversableExpressionComputation(
            "floor", shape,
            args -> new UniformCollectionExpression("floor", shape, in -> Floor.of(in[0]), args[1]),
            value);
}
```

### Test still fails

Test runner `e99c6fb4` on this branch (Mac M1 Ultra, macOS 24.6, JDK 24.0.1,
JNI + Metal + OpenCL backends active):

```
[ERROR] org.almostrealism.audio.BatchedPatternRendererTest.testAcousticEquivalence
        -- Time elapsed: 0.496 s <<< ERROR!
org.almostrealism.hardware.HardwareException: Failed to compile f_collectionAddComputation_17
    at org.almostrealism.hardware.metal.MetalProgram.compile(MetalProgram.java:152)
    at org.almostrealism.hardware.metal.MetalOperatorMap.init(MetalOperatorMap.java:98)
    ...
    at org.almostrealism.audio.BatchedPatternRendererTest.testAcousticEquivalence(BatchedPatternRendererTest.java:133)
```

`BatchedPatternRendererTest.java:133` is the first sequential-reference
`evaluate()` call inside the per-note reference loop:

```java
PackedCollection resampled =
        renderer.buildResampleProducer(sources[n], ratioValues[n])
                .get().evaluate();   // <-- line 133, first iteration n=0
```

For `n=0`, `ratioValues[0] = 1.0 + 0 * 0.1 = 1.0`. The failure occurs
*before* the batched chain is built — the per-note resample kernel for the
n=0 note is what won't compile.

---

## Phase 1 — Captured failure

### Reproduction recipe

The compile failure was reproduced with the existing
`AR_INSTRUCTION_SET_MONITORING=failed` instrumentation, which causes
`MetalProgram.compile` to dump the failing kernel's MSL source via
`recordInstructionSet()` before throwing. No new instrumentation was
added — the codebase already has the capture hook (see
`base/hardware/src/main/java/org/almostrealism/hardware/metal/MetalProgram.java:148-154`)
and the underlying native code already prints the Metal compiler
diagnostic to stdout (see
`base/hardware/src/main/cpp/MTL.cpp:133-138`):

```cpp
NS::Error* error;
MTL::Library* library = dev->newLibrary(funcSourceStr, compileOptions, &error);

if (error != nullptr) {
    printf("Error: %s\n", error->localizedDescription()->utf8String());
}
```

Test invocation:

```
mvn test -pl engine/audio \
  -DargLine='-DAR_INSTRUCTION_SET_MONITORING=failed -DAR_INSTRUCTION_SET_OUTPUT_DIR=results/metal-failure-capture' \
  -Dtest=BatchedPatternRendererTest
```

### (A) Verbatim Metal shader source — `f_collectionAddComputation_17`

The file written to
`engine/audio/results/metal-failure-capture/mtl_instruction_set_0.c`:

```c
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

[[kernel]] void f_collectionAddComputation_17(device float *_17_v21 [[buffer(0)]], device float *_7_v3 [[buffer(1)]], device int *offsetArr [[buffer(2)]], device int *sizeArr [[buffer(3)]], uint global_id [[thread_position_in_grid]], uint global_count [[threads_per_grid]]) {
int _17_v21Offset = (int) offsetArr[0];
int _7_v3Offset = (int) offsetArr[1];
int _17_v21Size = (int) sizeArr[0];
int _7_v3Size = (int) sizeArr[1];
_17_v21[((long)global_id) + _17_v21Offset] = ((_7_v3[(((((int) (floor(((long)global_id)) + 1.0)) % 2048) + 2048) % 2048) + _7_v3Offset] + (- _7_v3[(((((int) floor(((long)global_id))) % 2048) + 2048) % 2048) + _7_v3Offset])) * ((- floor(((long)global_id))) + ((long)global_id))) + _7_v3[(((((int) floor(((long)global_id))) % 2048) + 2048) % 2048) + _7_v3Offset];

}
```

### (B) Verbatim Metal compiler diagnostic

From the test runner stdout/stderr:

```
[ERROR] Error: program_source:21:65: error: call to 'floor' is ambiguous
[ERROR] program_source:21:158: error: call to 'floor' is ambiguous
[ERROR] program_source:21:231: error: call to 'floor' is ambiguous
[ERROR] program_source:21:297: error: call to 'floor' is ambiguous
```

Four ambiguity errors, one for each occurrence of `floor(((long)global_id))`
on line 21. The Metal compiler driver did not, in this surface, enumerate
the candidate overloads it considered — only the `error: call to 'floor'
is ambiguous` line per occurrence. Metal's `floor()` overloads (from
`metal_stdlib`) cover `float`/`half` scalar and `floatN`/`halfN` vector
forms; the integer scalar overload is what is missing, so an `int`/`long`
argument has multiple equally-good implicit conversions and the compiler
refuses to pick one.

---

## Phase 2 — Analysis

### Which line, which call, which operand, what type

Line 21 of the captured source contains four `floor(...)` calls. In every
case the operand is the same expression:

```c
floor(((long)global_id))
```

`global_id` is the kernel parameter
`uint global_id [[thread_position_in_grid]]`, cast to `long`. Its **type
in the Metal source is `long` (signed 64-bit integer)** — not `float`,
not `half`, not `auto`, not any FP type.

Metal Standard Library `floor()` is defined only for floating-point
scalar and vector types. Calling it with a `long` triggers implicit
conversion lookup, which finds multiple equally-ranked FP conversions
(`long → float`, `long → half`, etc.), and the resolution is ambiguous.

### Categorisation — case (c)

This is **case (c) from the task statement**: a genuine non-literal
ambiguity. The operand is not a literal at any layer — it is the runtime
kernel thread-position variable. The existing `Floor.of(...)` literal-fold
correctly returned a non-folded `new Floor(in)` because `in.doubleValue()`
is empty for `global_id`. Likewise the `Floor.getExpression(...)`
emission-time literal substitution correctly emitted
`floor(<operand expression>)` because `getChildren().get(0).doubleValue()`
is empty.

It is **not** case (a): no literal is slipping through any folding path.
The folding behaves correctly for the values it was designed for.

It is **not** case (b): the failing function *is* `floor`, which is the
function the master fix already addressed for literal operands. The fix
just doesn't cover non-literal integer operands, because that wasn't a
case the master fix was scoped to.

### Why the operand is integer-typed for the n=0 ratio

`BatchedPatternRenderer.buildResampleProducer`
(`engine/audio/src/main/java/org/almostrealism/audio/BatchedPatternRenderer.java:109-116`):

```java
public CollectionProducer buildResampleProducer(PackedCollection source, double ratio) {
    CollectionProducer srcPos = integers(0, targetLength).multiply(c(ratio));
    CollectionProducer fPos = floor(srcPos);
    CollectionProducer frac = srcPos.subtract(fPos);
    CollectionProducer s0 = c(shape(targetLength), cp(source), fPos);
    CollectionProducer s1 = c(shape(targetLength), cp(source), fPos.add(c(1.0)));
    return s0.add(frac.multiply(s1.subtract(s0)));
}
```

The reference loop in the test, line 132-133, calls this with
`sources[0]` and `ratioValues[0] = 1.0`. The construction path is:

1. `integers(0, 1024)` builds an `ArithmeticSequenceComputation(initial=0,
   rate=1)` (`compute/algebra/src/main/java/org/almostrealism/collect/computations/ArithmeticSequenceComputation.java:115-190`).
   Its `ArithmeticSequenceExpression.getValueAt(idx)` emits
   `e(0).add(e(1).multiply(idx))`
   (`base/code/src/main/java/io/almostrealism/collect/ArithmeticSequenceExpression.java:130-132`).
   `idx` here is `global_id`, the kernel thread-position index, which the
   framework emits as `(long)global_id` in Metal. The result of
   `0 + 1 * (long)global_id` is integer-typed.
2. `.multiply(c(1.0))` would normally produce a floating-point expression,
   but the framework's algebraic simplifier folds `x * 1.0` to `x` — and
   `x` here is the integer-typed kernel index. So the simplified `srcPos`
   reverts to the bare integer expression `(long)global_id`.
3. `floor(srcPos)` then constructs a `Floor` whose child is integer-typed.
   `Floor.of(...)` cannot fold it to a literal (no `doubleValue()`),
   so it builds `new Floor(<integer expression>)`.
4. Emission produces `floor(((long)global_id))`.

The same mechanism would NOT trigger for `ratioValues[1] = 1.1`, because
`x * 1.1` doesn't simplify to `x` — the multiplication stays and produces
a FP-typed `srcPos`, so `floor(srcPos)` emits with an FP operand and
Metal resolves it normally. The test fails on n=0 precisely because n=0
is the only iteration whose ratio is the multiplicative identity.

(For completeness: the test could also be triggered by any other path
that constructs `floor(...)` over an integer-typed operand. The current
single failing call site is in `buildResampleProducer`. A grep audit of
other `floor(...)` call sites is out of scope for this investigation —
the question was *whether* (c) is real, and the captured source proves
that it is.)

### Why the master Floor.of fix doesn't suffice here

`Floor.of(in)` short-circuits only when `in.doubleValue()` is present
(i.e. the operand is a numeric literal whose value is statically known).
A runtime kernel index has no `doubleValue()`. The master fix correctly
folds `floor(c(3.7))` to a `DoubleConstant`, but it leaves
`floor((long)global_id)` to be emitted as-is, because emitting it as-is
*would* be correct in a language with integer-accepting `floor` overloads
or sensible default conversion rules. Metal has neither.

---

## Phase 3 — Recommended fix approach

Two viable approaches; both can be argued from the captured evidence.
Below I name a primary recommendation and the secondary alternative,
with honest trade-offs.

### Recommendation: identity-simplify + detection-based cast in `Floor.of(...)`

Strengthen the existing `Floor.of(...)` factory to handle the
integer-typed operand case structurally, in two layers:

1. **Identity simplification**: if the operand is integer-typed, the
   mathematical result of `floor(x)` is `x` itself. Return the operand
   unchanged.

   ```java
   public static Expression of(Expression in) {
       OptionalDouble d = in.doubleValue();
       if (d.isPresent()) {
           return new DoubleConstant(Math.floor(d.getAsDouble()));
       }
       if (in.getType() == Integer.class || in.getType() == Long.class) {
           return in;     // floor of an integer is the integer itself
       }
       return new Floor(in);
   }
   ```

   This is the cleanest fix because it removes a useless operation from
   the kernel as well as avoiding the ambiguous emission. The
   simplification is mathematically exact: `Math.floor((double) n) == n`
   for all integers in the representable range — and Metal will only
   ever emit integer-typed expressions for indices/counts where the
   values are already integers by construction.

2. **Cast fallback at construction**: as a belt-and-suspenders measure
   for any operand that is neither a literal nor a pure integer type
   (mixed expressions whose `getType()` reports a non-FP class — there
   shouldn't be any today, but the type system doesn't forbid it), wrap
   with `.toDouble()` to ensure the emitted argument has an FP type. The
   `Expression` API already exposes this:
   `base/code/src/main/java/io/almostrealism/expression/Expression.java:1604-1608`:

   ```java
   public Expression<Double> toDouble() {
       if (getType() == Double.class) return (Expression<Double>) this;
       return Cast.of(Double.class, Cast.FP_NAME, this);
   }
   ```

   So the full body becomes:

   ```java
   public static Expression of(Expression in) {
       OptionalDouble d = in.doubleValue();
       if (d.isPresent()) {
           return new DoubleConstant(Math.floor(d.getAsDouble()));
       }
       if (in.getType() == Integer.class || in.getType() == Long.class) {
           return in;
       }
       if (!in.isFP()) {
           return new Floor(in.toDouble());
       }
       return new Floor(in);
   }
   ```

**Why this is the right shape:**

- It uses information the Expression API already exposes reliably:
  `getType()`, `isFP()`, `Expression.toDouble()`. `getType()` returns
  the concrete `Class<T>` set at construction time (see
  `base/code/src/main/java/io/almostrealism/expression/Expression.java:298`),
  not `null` and not `Object.class` — so the detection is robust.
- The integer-identity case is a true mathematical simplification that
  removes a `floor()` call from the kernel altogether — strictly better
  than emitting it.
- The fallback cast is the same `.toDouble()` mechanism already used
  throughout the codebase, not a one-off hack.
- It is local: the change is in one factory method on the affected
  expression class. No call-site changes, no codegen-layer changes, no
  language-operations-layer changes.
- It generalises cleanly to `Exp`, `Log`, `Sqrt`, `Pow`, `Sin`, `Cos`,
  `Tan`, `Tanh`, and any other transcendental that Metal defines only
  for FP types. Each of their `of(...)` factories can apply the same
  cast-fallback (the integer-identity rule does NOT generalise — e.g.
  `exp(n) != n` — but the cast rule does).

### Secondary alternative: always-cast at Floor emission

The "always-cast" version of the fix changes
`Floor.getExpression(...)` (or its equivalent in `Floor`'s codegen
layer) to wrap every non-literal operand in `(float)(...)` at emit
time, regardless of operand type.

Trade-offs vs. the recommended approach:

- ✗ Loses the integer-identity simplification — `floor((long)global_id)`
  becomes `floor((float)((long)global_id))` instead of `(long)global_id`.
  This still compiles in Metal but does pointless work on the GPU and
  pointless casts in the source.
- ✗ Pessimises FP operands that *would* have compiled fine. The
  detection-based version only inserts a cast when needed.
- ✓ Strictly cannot miss any ambiguous case, including ones our type
  detection might fail to identify (none known, but in principle).

The detection-based approach gives up almost nothing — the only case
where it could fail is an operand whose `getType()` lies about FP-ness,
which would be a bug elsewhere worth surfacing rather than papering
over.

### Out of scope for this investigation

- **Whether other transcendentals in the kernel have the same gap.**
  This memo only proves the case for `floor`. A parallel audit of
  `Exp`, `Log`, `Sqrt`, `Pow`, `Sin`, `Cos`, `Tan`, `Tanh` would tell us
  whether they're vulnerable to the same construction (an int-typed
  operand reaching their `getExpression`). Worth doing as part of the
  fix iteration.
- **Whether the simplifier should refuse to fold `int_expr * 1.0` to
  `int_expr`.** Arguably the simplifier is propagating an FP operation
  back to an integer result type incorrectly, and the right fix is at
  the simplifier layer. But that fix is harder to scope, more invasive,
  and the `Floor.of(...)` change is sufficient on its own.

---

## Reproduction artifacts

- Captured MSL source:
  `engine/audio/results/metal-failure-capture/mtl_instruction_set_0.c`
- Test runner ID: `e99c6fb4` (run on Mac M1 Ultra, macOS 24.6.0,
  JDK 24.0.1 Homebrew, ar-test-runner module=engine/audio).
- Reproduction command:

  ```
  mvn test -pl engine/audio \
    -DargLine='-DAR_INSTRUCTION_SET_MONITORING=failed -DAR_INSTRUCTION_SET_OUTPUT_DIR=results/metal-failure-capture' \
    -Dtest=BatchedPatternRendererTest
  ```

  The `AR_INSTRUCTION_SET_MONITORING=failed` setting triggers
  `MetalProgram.recordInstructionSet()` on compile failure. No source
  modifications were required to capture the diagnostic — the native
  printf in `MTL.cpp:137` already surfaces the Metal compiler error to
  stdout, and `MetalProgram.compile` already writes the MSL to disk on
  failure.
