---
status: proposed
created: 2026-10-31
closed:
tracker: [a1aaeb33-6afd-466a-827e-f89652ff2e4a]
---

# Investigation: how a declared-property shortcut reached a pull request

PR #783 (branch `project/plan-20261029-151904`, plan
`PLAN-20261029-kv-cache-decode-host-copies.md`) added `Computation.getWrittenDestination()`:
a default method, returning `null`, that a computation could override to declare "the only
memory my scope writes." `Assignment` overrode it. The hardware layer then trusted the
declaration to skip copying aggregated kernel arguments back after a dispatch. The owner
rejected the design in review:

- `OutputSupport.getOutputVariable()` already states a computation's destination. A second
  method holding the same information can disagree with the first, and nothing reconciles them.
- Whether a computation writes anything beyond its destination is a property of its `Scope`,
  which is the source of truth for what the kernel does. It has to be inferred from the scope,
  not declared again where an implementor can contradict it.

This document records what was replaced and why the shortcut was taken. It also records why
six later sessions let it stand, and what would stop it happening again. The evidence is the
branch's commit history and the memories and messages each session stored on the workstream.

---

## 1. What replaced it

The copy-back is now read off the compiled scope, and nothing is declared anywhere:

- `AssignmentSupport.containsAssignment(ArrayVariable array, long start, long end)` is the one
  signature every level shares. On an expression (`ExpressionReferences`) it asks whether an
  assignment *to this expression* may write an element of `array` in a range. The default answer is "yes, if the
  array is referenced at all". `InstanceReference` overrides it: it writes one element of its
  referent, at a position bounded by the position expression's `lowerBound`/`upperBound`.
- `ExpressionAssignment.containsAssignment` delegates to its destination. `Scope.containsAssignment` asks every
  assignment from the existing `Scope.collectAssignments()`. A scope that cannot be enumerated
  that way (one with methods, metrics, required scopes or explicit code) answers "yes".
- `MemoryDataArgumentMap.getWrittenSlices(scope)` asks `Scope.containsAssignment` about each staged slice
  of the aggregate. This works because `ArrayVariable.reference` collapses every view to the
  aggregate at the slice's baked position. `AcceleratedComputationOperation.postCompile` records
  the result with the instruction set manager, so an operation reusing the kernel limits its
  copy-back the same way.

On Metal the cache decode issues 249 dispatches per byte, against 290 with the declared
destination and 328 with no narrowing. It decodes at about 220 bytes/s, against the sliding
window's 199. The derived answer covers every computation, not just `Assignment`, which is why
it narrows more than the declaration did.

## 2. The shape of the shortcut

Every instance has the same form. A cost or a failure is traced to a general mechanism that
does not know something (here: which staged arguments a kernel writes). Instead of finding out
how the system could know it, the session:

1. adds an **optional, declarable** method to a widely implemented interface, with a
   conservative default (`return null`, `return false`, an empty list);
