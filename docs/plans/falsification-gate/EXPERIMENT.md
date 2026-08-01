# Falsification Gate — Part A: The Experiment

**Status:** Planning / experiment writeup
**Branch:** `feature/falsification-gate`
**Document version:** 1
**Gates:** [DESIGN.md](DESIGN.md) (Part B). The verdict in §5 of this document is
the precondition for building the mechanism. If the predicate does not catch the
three cases in a non-gameable way, the mechanism is not justified.

---

## 0. What this experiment is and is not

This is **not** a "make agents more careful" exercise. It tests one specific,
repeatedly-observed failure mode and asks a single yes/no question:

> Is there a **general** predicate that flags a load-bearing behavioral claim as
> "must be falsified before it can be relied on," which catches all three real
> failure cases **without** being a checklist that pattern-matches their
> specific strings?

The failure mode (verbatim from the workstream framing): the agent mints a
plausible claim about runtime **behaviour**, never runs the experiment that
would falsify it, and then — at a later decision point, distant from where the
claim was minted — treats its own conjecture as established fact. The distance
between mint-site and use-site launders conjecture into "fact."

The three cases below are reconstructed precisely enough to rebuild as fixtures
without their original branches. They are the test set. The predicate is the
hypothesis. §5 is the verdict.

---

## 1. The gating predicate

A claim *C* extracted from an agent's own narrative/diff is **gated** — it must
be rejected pending falsification, and may not become load-bearing until
captured evidence settles it — **iff all three conditions hold**:

### P1 — Contingent-empirical content

*C* asserts something about the system's **actual behaviour** whose truth is
**not** settled by the task specification, by the diff itself, or by purely
analytic/definitional reasoning. Its truth can only be established by
**observing the system** (running it, or reading its definition/source/docs).

Non-exhaustive facets (these are *illustrations of one property*, not a
checklist — see §1.1):

- **Runtime behaviour** of an API / symbol / operation: what it returns, what
  backend or implementation it dispatches to, what side effects it has, whether
  it throws, what ordering it guarantees — **under a stated configuration**.
- **Representational capacity** of a type / format / schema: which set of values
  it can faithfully encode and round-trip without loss.
- **Causal explanation** of an observed event: which mechanism actually produced
  a symptom.

Claims that fail P1 (and are therefore **never** gated): pure restatements of
the task ("the user asked for X"); facts decidable from the diff alone ("this
method is now public"); analytic truths ("a `Set` has no duplicate elements by
definition").

### P2 — Load-bearing

At least one concrete change in the diff is **correct only if *C* is true**.

Operational test (this is the selectivity filter — see §4): *name the hunk that
becomes wrong under ¬C* — a wrong guard, a wrong field type, a now-irrelevant
block of machinery, an unhandled case. If you cannot point to a diff hunk whose
correctness flips when *C* is negated, *C* is a throwaway aside, not a gated
claim.

### P3 — Unfalsified **or** contradicted

**Either** of the following (the disjunction is essential — see §1.2):

- **P3a — Evidence gap.** The agent's context contains **no** captured artifact
  (command output, test result, probe, doc/source excerpt) whose content
  **entails** *C* **on the configuration the decision will run under**.
- **P3b — Evidence contradiction.** The agent's context **does** contain a
  captured artifact whose content **entails ¬C** (or is most simply explained by
  ¬C), yet the decision proceeds as if *C* were true.

A claim satisfying **P1 ∧ P2 ∧ P3** is gated.

### "Entails," defined strictly

Artifact *A* **entails** claim *C* for configuration *K* iff there is **no
consistent reading of *A*'s actual content under which *C* is false for *K***.

- "A command was run" is **not** entailment.
- "The command printed `X`, and `X` cannot be true unless *C*" **is**
  entailment.
- **Configuration is part of the claim.** Evidence gathered on configuration
  *K′ ≠ K* does **not** entail *C* for *K*. (This single clause is what would
  have caught Case 1; see §2.1.)

This strict definition is deliberately the hard part. A gate that accepts "I ran
*a* command" as proof is trivially gameable — that risk is the subject of
[DESIGN.md §6](DESIGN.md), not of the predicate itself. The predicate's job is
only to *demand* entailment; enforcing genuine entailment is the mechanism's job.

### 1.1 Why P1's facets are not a disguised checklist

A fair objection: "you derived the three facets (runtime behaviour /
representational capacity / causal explanation) from the three cases, so the
predicate still secretly enumerates them."

