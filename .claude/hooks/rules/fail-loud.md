# Rule: Fail Loud, Never Tolerate Bad State

This rule exists because an agent spent a day masking a bug with a defensive
guard — `if (ctx > 0)` — instead of finding who was returning zero. The
project owner was right to be furious. This document is the reminder that
your instinct to "make the error go away" is the instinct of someone who
does not want to engineer software.

## The Rule

**A function that receives a value that violates its contract MUST throw
immediately, with a message that names the contract and points at the
caller.** It must not:

- Silently substitute a default ("if it's zero, use one")
- Clamp the value into a plausible range
- Return empty / null / false instead of the real answer
- Wrap the whole thing in `try/catch` that eats the problem
- Add a special case for "this broken input, do something"

Every one of those is a lie. The lie propagates. The next caller inherits
the lie and adds a new lie of their own. Eventually something silently
succeeds with catastrophically wrong behavior — a silent WAV, a model that
always predicts zero, a rendered frame that's black.

## The Test

Before adding a guard, answer in writing:

1. **Where did the bad value come from?** Walk the stack until you find the
   producer. If the producer is in this project, it is the thing to fix.
2. **What did the producer's contract say?** Read the Javadoc. Every
   method has one, even if it's implicit. If the producer's contract
   forbids the observed value, the producer has the bug, not you.
3. **Who else is consuming this value right now?** If you "tolerate" here,
   every other consumer silently sees the same bad value. You have not
   fixed it — you have hidden it from yourself.

If you cannot answer these three questions, you are not ready to add the
guard. You are ready to investigate.

## What "Fail Loud" Looks Like

```java
// WRONG — tolerates a contract violation, propagates the lie downstream
int len = memLength;
long ctx = context.getKernelMaximum().orElse(0);
if (ctx > 0) {
    len = count / ctx;
}
// compiled kernel now uses len = memLength for the broken case,
// silently producing wrong output for anyone who hits it

// RIGHT — contract says OptionalLong.empty() means "unknown",
// isPresent() means "known positive". A present zero is a lie.
int len = memLength;
OptionalLong max = context.getKernelMaximum();
if (max.isPresent()) {
    long ctx = max.getAsLong();
    if (ctx <= 0) {
        throw new IllegalStateException(
            "KernelStructureContext " + context + " reported a kernel "
            + "maximum of " + ctx + ". A zero-iteration kernel cannot "
            + "exist. Fix the context, do not relax this check.");
    }
    // ...
}
```

The first version ships. The second version fails in the test suite the
first time someone breaks the contract, and the stack trace names the
broken component. That is the point.

## Categories of Tolerance That Are Always Wrong

| Pattern | Why it's wrong |
|---|---|
| `if (x != null)` guarding a value that was just returned from a method whose signature does not permit null | You're telling the next reader the method might lie. Fix the method or its signature, don't teach callers to distrust it. |
| `Math.max(0, computedValue)` / `Math.min(max, computedValue)` | Clamping the symptom. What produced a negative or overflow? That's the bug. |
| `try { ... } catch (Exception e) { /* ignore */ }` | You just deleted a stack trace. Permanently. |
| `try { ... } catch (Exception e) { return defaultValue; }` | Worse. Now callers can't even see there was a failure. |
| `if (result.isEmpty()) return Optional.of(ZERO);` | Converting "unknown" into "known zero" is the exact pattern that broke this project. |
| Adding `if (count == 0) return` to a loop that already handles `count == 0` via its bound | Useless defensive code. If the bound is broken, fix the bound. |

## When a Guard IS Legitimate

- **Input validation at a public API boundary.** If your method is called
  by untrusted code (UI input, network input, configuration files),
  validate and reject with a clear message. The message is an external
  contract.
- **Precondition checks on construction.** A constructor enforcing its
  invariants. Throw in the constructor, not on first use.
- **Assertions of loop invariants.** Not catching bad state — documenting
  and enforcing the invariant so violations are impossible rather than
  invisible.

If your guard is in the middle of a method, operating on a value obtained
from another method in the same project, it is almost certainly not in
any of these categories.

## The Habit to Break

The habit is: "I see an exception, therefore the exception is the problem,
therefore I need to suppress the exception." No. The exception is a
messenger. The messenger is telling you what is wrong. Kill the messenger
and you will never find the problem.

Read the stack trace. Find the producer. Fix the producer. Then delete the
guard you were about to write.
