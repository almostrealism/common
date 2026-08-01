# The Feasibility Gate

> A pre-investment gate. Before optimizing — or continuing to optimize — a system toward a
> quantitative target, establish that the **current structure can reach the target**, not
> merely that local changes move the number. Part of the falsification-gate family: you pass
> the gate by *trying, cheaply, to falsify* the claim "this approach can hit the target," and
> proceeding only if that falsification fails.

---

## Why this gate exists — the failure it prevents

This gate was written after a real failure: a ~14-hour effort to take the AudioScene a2
(pattern-element render) layer to ~5× real-time. The work moved the number from 0.18× to
2.34× through a long series of measured, verified, individually-correct tweaks (kernel
coalescing, a gather cache, a window filter). It never reached the target, and the
post-mortem conclusion was that 5× needs a **different structure** — independent
"run-ahead" PDSL streams — of which the whole problem is a special case.

The tell: that correct framing surfaced *only after the wrong approach was exhausted*. Every
measurement along the way produced a **local gradient** ("cut the gather", "cut perNote"),
and following it felt like rigor. It was rigor — *tactical* rigor, applied to climbing the
wrong hill. Local optimization cannot cross from one structural basin to another; no amount
of verified tweaking escapes a local minimum the structure imposes. The cost of skipping
this gate was the entire session.

## The core question

When you face a quantitative target (N× faster, under budget, X memory), ask **before**
optimizing:

> Is the gap a **constant factor within the right structure**, or is the **structure itself
> the ceiling**?

- Constant factor → optimize. The gate **passes**.
- Structure is the ceiling → stop; change the structure. The gate **fails** — do not
  spend time tuning.

## How to run the gate (cheap falsification, ~one focused pass)

1. **Decompose the cost once, fully, up front.** A single thorough profiling pass that
   attributes the *whole* cost — not a series of one-hypothesis-per-run probes. Serializing
   hypotheses across expensive runs (each confirming or refuting one guess) is the dominant
   time sink and the thing this gate replaces.
2. **Separate the irreducible core from the structural overhead.** What is the actual
   necessary work (e.g. the real GPU render of the data), versus overhead imposed by the
   current structure (shared dimensions across a batch, host-side marshalling, re-computation
   that a different layout wouldn't need)?
3. **Compute the ceiling.** Imagine the structural overhead were zero. Does the irreducible
   core *alone* meet the target?
   - Core alone still misses → the algorithm/kernel is wrong. Redesign.
   - Core meets it but the overhead is **structural** (cannot be removed without changing the
     structure) → the structure is the ceiling. Redesign.
   - Core meets it and the overhead is **incidental** (a constant factor removable in place)
     → optimize. Gate passes.
4. **If redesigning, name the right abstraction first.** What general construct is this
   problem a special case of? Write that abstraction down — ideally expressed in the target
   system's own language — *before* building. It becomes the north star, and it almost always
   generalizes past the immediate problem (in the motivating case: "render-ahead pattern
   audio" is a special case of "PDSL streams that run ahead of a consumer's forward pass").

## Hard rules

- **Hypotheses are not conclusions.** An unverified causal story ("it's the FIR", "it's
  frame count", "it's compile time") must be labelled unverified and *measured before acting
  or reporting*. In the motivating session every one of those three was asserted as a cause
  and then refuted by measurement; the real cause (GPU pipeline-state switching) was none of
  them.
- **A small local gradient next to a large gap is a STOP signal, not a to-do item.** If each
  verified change moves the number a few percent and the target is a multiple away, the
  structure is wrong. Escalate the rebuild-vs-tweak decision immediately.
- **Tactical rigor ≠ strategic correctness.** A clean measure → change → verify loop is a
  very disciplined way to climb the wrong hill. The discipline can hide the absence of a
  strategic decision.
- **If the conclusion "this needs a redesign" keeps reappearing**, that is evidence the gate
  should have run first. Treat its recurrence as a trigger to stop and run the gate now.

## When to apply

- At the **start** of any optimization task that carries a quantitative target.
- **Re-apply** the moment several consecutive changes show diminishing returns relative to
  the gap.
- Before committing to "just one more optimization" when the target is still a multiple away.

## One-line summary

Don't optimize a structure until you've shown it can reach the target; the cheapest way to
show that is to try to prove it *can't*.