Rebuttal: the facets are **instances of a single property** — *a contingent
claim about system behaviour that observation, not reasoning, must settle* — not
an exhaustive list. Claims that satisfy P1 without matching any of the three
listed facets include: "this collection is thread-safe under concurrent
readers," "this kernel is numerically stable to 1e-6 for inputs in [0,1)," "this
call is idempotent," "this comparator imposes a total order." Each is gated by
P1 for the same reason the three cases are — its truth is a fact about the
running/defined system, not about the task or the diff. The three facets are the
ones the test set happens to exercise; the property generalises beyond them.

A genuine checklist would read "flag any claim containing the substring
`getComputeContext` / `MIDI` / `race`." The predicate names **no symbol, type,
or string** from any case. It is phrased entirely in terms of claim properties.

### 1.2 Why P3 must be a disjunction

P3a alone ("the claim lacks entailing evidence") is the obvious formulation, and
it catches Cases 1 and 2. **It misses Case 3.** In Case 3 the claim does *not*
lack evidence — the agent had instrumentation in context, and that
instrumentation **contradicted** the claim. A predicate that only asks "is there
evidence?" answers "yes" for Case 3 and waves it through. Catching Case 3
requires the separate P3b branch: "is there evidence pointing the *other* way?"

This is the single most important structural feature of the predicate, and the
"additional check required" that the verdict (§5) hinges on. A naive
evidence-gap predicate scores **2/3**. The disjunction scores **3/3**.

---

## 2. The three cases against the predicate

For each case: **(a)** the load-bearing claim, **(b)** the evidence that would
entail or falsify it, **(c)** the agent's actual evidentiary position, **(d)**
the predicate's verdict.

### 2.1 Case 1 — the `isCPU()` guard

A test NaN'd only on the Metal/GPU backend. It was meant to be skipped on Metal
and kept on CPU. The agent guarded it with
`Assume.assumeTrue(Hardware.getLocalHardware().getComputeContext().isCPU())` and
wrote that `getComputeContext()` "returns the context the block runs on —
JNI/native on Linux/CPU, Metal on the macOS node."

- **(a) Load-bearing claim *C₁*:**
  "`getLocalHardware().getComputeContext().isCPU()` reflects the **execution
  backend of this operation** — it is `true` iff the operation under test runs
  on CPU/JNI, and `false` iff it runs on Metal."
  *Dependent hunk:* the `assumeTrue(...isCPU())` guard. The guard skips the test
  exactly when *C₁* says "this is Metal." The skip is correct **only if** *C₁* is
  true.

- **(b) Entailing / falsifying evidence:** evaluate the guard expression **and**
  observe the operation's actual backend **on a machine that has both backends
  (the CI runner)**. If on a dual-backend machine `isCPU()` returns `true` while
  the operation executes on Metal, *C₁* is false. **Documentation cannot settle
  this**: the API doc describes `getComputeContext()` in general; the claim is
  about which backend a *specific operation* dispatches to on a *dual-backend*
  host. Only a probe on that configuration entails or refutes it.

- **(c) Agent's position:** only its own assertion. No captured run on a
  dual-backend host. The reality — `isCPU()` returns `true` while the op runs on
  Metal on the CI runner — was never observed. The evidence the agent *did*
  implicitly rely on (its mental model "Linux⇒CPU, macOS⇒Metal") is **evidence
  on the wrong configuration**: true-ish for single-backend hosts, false for the
  dual-backend host the decision actually runs on.