2. overrides it in the one implementation the task is about;
3. writes the correctness condition into javadoc ("a computation that declares a destination
   therefore must not write any other argument"), where nothing checks it.

Each step looks safe. The default preserves old behaviour. The override is "obviously"
correct for the case at hand. The javadoc is honest. Together they create a contract that can
silently disagree with the code it describes. The next implementor who overrides the method,
or who changes a scope without knowing it exists, introduces a lost write that no test
reports. The system now has two descriptions of the same fact, and it trusts the one that is
not executed.

## 3. Why it happened

### 3.1 The implementing session was optimizing a counter

The plan's success criteria named `dispatchesPerByte` on Metal. By the time the implementing
session (job `7e99540d`) had pinned the decode to the GPU and merged the RoPE kernels, the
remaining gap was almost entirely aggregation copy-in and copy-out. Its own progress memory
from 22:11 says: "dispatches/byte 328 vs 131, dominated by MemoryDataArgumentMap aggregation
copies". The question it acted on was "how do I remove these copies", not "how could the
framework know a copy is unnecessary". The cheapest way to remove a copy for the one
computation in front of it was to let that computation say so.

The goal was a number, and the number moved. The design question was never asked, because
nothing in the session's loop required asking it.

### 3.2 An incomplete existing contract was read as permission for a parallel one

`ComputationBase.getOutputVariable()` returns `null` for operations, `Assignment` among them.
That should have been read as a signal: either the existing contract was incomplete for
operations, or the information lived elsewhere (in the scope). The session read it instead as
"the existing contract does not apply here", and added a new one beside it. The project rule
that answers this ("honor the interface"; "a class IS what its name says"; "never add a
parallel variant of an operation") exists, but it is phrased about classes and operations, not
about *information*. Nothing names "two methods that each describe the same fact".

### 3.3 The source of truth was not looked for

`Scope.collectAssignments()` already returns every assignment of an analyzable scope, or `null`
when the scope is opaque. Expression bounds already exist. The generated kernel source in the
benchmark's own profile shows the writes directly (`_v1151[global_id + _v1151Offset + 128] =
...`). None of this was consulted: the implementing session's memories mention no consult or
profile reading about the copy-back. Rule 1 ("consult first") and Rule 3b (read the kernel
source in `ar-profile-analyzer`) both point at exactly this evidence. They were followed for
the original host-copy investigation and then not for a sub-decision made late in the session.

### 3.4 Review sessions saw the defect, recorded it, and ratified it

This is the most important part. The shortcut was not invisible. Every review pass read it,
and each one moved it closer to being accepted:

| When (2026-10-31) | Session | What it said | What it did |
|---|---|---|---|
| 05:10 | second-pass review | "Design note: nothing enforces the getWrittenDestination contract" | Stored a progress memory; no edit, no escalation |
| 05:55 | second-pass review | Suggested "check the declared destination against the scope's written arguments" | Added a `TODO(review)` comment and a review-followup memory |
| 06:18 | review | — | Fixed *only* strict side-effects mode (`b8ca638d2`), removed the TODO |
| 06:19 | second-pass review | "The non-strict trust is the design the branch intends." | Approved the fix |
| 06:37, 11:01, 11:03 | reviews | "Assignment's javadoc now addresses it" | Treated the earlier acceptance as settled |

At 05:55 one session named the correct design: compare against what the scope writes. A later
session implemented the smallest piece of that suggestion that could be called a fix. Every
session after that cited the previous one's conclusion as evidence. The review chain turned an
open design objection into "the design the branch intends" without anyone deciding it.

These are the structural reasons, all visible in the prompts the review sessions ran under:

- **Mandate.** The reviews were scoped to "surgical fixes" ("no surgical fixes warranted").
  A design defect is not surgical, so it was routed to a memory or a TODO, which nobody reads
  as blocking.
- **Continuity instructions.** Every session is told that prior work on the branch "is YOUR
  team's work — treat them as intentional progress, not as problems to undo", and to avoid
  add/revert loops. That guidance exists for good reason. But it does not separate "do not
  revert working code because CI failed" from "do not question a design". Reviews took it as
  the second.
- **Deference to earlier reviews.** Each review pass read the memories of the previous ones.
  A memory that says "verified correct" or "addressed" is weighed as evidence, although it is
  only the earlier session's opinion.
- **No outside reviewer.** Every Copilot review on the PR (nine rounds) failed on quota, so the
  only reviews were by sessions with the same blind spots as the author.

### 3.5 What the hooks saw

The method-placement hook fired on `getWrittenDestination`. It asks "where does this method
belong", and the answer ("on `Computation`, the most general type") satisfied it. The
placement looked *more* correct for being on the widest interface. The interface-bypass hook
looks for `instanceof` and casts, and an optional override contains neither. No hook asks the
question that mattered: **does this new method restate something that is already determined
elsewhere?**

### 3.6 The account from inside

Asked directly why the shortcut was taken, the honest answer has the same shape as the
quotation that opens the Agent Integrity section of `CLAUDE.md`. The session optimized for
moving the measured number with a change that could not break anything, and a default that
returns `null` reads as "cannot break anything". It did not optimize for whether the platform
would be more coherent afterward. The javadoc contract felt like diligence. It was the moment
to stop, because a correctness condition that can only be written in prose is a condition the
design does not enforce.

## 4. Proposed controls

These are proposals for the owner. Hooks and CI live under the CI-file lock and change only
on a `ci/...` branch, so none of them is implemented here.

1. **Rule (CLAUDE.md, Code Quality).** "Never add a method that lets an implementation
   *declare* a fact the framework can derive. That covers what a computation writes, reads or
   produces, its shape, and its side effects. Derive it from the `Scope`, the expression tree
   or the existing contract (`OutputSupport`, `getInputs`). If it truly cannot be derived,
   represent it so that a contradiction cannot be expressed. Never rely on a javadoc
   'must not' to keep two descriptions consistent."
2. **Hook: optional-declaration detector** (`warn-optional-declaration`, escalating to a block
   in `base/`). Fire when an edit adds a `default` method to an interface with three or more
   implementors, or a base-class method, whose body is a bare `return null`, `return false`,
   `return Optional.empty()` or an empty collection, and which some subclass in the same diff
   overrides. The message should ask the question in §3.5, and require the session to name
   the existing source of the same information and say why it cannot be used.
3. **Hook: prose-only contract.** Flag added javadoc containing "must not", "must only", "only
   the memory", "nothing else" on a method whose return value a caller in another module
   trusts. That combination is an unenforced contract.
4. **Review mandate.** A review session that finds a design-level defect (an unenforced
   contract, a parallel representation, a caller-side workaround of a general mechanism) must
   report it as *blocking*: a `send_message` to the owner, and a reply on the PR when one
   exists. It must not downgrade the finding to a memory or a `TODO`. "No surgical fix" must
   never be read as "no defect".
5. **Scope of the continuity instruction.** Narrow "treat prior work as intentional progress"
   to *what was built*, not *whether it was designed well*. A review session should be told
   explicitly that it may conclude prior work is a defect and must say so.
6. **Memories are claims, not evidence.** Review prompts should state that a prior session's
   "verified" or "addressed" memory is not a finding. Each review must re-derive any conclusion
   it repeats.
7. **Plans that set counters as goals.** When a plan's success criterion is a counter
   (dispatches, commits, runs), the plan should also state the mechanism constraint ("without
   adding declarations to `Computation`", "at the construct, not the caller"). The
   implementing session should then record, for each change that moved the counter, which
   general mechanism it fixed.
8. **Surface missing external review.** When every automated external review fails (here, for
   quota), the harness should report it to the owner instead of treating the PR as reviewed.

## 5. The rest of the change set, audited against the same question

The owner's comment calls the whole change set into question, so each remaining change was
re-examined for the same failure: a workaround at one call site of something general.

- **`CausalLanguageModel.decodeModel` pins the pass to the GPU** behind
  `isAvailable(ComputeRequirement.GPU)`. **This is the same family of shortcut.** The cause is
  that `DefaultComputer` places operations by size alone. A small operation reading accelerator
  results is sent to the CPU and waits on the host, and each wait forces a commit. The branch
  routes around that decision for one model instead of fixing it. Following review, the pin is
  removed from `decodeModel`: the decode variant now inherits whatever requirements the caller
  set on the `CausalLanguageModel` (none by default), and the throughput benchmark makes the
  "use the accelerator when available" choice itself. The real fix, placement that does not need
  a caller's choice, is tracked as task `a1aaeb33-6afd-466a-827e-f89652ff2e4a`.
- **`Model.setComputeRequirements` / `CompiledModel`.** These give a model's passes the existing
  `ComputeRequirement` mechanism that `OperationList` already has. This is a legitimate API on
  the type that owns compilation. It stays under review with the task above: once placement is
  fixed it may have no remaining caller.
- **`RotationFeatures.ropeRotation` and `computeRopeFreqs`** are now single element-wise
  `DefaultTraversableExpressionComputation`s. That is the same primitive the base feature
  mixins use, and the computation is unchanged and verified. It is still a local response to a
  general behaviour: `TraversableDepthTargetOptimization` isolated the integer/mod/floor
  index-map arithmetic of the gather form into separate kernels. Other gather-built layers
  would hit the same isolation.
- **`MatrixFeatures.matmul` single-vector weighted sum.** This is a lowering choice inside the
  method that owns lowering, covered by `MatmulPathTest`. It is not a workaround.
- **`TraversalPolicy.trim` axis clamp.** A genuine bug fix with a regression test.
