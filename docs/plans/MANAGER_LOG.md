# Manager Log

A living journal of project-management decisions for the Almost Realism
platform. The newest entry is at the top. This is a record of *current
thinking* — the narrative of how the project's direction evolves and why
specific tasks were chosen — not a historical archive. Prune older entries
(condense, don't delete the thread) when the file exceeds ~50,000 characters.

The four task categories, evaluated in priority order for every planning
session: (1) Documentation, (2) Code Quality, (3) Performance, (4) Proof of
Value. Focus goes to the first category with genuine, impactful, unaddressed
work.

---

## 2026-09-26 — Documenting the impossibility rules at the Java↔native boundary

**This is the first entry in this log.** No `MANAGER_LOG.md` existed before
today; project direction had been carried in the individual plan documents
under `docs/plans/` and in `TRACKER.md`. Starting the log here so future
planning sessions have a single narrative thread to read before proposing work.

### What I investigated

- Read `TRACKER.md` (the lightweight ticket-system design) and surveyed the
  `docs/plans/` corpus by recency and git activity.
- Observed the recent commit history is dominated by automated `qa/*` branches —
  `qa/docs`, `qa/performance`, `qa/defect`, `qa/consolidate`, `qa/coverage`,
  `qa/pdsl`. Routine maintenance across documentation drift, performance,
  defects, and coverage is already being handled on a recurring basis. That
  frees a planning session to look for *net-new*, high-leverage work rather than
  incremental upkeep.
- Evaluated the four categories in order and stopped at the first with genuine
  unaddressed work: **Documentation.**

### What I found

`docs/plans/KERNEL_AND_MEMORY_DOCUMENTATION_GAPS.md` records a real crash
investigation that produced three substantively wrong hypotheses in a row, each
caused by *missing documentation of a platform invariant* rather than misread
evidence: a "stale kernel cache" that cannot bite (the generated `.c`/`.so`
files persist on disk — there is no startup purge — but the per-run target
counter restarts at 0 and each slot is recompiled and overwritten before it is
loaded, so no prior-run artifact is ever executed), an off-heap "budget
exhaustion → null pointer" that cannot happen
(exhaustion throws; it does not silently zero a pointer), and a kernel "nulling
a pointer mid-invocation" that cannot happen (the codegen language has no null
and no pointer-erase). I verified the follow-through was never done: none of the
four proposed internals pages exist (`KERNEL_CACHE.md`, `OFF_HEAP_MEMORY.md`,
`CODEGEN_VALUE_SEMANTICS.md`, `KERNEL_THREAD_SAFETY.md`), and the existing
`INSTRUCTION_CACHING.md` covers only the *in-JVM signature* cache, not the
on-disk dylib lifetime, the off-heap model, or codegen value semantics. All the
source files the gaps doc names still exist.

### Why I chose this task

Documentation is category 1, and this is not vague "improve the docs" work — it
is a specific, evidence-backed set of gaps whose payoff is unusually high. The
most valuable text we can write for this platform is not another API listing
but the set of *impossibility rules* at the Java-orchestration ↔ native-execution
boundary: the facts that tell a fresh investigator (human or model) what
*cannot* happen. Those rules permanently remove whole classes of dead-end
hypothesis. And they matter doubly for the longer-term vision of training models
that reason about this codebase: a model that "knows" a generated kernel cannot
null a pointer will not waste inference proposing that it might. Documentation
that encodes impossibility compounds.

The task — `PLAN-20260926-kernel-memory-codegen-docs.md` — operationalizes the
gaps doc into concrete deliverables: four single-page internals references (in
the source plan's priority order, codegen value semantics first), five
class-level Javadoc additions so the invariants live at the code, and `llms.txt`
wiring. It is documentation-only (no behavior change), medium complexity because
the value depends entirely on accuracy, and achievable in one focused session.

### Balance across categories

The recurring `qa/*` automation is covering incremental documentation drift,
performance, defects, and coverage. That is the right division of labor: let
automation hold the line on upkeep, and spend deliberate planning sessions on
net-new, compounding work. Right now that is documentation of hard invariants.
Once the Java↔native boundary is documented, the balance should tilt: code
quality and performance still have the `qa/*` safety net, so the next
*deliberate* push should start moving toward proof-of-value — the crown-jewel
demonstrations — building on a foundation that is now more legible to both
humans and models. The immediate follow-ups this task sets up are a native-crash
investigation playbook (companion to `ci-investigation-protocol.md`) and,
eventually, using this invariant corpus as high-signal training material.