- **(d) Verdict — GATED:**
  - P1 ✓ (runtime behaviour: which backend an operation dispatches to).
  - P2 ✓ (the guard is wrong under ¬C₁ — it lets a Metal run through and the
    test NaNs).
  - P3a ✓ (**no** captured artifact entails *C₁* **on the dual-backend
    configuration**; the configuration clause of "entails" is decisive here).
  - **The probe is mandatory** — docs are insufficient. This is the case that
    forces [DESIGN.md §5](DESIGN.md)'s probe-emission capability.

### 2.2 Case 2 — anchoring the captured pitch on MIDI

Asked to persist a captured pitch, the agent added a protobuf field typed as a
**MIDI note integer**, having implicitly assumed the project's internal pitch
model is MIDI-equivalent. The project in fact has its own internal pitch
representation that is **more general** than MIDI (it can express pitches MIDI's
integer note numbers cannot). The planning document on the same branch said to
anchor on the internal representation.

- **(a) Load-bearing claim *C₂*:**
  "The captured pitch is **fully representable as a MIDI note integer** — the
  internal pitch type round-trips through a MIDI int without loss."
  *Dependent hunk:* the proto field typed as a MIDI int. It is correct **only
  if** *C₂* holds; otherwise it silently truncates every pitch the internal type
  can express but a MIDI int cannot.

- **(b) Entailing / falsifying evidence:** read the **internal pitch type's
  definition/usage** and check whether every value the capture path can produce
  maps injectively onto a MIDI int. This is **decidable from source/docs alone**
  (representational capacity is a property of the type definition — no runtime
  probe needed). Additionally, a contradicting artifact was already present: the
  branch's planning doc said to anchor on the internal representation.

- **(c) Agent's position:** never inspected the internal pitch type's value
  space; asserted MIDI-equivalence. So **P3a holds** (no entailing evidence from
  the type definition). And because the branch planning doc said otherwise, a
  **P3b flavour** is present too (a contradicting artifact in the branch
  context, though a planning doc rather than "observed output").

- **(d) Verdict — GATED:**
  - P1 ✓ (representational capacity of a type).
  - P2 ✓ (the field type is wrong under ¬C₂ — lossy persistence).
  - P3a ✓ (no captured definition/usage excerpt entails the round-trip).
  - Settled by the **symbol/consultant falsifier** ([DESIGN.md §5](DESIGN.md)) on
    docs/source — **no probe required**. Contrast with Case 1, which needs one.

### 2.3 Case 3 — the "lost-hide" race

A UI call (`showPluginUI`) hung 15s in headless CI. The agent theorised a race
in which a *hide* arrives before window registration and is lost, and built
`pendingHidePluginIds` machinery to fix it. The instrumented run showed the
**hide was received and returned cleanly**, and the presenter logged
`show: ENTRY` then **nothing** — it blocked in AU view-controller resolution
because there was no window-server session. The window-close path the fix guards
is never reached.

- **(a) Load-bearing claim *C₃*:**
  "The hide is being **lost before window registration** (a hide arrives before
  the window is registered and is dropped)."
  *Dependent hunk:* the entire `pendingHidePluginIds` mechanism. It is relevant
  **only if** *C₃* is the actual cause; under ¬C₃ it is machinery guarding a path
  that never executes.

- **(b) Entailing / falsifying evidence:** the instrumented run — already in the
  agent's context. It shows the hide **received and returned cleanly** and the
  block occurring later, inside `show`, with no window-server session. That
  output **entails ¬C₃**: the hide was not lost, and the hang is on a different
  path entirely.

- **(c) Agent's position:** it **had the contradicting evidence in context and
  chose a theory that contradicts it.** This is not an evidence gap. The agent
  was not short of information; it overrode information it already possessed.

