# Falsification Gate

**Status:** Planning (design + experiment). No harness/controller code in this
work — design and experiment only.
**Branch:** `feature/falsification-gate`

## The problem this addresses

A specific, repeatedly-observed failure mode in our coding agents: the agent
mints a plausible claim about runtime **behaviour**, never runs the experiment
that would falsify it, and then — at a later decision point distant from where the
claim was minted — treats its own conjecture as established fact. The distance
between mint-site and use-site launders conjecture into "fact." It is **not** an
information shortage (the codebase is heavily documented); it is that load-bearing
claims go **unfalsified**.

Two levers are pulled:

- **A structural gate** that refuses to advance while a load-bearing behavioural
  claim lacks captured evidence that *entails* it (or contradicts it).
- **Proximity** — re-presenting the relevant concern *at the decision site*, on
  the strong prior that proximate corrections are respected where distant standing
  rules are ignored.

## The two documents

1. **[EXPERIMENT.md](EXPERIMENT.md) — Part A (gates Part B).** States a *general*
   predicate for "a load-bearing claim that must be rejected pending
   falsification," walks all three real failure cases against it, gives the
   false-positive profile honestly, and renders a verdict. **The verdict is the
   gate on Part B** — if the predicate did not catch the cases non-gameably, the
   mechanism would not be justified.

2. **[DESIGN.md](DESIGN.md) — Part B (the mechanism).** Proposes a new agent-job
   **falsification phase** that runs after primary and before review and — unlike
   review (files follow-ups only) and retrospective (memories only) — can **bounce
   the job back to primary**. Grounded in this repo's actual phase/restart/config
   code, with every integration point cited and **nothing modified**.

## Part A verdict (the gate)

**PASS — the predicate catches 3/3, non-gameably, conditional on one structural
requirement.**

The predicate gates a claim *C* iff: **(P1)** *C* is a contingent-empirical claim
about system behaviour (runtime behaviour / representational capacity / causal
explanation), **and (P2)** a namable diff hunk is correct only if *C* is true,
**and (P3)** either no captured artifact *entails* *C* on the relevant
configuration (**P3a**, evidence gap) **or** a captured artifact *entails ¬C* yet
the decision proceeds anyway (**P3b**, evidence contradiction).

- Case 1 (`isCPU()` guard) → gated via **P3a**, and only a **probe on a
  dual-backend host** can settle it (docs are constitutively insufficient).
- Case 2 (MIDI anchor) → gated via **P3a**, settled from **source/docs** (no
  probe).
- Case 3 (lost-hide race) → gated via **P3b** — the agent had contradicting
  evidence in context and overrode it.

**The structural requirement:** P3 *must* be a disjunction. A naive
"claim-lacks-evidence" predicate (P3a only) catches Cases 1 and 2 but **misses
Case 3**, scoring 2/3. The contradiction branch (P3b) is the difference between
2/3 and 3/3.

**The honest carry-forward:** the predicate's teeth depend on the mechanism
enforcing *genuine entailment* ("the output shows X, and X is false unless *C*")
rather than "a command was run." That obligation — and the discipline of
reporting **UNSETTLED** rather than fabricating **CONFIRMED** — is handed to
[DESIGN.md §6](DESIGN.md).

## Key grounding anchors (read from the working tree on this branch)

| Concern | Where |
|---|---|
| Phase inventory + enum | `flowtree/agents/.../jobs/agent/Phase.java`; `flowtree/docs/architecture/PHASES.md` |
| `doWork()` sequence (primary → enforcement → retrospective) | `flowtree/runtime/.../jobs/CodingAgentJob.java:1056-1073` |
| Bounce-to-primary precedent | `CodingAgentJob.onGitTampering():1183-1211` |
| Proximity mechanism (3 existing "restart preambles" prepended above all content) | `InstructionPromptBuilder.java:422 / 446 / 477` |
| Review = "files follow-ups" | `ReviewPromptBuilder.java:95-101` (BIAS STATEMENT) |
| Retrospective = memories only, no bounce | `RetrospectivePhase.java`; `CodingAgentJob.java:1096-1099` |
| Probe-command primitive | `PostCompletionCommandRule.java:286-329` (`sh -c`, timeout, tail-capped capture) |
| Closest precedent for adding a phase | `docs/plans/SELF_IMPROVEMENT_PHASE.md` |

## Note on document durability

Per [`docs/plans/CLAUDE.md`](../CLAUDE.md), these are temporary working documents.
Code, tests, and durable docs must **not** reference them; these documents may
freely reference code. When/if the falsification phase lands, the durable record
belongs in `flowtree/docs/architecture/PHASES.md` (a new row), not here.
