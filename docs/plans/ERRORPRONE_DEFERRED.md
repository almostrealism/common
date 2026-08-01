# ErrorProne Deferred Checks

This document records ErrorProne checks that were evaluated but intentionally deferred
when the active checks were enabled at `ERROR` level. The active checks are configured
in `pom.xml` under the `-Xplugin:ErrorProne` compiler argument.

## Currently Active Checks (ERROR level)

| Check | Rationale |
|---|---|
| `UnnecessarilyFullyQualified` | Keep imports clean; FQN in code is almost never needed |
| `MissingOverride` | Prevents interface drift; catches missed `@Override` annotations |
| `MissingSummary` | Javadoc is essential for agent comprehension and code navigation |
| `UnusedVariable` | Dead code; eliminates confusion and dead assignments |
| `JdkObsolete` | Removes legacy types (StringBuffer, Stack, Hashtable, Vector, LinkedList) |
| `CatchAndPrintStackTrace` | Ensures exceptions are properly logged, not silently swallowed |
| `ClassCanBeStatic` | Eliminates implicit enclosing-instance references where not needed |
| `InvalidLink` | Broken Javadoc `{@link}` references are misleading to readers |
| `InvalidParam` | Stale `@param` tags that reference non-existent parameters |
| `EscapedEntity` | HTML entities inside `{@code}` render literally, not as intended |

## Deferred Checks

### ReferenceEquality (11 violations when audited)

**Why deferred:** The `==` operator is used intentionally in this codebase for identity
comparison, not just as a shortcut for `.equals()`. The framework uses object identity
to distinguish specific singleton instances and sentinel values. Enabling this check
at ERROR level would flag intentional identity comparisons as bugs.

**Revisit when:** A formal audit of all `==` comparisons in the codebase has been
completed and the intentional ones documented or replaced with explicit identity-check
helpers.

---

### LongDoubleConversion (11 violations when audited)

**Why deferred:** Implicit narrowing conversions from `long` to `double` can lose
precision for large values. Violations were present but fixing them requires careful
review of numeric ranges in each context (GPU kernel indices, sample counts, etc.)
to determine whether the narrowing is safe.

**Fix pattern:** Cast explicitly: `(double) longValue` or restructure to avoid the
conversion where precision matters.

**Revisit when:** Numeric precision audit is performed, particularly in hardware/kernel
coordinate calculations.

---

### DefaultCharset (10 violations when audited)

**Why deferred:** Methods like `new String(bytes)`, `String.getBytes()`, and
`new InputStreamReader(stream)` without an explicit charset depend on the platform
default charset, which may differ between environments. Fixing requires determining
the correct charset for each call site (UTF-8 in most cases) and is low-risk on
UTF-8 Linux environments.

**Fix pattern:** Add explicit charset: `new String(bytes, StandardCharsets.UTF_8)`,
`str.getBytes(StandardCharsets.UTF_8)`.

**Revisit when:** Cross-platform portability becomes a concern or Windows CI is added.

---

### ShortCircuitBoolean (4 violations when audited)

**Why deferred:** Using `&` or `|` instead of `&&` or `||` for boolean expressions
prevents short-circuit evaluation. In some cases this is intentional (forcing evaluation
of both sides for side effects). Requires per-site review to distinguish intentional
from accidental use.

**Revisit when:** The affected methods are reviewed for side-effect intent.

---

### RandomCast (4 violations when audited — 2 fixed as real bugs)

**Status:** 2 violations in `MercuryXenonLamp.java` were genuine bugs
(`(int)Math.random()*N` always evaluates to 0) and were fixed. The remaining
violations (if any) should be reviewed similarly — this is almost always a bug.

**Fix pattern:** `(int)(Math.random() * N)` not `(int)Math.random() * N`.

**Revisit when:** Any new violation appears — treat as a likely bug, not a style issue.

---

### ObjectEqualsForPrimitives (3 violations when audited)

**Why deferred:** Using `Objects.equals()` or `.equals()` on primitive wrapper
types (Integer, Double) instead of `==` or primitive comparison. Low-risk correctness
issue; fix is mechanical.

**Fix pattern:** Use primitive comparison `==` or unbox explicitly.

---

### FloatCast (3 violations when audited)

**Why deferred:** Implicit casts from `double` to `float` that may lose precision.
Context-dependent — GPU shader code often intentionally uses float precision.

**Fix pattern:** Add explicit cast `(float)` where intentional; use `double` throughout
where precision matters.

---

### NarrowCalculation (2 violations when audited)

**Why deferred:** Integer arithmetic that overflows before being widened to `long`
(e.g., `long x = a * b` where `a` and `b` are `int`). Requires per-site review
of whether overflow is possible in practice.

**Fix pattern:** `long x = (long) a * b`.

---

### JavaUtilDate (2 violations when audited)

**Why deferred:** `java.util.Date` is deprecated in favor of `java.time.*` API.
Fixing requires migrating callers to `Instant`, `LocalDateTime`, etc., which is a
larger refactoring than a single-line fix.

**Fix pattern:** Replace `new Date()` with `Instant.now()`; replace date formatting
with `DateTimeFormatter`.

**Revisit when:** A broader date/time API modernization is planned.

---

### ClassNewInstance (deferred — count not audited)

**Why deferred:** `Class.newInstance()` is deprecated and propagates checked exceptions
as unchecked. Replacement is `clazz.getDeclaredConstructor().newInstance()` with
proper exception handling. Low-risk in practice but requires per-call-site review.

**Fix pattern:** `clazz.getDeclaredConstructor().newInstance()` wrapped in try-catch
for `ReflectiveOperationException`.

---

## Notes for Future Sessions

1. **Do not re-enable `ReferenceEquality` without explicit owner sign-off.** The
   `==` operator is used intentionally for identity semantics in this codebase. See
   CLAUDE.md agent integrity section.

2. **`RandomCast` is almost always a real bug.** If a new violation appears, investigate
   the arithmetic rather than suppressing.

3. **The `DefaultCharset` violations are concentrated in legacy persistence/NFS code**
   (`graphpersist` module). A targeted fix there would cover most of the count.

4. When fixing `JdkObsolete` LinkedList replacements, prefer `ArrayList` over
   `ArrayDeque` when the collection may contain `null` elements, since `ArrayDeque`
   does not permit nulls.