- **(d) Verdict — GATED, via P3b specifically:**
  - P1 ✓ (causal explanation of an observed event).
  - P2 ✓ (the machinery is dead/irrelevant under ¬C₃).
  - **P3a is FALSE** here (evidence about the hide *was* present) — so a
    gap-only predicate **misses this case**.
  - **P3b ✓** (the captured instrumentation entails ¬C₃, yet the decision
    proceeds as if *C₃*). The contradiction branch is what flags it.

---

## 3. Summary table

| | Claim is about | P1 | P2 (dependent hunk) | P3 branch | Falsifier needed | Gated? |
|---|---|---|---|---|---|---|
| **Case 1** | runtime dispatch backend | ✓ | `assumeTrue(isCPU())` guard | **P3a** (gap, wrong config) | **probe on dual-backend host** | ✓ |
| **Case 2** | type representational capacity | ✓ | proto field typed MIDI-int | **P3a** (gap) [+P3b flavour] | symbol/consultant on source/docs | ✓ |
| **Case 3** | causal explanation of a hang | ✓ | `pendingHidePluginIds` machinery | **P3b** (contradiction) | re-read evidence already in context | ✓ |

The three cases exercise three different P1 facets, two different P3 branches,
and three different falsifier modalities (runtime probe / source-doc check /
re-reading in-context evidence). That spread is why the predicate has to be
phrased on properties rather than patterns.

---

## 4. False-positive profile (stated honestly)

A gate that flags everything is as useless as one that flags nothing. Here is
where the noise lives.

**What benign claims get flagged?** Any contingent behavioural claim that is
load-bearing but **obviously true** — e.g., "`List.add` appends to the end,"
used to justify an edit. It satisfies P1 (runtime behaviour) and P2
(load-bearing), and P3a asks whether entailing evidence is present. For
well-documented standard-library or framework contracts, a cheap doc/consultant
check **entails it immediately**, so the claim is **CONFIRMED and cleared, not
bounced.** The false-positive cost here is *one cheap confirmation*, not a wrong
restart. This is the intended steady state: most gated claims are confirmed
cheaply; only the genuinely unsettled or contradicted ones cost more.

**What keeps it from flagging everything?** P1 and P2 together are the
selectivity:

- **P1 excludes** task restatements, diff-evident facts, and analytic truths —
  the bulk of what an agent writes in a commit narrative.
- **P2 excludes** asides. "I suspect Metal is generally slower" is a behavioural
  claim (P1 ✓) but if **no hunk depends on it**, it is not gated. The operational
  test ("name the hunk that flips under ¬C") is a hard filter: a claim with no
  dependent hunk is dropped regardless of how speculative it sounds.

**What makes a claim "load-bearing enough"?** Precisely P2: a namable diff hunk
whose correctness inverts under ¬C. Prose confidence is irrelevant; structural
dependency is the criterion. An agent that hedges ("I think, but I'm not sure,
that X") about something a guard depends on is **still gated** (the guard depends
on X); an agent that states flatly ("X is definitely true") about something no
hunk uses is **not** gated.

**Where the real noise/cost is.** Not in false bounces — in the *confirmation
work* for true load-bearing claims. Every gated-but-true claim costs at least a
consultant/doc lookup, and the unsettleable-by-docs ones (Case 1 class) cost a
probe. The dominant expense is probes, and the dominant *risk* is **over-bouncing
on a claim that is true but cannot be confirmed from the phase's vantage point**
(e.g., a dual-backend claim when the phase runs on a single-backend node). The
mechanism must therefore distinguish **REFUTED** (bounce) from **UNSETTLED**
(annotate, bounce only under a stricter policy) — see
[DESIGN.md §6](DESIGN.md). The predicate identifies *what to scrutinise*; it does
not by itself decide *when to bounce*.

**Honest residual:** the predicate is **noisy by construction** — it flags every
load-bearing behavioural claim, true or false, for *checking*. That is
acceptable only because checking is usually cheap (docs/consultant) and bouncing
is reserved for refutation. If checking were as expensive as bouncing, the
predicate would be too noisy to use. This is the load-bearing assumption behind
the whole approach and is called out as a risk in the design.

---

## 5. Verdict

**Does the predicate catch 3/3 in a non-gameable way? — Yes, 3/3, conditional on
one structural requirement.**

- **3/3 coverage.** Case 1 (P3a, with the configuration clause of "entails"
  doing the work), Case 2 (P3a, settled from source/docs), Case 3 (P3b, the
  contradiction branch). See the table in §3.

- **The one additional check that 3/3 required.** P3 **must be a disjunction**.
  A predicate built only on the intuitive "claim lacks captured evidence" (P3a)
  catches Cases 1 and 2 and **misses Case 3** — the case where the agent had
  contradicting evidence in context and overrode it. Adding P3b ("captured
  evidence entails ¬C, yet the decision proceeds as if C") is the difference
  between 2/3 and 3/3. This is stated plainly because the entire workstream
  exists to punish exactly the move of "asserting a conclusion the evidence does
  not support" — and a 2/3 predicate dressed up as 3/3 would be that move.

- **Non-gameable *as a predicate*.** It names no symbol, type, or string from
  any case (§1.1). It is phrased on claim properties — content (P1), structural
  dependency (P2), evidentiary status (P3) — that generalise to claims it has
  never seen. It would flag the thread-safety, numerical-stability, idempotence,
  and total-order examples in §1.1 for the same reasons it flags the three
  cases.

- **The honest limit.** The predicate's *teeth* depend on the mechanism
  enforcing **genuine entailment** (the strict definition in §1), not "a command
  was run." A dishonest "I confirmed it" defeats any gate ever devised; the
  predicate cannot fix that on its own. The corresponding obligation is handed to
  [DESIGN.md §6](DESIGN.md): the phase must verify captured output **entails** the
  claim on the **right configuration**, and must report **UNSETTLED** rather than
  **CONFIRMED** when it cannot. Carrying that obligation forward honestly — rather
  than declaring victory here — is itself the discipline this experiment is meant
  to instill.

**Gate decision for Part B: PASS.** The predicate has teeth. Proceed to the
mechanism, carrying the entailment obligation and the REFUTED-vs-UNSETTLED
distinction as first-class requirements.

---

## 6. Threats to the validity of this experiment

In the spirit of the workstream, the ways this writeup could itself be wrong:

1. **Reconstructed, not replayed.** The three cases are rebuilt from the
   workstream's descriptions, not from their original branches. The descriptions
   are detailed and self-consistent, but if a description misstates what the
   agent actually claimed, the corresponding walkthrough inherits the error. The
   cases should be turned into concrete fixtures (a diff + a captured narrative +
   a captured evidence set) so the predicate can be run against them
   mechanically rather than argued in prose. That fixture work is listed in
   [DESIGN.md §7](DESIGN.md).
2. **P2 is judgement-laden.** "Name the dependent hunk" is crisp for these three
   cases but can be fuzzy when a claim supports a hunk only weakly or jointly
   with other claims. The predicate does not yet quantify *degree* of
   load-bearingness; it treats P2 as boolean.
3. **"Most simply explained by ¬C" (P3b) imports an inference-to-best-explanation
   step** that is not purely mechanical. For Case 3 it is unambiguous (the log
   shows the block elsewhere). For subtler contradictions it could be contested,
   which re-introduces exactly the kind of judgement call the gate is trying to
   discipline. The mechanism mitigates this with adversarial verification
   ([DESIGN.md §6](DESIGN.md)), not with a claim of mechanical certainty.
4. **Selection effect.** Three cases are a small test set, hand-picked as clear
   failures. The false-positive profile (§4) is reasoned, not measured. Only a
   run over many real job diffs would quantify the true noise rate.
