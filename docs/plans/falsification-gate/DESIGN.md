# Falsification Gate — Part B: The Mechanism

**Status:** Implemented (v1, artifact-settleable; probes deferred)
**Branch:** `feature/falsification-gate`
**Document version:** 1
**Gated by:** [EXPERIMENT.md §5](EXPERIMENT.md) — verdict **PASS** (predicate
catches 3/3, conditional on the disjunctive P3 and on the mechanism enforcing
genuine entailment). This document carries the entailment obligation and the
REFUTED-vs-UNSETTLED distinction as first-class requirements.

**Scope of this job:** v1 implementation complete. The runtime
`FalsificationPhase` and supporting gate, verdict, and claim classes are
implemented; `falsificationEnabled` is wired through the job API, codec,
factory, server.py, and FlowTreeApiEndpoint. Probe emission is deferred to v2.
Original integration-point citations below remain for reference; items marked
**[INFERRED]** were inferred from the working tree at design time.

---

## 0. The idea in one paragraph

Insert a new agent-job phase — **falsification** — that runs **after the primary
session and before the review phase**. Unlike review (which can only file
follow-up memories and `TODO(review):` comments) and unlike the retrospective
phase (which can only emit memories), the falsification phase can **bounce the
job back to a fresh primary run.** It ingests the diff plus the agent's own
commit narrative, extracts the load-bearing behavioural claims, applies the
[Part-A predicate](EXPERIMENT.md), and tries to *falsify* each gated claim using a
small toolbox of falsifiers (one of which can emit and run a probe command). When
a load-bearing claim is **refuted** — or is **unsettled** under a strict policy —
the phase restarts the primary phase with the refutation injected **at the very
top of the next primary context**, so the correction is *proximate* to the
decision being redone. Proximity is the design's load-bearing bet, and it is
already proven to work in this codebase (§4).

---

## 1. Where the phase sits, and why that is the hard case

### 1.1 The current `doWork()` sequence (read, not assumed)

`CodingAgentJob.doWork()` (`flowtree/runtime/src/main/java/io/flowtree/jobs/CodingAgentJob.java:1056`):

```java
protected void doWork() {
    if (sessionStartedAt == null) sessionStartedAt = Instant.now();
    executeSingleRun();                              // PRIMARY              (1058)
    primaryPhaseHardFailed = isHardPrimaryFailure();  //                    (1062)
    if (!hasAgentCommitted()) {
        runEnforcementRules();                       // review = first default-on rule (1066)
    }
    retrospective.reset();                            //                    (1069)
    if (retrospectiveEnabled) {
        runReflectionPhase();                        // terminal, no bounce (1071)
    }
}
```

Enforcement-rule order is built in
`EnforcementRunner.buildActiveRules()`
(`flowtree/runtime/.../jobs/EnforcementRunner.java:66`): `enforce-changes`,
**`review`**, `deduplication`, `organizational-placement`, `post-completion`,
`maven-dependency`, custom rules (`rules.addAll(job.getCustomEnforcementRules())`,
line 90), and `commit-message` last (92-94). `PHASES.md` confirms review "sits
between `ENFORCE_CHANGES` and `DEDUPLICATION`"
(`flowtree/docs/architecture/PHASES.md:148`).

So **"after primary, before review"** means: between line 1062 and line 1065 of
`doWork()` — *before* `runEnforcementRules()` is entered.

### 1.2 Why this phase is structurally novel (and harder than retrospective)

Two existing phases bracket the design space:

- **Review** (`ReviewRule` + `ReviewPromptBuilder`) runs *inside* the enforcement
  loop and is deliberately neutered: its prompt's BIAS STATEMENT is "When in
  doubt, DEFER. A reviewer that files a useful memory and a useful TODO comment
  is a successful reviewer even if they change zero lines"
  (`ReviewPromptBuilder.java:95-101`). It can make surgical edits or file
  `review-followup` memories — it **cannot** send the job back to redo primary.
- **Retrospective** (`RetrospectivePhase`) runs *after* the enforcement loop as a
  terminal hook and emits **memories only**; the orchestrator's own javadoc says
  it "produces memories, not code changes, so it cannot trigger re-entry into the
  enforcement loop even when `hasAgentCommitted()` returns `false`"
  (`CodingAgentJob.java:1096-1099`).

The falsification phase needs the **opposite** of both: the power to **bounce the
whole job back to primary.** That power already exists in the codebase, but only
on two narrow paths — `EnforceChangesRule` (re-run primary when no code changed)
and `onGitTampering()` (re-run primary after destroying tampered state). §3 shows
the falsification phase is a *third* instance of that same bounce primitive,
generalised to "a load-bearing claim was refuted."

### 1.3 Activation, runner routing, telemetry — free from the existing ladder

Because the phase is addressed by a `Phase` enum value, the entire per-phase
configuration ladder applies with no new plumbing:

- **Runner/model/effort/provider** resolve through
  `resolveEffectivePhaseConfig(Phase.FALSIFICATION)`
  (`CodingAgentJob.java:1407` → `PhaseConfigBundle.forPhase`,
  `flowtree/agents/.../agent/PhaseConfigBundle.java:93`) and the 8-level routing
  precedence ladder documented in `PHASES.md:47-56`.
- **Opt-in** mirrors `retrospectiveEnabled` /  `reviewEnabled`: a job-level
  boolean, a `workstream_submit_task` parameter, and per-phase config. The
  retrospective precedent wires `retrospective_enabled: bool = False` at
  `tools/mcp/manager/server.py:1807` and threads it through
  `FlowTreeApiEndpoint`, `CodingAgentJobFactory`, `CodingAgentJobEvent`, and
  `CodingAgentJobCodec` (all four matched on `retrospectiveEnabled`). The
  falsification phase follows the same five-file path **[INFERRED from the
  retrospective precedent — those four Java files were located by grep but their
  internals were not all read in this job]**.

---

## 2. What the phase ingests

The phase ingests three things; the first two are the same inputs review already
assembles, so the extraction code has a working template
(`ReviewPromptBuilder.build`, `ReviewPromptBuilder.java:73-86`):

1. **The diff** — branch-vs-base plus uncommitted working tree, via
   `GitOperations.captureBranchDiff(...)` (the exact call
   `ReviewPromptBuilder.appendDiff` uses, `ReviewPromptBuilder.java:237-258`),
   truncated head+tail like review's 60k cap.
2. **The agent's own commit narrative / claims.** Primary sources:
   - `commit.txt` (the agent's own statement of *what it changed and why*),
     resolved via `job.resolveWorkingPath("commit.txt")` (used throughout
     `CodingAgentJob`, e.g. 1126).
   - The primary session's transcript/output — either `accumulator.getOutput()`
     (raw stdout the orchestrator already retains, exposed via `job.getOutput()`)
     or, when the primary ran on opencode, the transcript-analyzer tools the
     retrospective phase already uses (`SELF_IMPROVEMENT_PHASE.md §2.3`).
3. **Contradiction sources** for the P3b branch: the branch planning doc
   (`planningDocument`) and prior memories (`memory_recall` /
   `workstream_context`). Case 2's "the planning doc said to anchor on the
   internal representation" is exactly this signal; Case 3's contradicting
   instrumentation lives in source (2) above.

The phase is therefore an **agent session** (like review and retrospective), not
a static analyzer — extracting "the behavioural claim this hunk depends on" and
judging entailment are model-judgement tasks. It runs through the same
`executeSingleRun()` primitive every phase uses.

---

## 3. The mechanism

Implemented as a **separate collaborator class `FalsificationPhase`**, mirroring
`RetrospectivePhase`, plus a `FalsificationPromptBuilder` mirroring
`ReviewPromptBuilder` / `RetrospectivePromptBuilder`. It is **not** inline methods
on `CodingAgentJob`: that file is **1598 lines, over the 1500 soft limit** (the
file-length advisory fires on every read of it), so new orchestration logic must
live in its own file. This is a real, observed constraint, not a stylistic
preference.

### 3.1 The four steps

```
FalsificationPhase.run(job):
  1. EXTRACT   — one agent session ingests §2 inputs, emits a structured list of
                 (claim, dependent-hunk, P1-facet) tuples. Drops anything failing
                 P1 or P2. Writes falsification-results.json.
  2. CLASSIFY  — for each surviving claim, mark P3a (gap) or P3b (contradiction).
  3. FALSIFY   — run the falsifier toolbox (§5) per claim; each yields a verdict
                 CONFIRMED | REFUTED | UNSETTLED plus the captured evidence and the
                 configuration it was gathered on.
  4. DECIDE    — if any load-bearing claim is REFUTED (or UNSETTLED under the
                 strict policy, §6), BOUNCE (3.3). Otherwise, file the confirmations
                 /annotations as memories and return without bouncing.
```

The structured result file (`falsification-results.json`) mirrors
`RetrospectivePhase.RESULTS_FILE = "retrospective-results.json"`
(`RetrospectivePhase.java:50`, read back at 169-185) so the orchestrator can read
per-claim verdicts the same missing-tolerant way.

### 3.2 The analysis session itself (no bounce)

Steps 1-3 run as one or more agent sessions tagged with the falsification phase,
exactly as the retrospective swaps prompt + activity and calls
`executeSingleRun()` (`RetrospectivePhase.java:128-154`):

```java
// FalsificationPhase.run — analysis portion, modelled on RetrospectivePhase.run
String originalPrompt = job.getPrompt();
String previousActivity = job.getCurrentActivity();
job.setCurrentActivity(Phase.FALSIFICATION.wireName());   // routes runner/model
try {
    job.setPrompt(FalsificationPromptBuilder.build(job));
    job.executeSingleRun();                                // analysis session
    readResults(job);                                      // parse falsification-results.json
} finally {
    job.setPrompt(originalPrompt);
    job.setCurrentActivity(previousActivity);
}
```

This reuses the **key invariant** from `SELF_IMPROVEMENT_PHASE.md §9.3`: a phase
that is not producing a commit must use `executeSingleRun()` with a swapped
prompt, **not** `runCorrectionSession()` (which carries `commit.txt`
save/restore semantics oriented around producing commits,
`CodingAgentJob.java:1120-1153`).

### 3.3 The bounce — the distinctive power

When step 4 decides to bounce, it does **not** run a constrained correction
session. It re-runs **primary**, with the refutation prepended to the top of the
prompt. The primitive already exists: `onGitTampering()`
(`CodingAgentJob.java:1183-1211`) sets a field, re-runs `executeSingleRun()`, and
clears the field in `finally`:

```java
// onGitTampering(), the existing precedent (abridged)
gitTamperingViolation = violation;                        // 1192 — read by buildInstructionPrompt
currentActivity = Phase.GIT_TAMPERING_RESTART.wireName(); // 1199 — runner routing
try {
    executeSingleRun();                                   // 1203 — fresh PRIMARY-style re-run
} finally {
    currentActivity = previousActivity;                  // 1205
    gitTamperingViolation = null;                        // 1207 — don't leak into later retries
}
```

The falsification bounce is the same shape:

```java
// FalsificationPhase bounce — modelled exactly on onGitTampering()
void bounceToPrimary(CodingAgentJob job, String findingsBlock) {
    job.setFalsificationFindings(findingsBlock);  // NEW field; read by buildInstructionPrompt
    // Redo runs as PRIMARY (cleared activity) so it gets the primary runner/model/effort
    // and the full primary preamble — it IS redoing the primary work, not a correction.
    String previousActivity = job.getCurrentActivity();
    job.setCurrentActivity(null);
    try {
        job.executeSingleRun();
    } finally {
        job.setCurrentActivity(previousActivity);
        job.setFalsificationFindings(null);       // clear so it doesn't leak into later phases
    }
}
```

Two deliberate differences from `onGitTampering()`:

- **Activity is cleared (PRIMARY), not set to the falsification wire name**, for
  the *redo*. The analysis session (3.2) runs as the falsification phase so it can
  be routed to a skeptical model; the **redo is genuine primary work** and must
  get the primary runner/model and the full primary preamble. (Note: a non-empty
  `currentActivity` makes `buildInstructionPrompt` treat the session as a
  correction session and suppress the primary preambles —
  `CodingAgentJob.java:977`, `InstructionPromptBuilder.setCorrectionSession`,
  `InstructionPromptBuilder.java:372`. Clearing it avoids that.)
- **It is bounded by a bounce cap** (§6) — `onGitTampering` relies on the
  destroy-and-restart being self-limiting; a falsification bounce must not loop
  forever if the agent keeps re-asserting the refuted claim.

### 3.4 The restart contract — and why it must reach `runEnforcementRules`

`doWork()` calls `runFalsificationPhase()` *before* `runEnforcementRules()`. The
bounce re-runs primary **inside** the falsification phase. When the phase returns,
`doWork()` continues to `runEnforcementRules()` against the **redone** tree — so
review/dedup/placement all see the corrected work, which is the desired ordering
("after primary, before review"). The phase may iterate steps 1-4 on the redone
tree up to the bounce cap; each iteration's findings **accumulate** into the
`findingsBlock` so a re-asserted refuted claim is increasingly conspicuous.

---

## 4. Proximity — the part backed by hard evidence

The workstream's strongest prior: a standing rule ("don't write files >1600
lines") was *universally ignored* as a distant instruction but *almost
universally respected* once delivered as an in-context warning immediately after
the edits. The falsification redo applies the same lesson: the refutation is
delivered **at the very top of the next primary context**, right where the
decision will be re-made.

This mechanism already exists and already works in this codebase.
`InstructionPromptBuilder.build()` prepends **"restart preambles" above all other
content** — there are three today:

- git-tampering violation warning (`InstructionPromptBuilder.java:422-439`),
- inactivity-timeout warning (446-469),
- enforcement-retry warning (477-491).

Each is gated on a field set by the orchestrator just before the re-run, and each
appears **before the opening paragraph** so the agent reads it first. The
falsification findings block is a **fourth such preamble**:

```java
// InstructionPromptBuilder.build(), new block prepended alongside the existing three
if (falsificationFindings != null && !falsificationFindings.isEmpty()) {
    sb.append("## !! SESSION RESTARTED -- A LOAD-BEARING CLAIM WAS REFUTED !!\n\n");
    sb.append(falsificationFindings);    // claim, dependent hunk, captured evidence, config
    sb.append("\n\nDo NOT re-assert this claim without capturing evidence that ");
    sb.append("entails it on the configuration the decision runs under. ");
    sb.append("Reconcile your approach with the evidence above before proceeding.\n\n");
    sb.append("---\n\n");
}
```

Wiring is one line in `CodingAgentJob.buildInstructionPrompt()` (which already
calls `.setGitTamperingViolation(gitTamperingViolation)` and
`.setInactivityRestartAttempt(...)`, `CodingAgentJob.java:996-997`):
`.setFalsificationFindings(falsificationFindings)`.

The **findings block content** is the proximity payload and must be concrete:

```
CLAIM (from your prior attempt): "<the load-bearing claim, verbatim or paraphrased>"
THIS CODE DEPENDS ON IT: <file:line of the dependent hunk> — <why it is wrong if the claim is false>
WHAT WE CAPTURED: <command run + exit + relevant output tail> OR <doc/source excerpt> OR <the in-context evidence you contradicted>
CONFIGURATION: <the config the evidence was gathered on; and the config the decision runs under>
VERDICT: REFUTED — the captured evidence is inconsistent with the claim.
```

That this preamble mechanism is *already* how the codebase delivers
git-tampering, inactivity, and enforcement-retry corrections is the single best
piece of evidence that proximity works here — we are not inventing a delivery
channel, we are adding a fourth message to a channel with a proven track record.

---

## 5. The cross-codebase symbol falsifier (one falsifier, not the whole design)

The phase has a small toolbox of falsifiers; the cross-codebase symbol check is
**one** of them. It exists because two of the three cases turn on what a symbol
*actually does* versus what the agent *assumed* it does.

### 5.1 Doc/source check

Given the symbols a dependent hunk relies on (e.g.
`getLocalHardware().getComputeContext().isCPU()` in Case 1; the internal pitch
type in Case 2), pull their definition, usage, and documentation and ask: **does
the agent's stated assumption about this symbol match what it actually does?**
Two retrieval paths, both already available to an agent session:

- `mcp__ar-consultant__consult(question, keywords=[<symbol names>])` — the
  project consultant, which returns doc-grounded answers (this is the same tool
  CLAUDE.md Rule 1 mandates, and the one used to seed this very design).
- Direct `Grep`/`Read` of the symbol's definition and call sites.

**This settles Case 2 outright.** Representational capacity is a property of the
type definition; reading the internal pitch type's value space and comparing it to
MIDI's integer range *entails or refutes* "the captured pitch round-trips through
a MIDI int." No runtime execution is needed.

**This does *not* settle Case 1.** The doc for `getComputeContext()` describes the
API in general; the claim is about which backend a *specific operation* dispatches
to on a *dual-backend host*. Documentation is **constitutively insufficient** for
a runtime-dispatch claim. That is the trigger for probe emission.

### 5.2 Probe emission — when docs cannot settle a runtime claim

When the symbol check cannot entail/refute a **runtime-behaviour** claim, the
falsifier may **emit a probe command**: a minimal command or test whose captured
output would entail or refute the claim. For Case 1, a probe that, on a
dual-backend machine, prints both the guard expression `isCPU()` **and** the
operation's actual execution backend — the two values whose disagreement *is* the
refutation.

The phase then **runs the probe** using the command-execution primitive the
codebase already has in `PostCompletionCommandRule`
(`flowtree/runtime/.../jobs/PostCompletionCommandRule.java`): launch via
`sh -c` (line 291), redirect combined stdout+stderr to a temp file (296), enforce
a wall-clock timeout with `waitFor(timeoutSeconds, ...)` (302), and read back the
tail capped at `MAX_OUTPUT_CHARS = 8000` (63, 342-358). The captured exit code +
output tail become the evidence attached to the claim's verdict. The
"show the command, the exit code, and the output" correction shape is exactly
`PostCompletionCommandRule.buildCorrectionPrompt` (214-249) — the same shape the
findings block (§4) uses.

### 5.3 The cost of probe emission (flagged explicitly, per the owner's decision)

The owner has accepted higher cost now for a better catch rate, deferring
overhead reduction. Designing for catch rate first, here are the expensive parts,
named so they are not discovered later:

- **Compute cost.** A probe is a full compile+run (Case 1's probe needs the module
  built). For a code-heavy probe this is minutes and real money per gated claim —
  and there may be several gated claims per job.
- **Configuration-matching cost — the dangerous one.** A probe only entails the
  claim **if it runs on the configuration the decision runs under.** Case 1's
  probe is meaningless on a CPU-only node: running it there would print
  `isCPU()=true`, the operation would run on CPU, and the probe would *confirm*
  the claim — **reproducing the exact original bug** ("evidence on the wrong
  configuration"). So a config-specific probe must be routed to a matching node,
  or it must not be run at all and the claim reported **UNSETTLED**. Routing
  probes to matching nodes is **out of scope here and flagged as the single
  biggest open cost** (§7).
- **Trust boundary cost.** `PostCompletionCommandRule`'s own javadoc states its
  command is trusted submitter input and "is **NOT a sandbox boundary**"
  (`PostCompletionCommandRule.java:31-35`). A probe **emitted by the falsification
  agent** is *agent-authored*, not submitter-authored — so reusing that mechanism
  crosses a trust boundary the original explicitly assumed away. Probe emission
  needs at least an allow-list / review step that post-completion commands do not.
  This is a security delta, not just a cost delta (§6, §7).

Mitigations (caching probe results, an allow-list, running probes only for
REFUTED-or-UNSETTLED load-bearing claims, config-aware routing) are **deferred by
the owner's explicit cost decision** and listed in §7. The design optimises catch
rate first.

---

## 6. The anti-gaming requirement: entailment, not "a command was run"

[EXPERIMENT.md §5](EXPERIMENT.md) handed this document a non-negotiable
obligation: a gate that demands "show the command that proves it" is gamed by an
agent that runs *a* command and writes a plausible story about output that does
not actually entail the claim. The phase must check that captured output
**entails** the claim, not merely that some command ran.

Four mechanisms, in increasing strength:

1. **Truth-condition binding.** A probe is only accepted if the falsifier states,
   *before running it*, the claim's truth condition in terms of the probe's
   output ("the claim is true iff this probe prints `backend=CPU`"). The verdict
   is then a mechanical comparison of expected-vs-actual, not a prose summary. A
   probe with no pre-stated truth condition yields **UNSETTLED**, never CONFIRMED.
2. **Configuration tagging.** Every captured artifact is tagged with the
   configuration it was gathered on; the claim carries the configuration the
   decision runs under. A mismatch forces **UNSETTLED** regardless of what the
   output says. This is the mechanical encoding of the "entails … on the relevant
   configuration" clause from [EXPERIMENT.md §1](EXPERIMENT.md), and it is exactly
   what Case 1 needed.
3. **Adversarial verification.** A *second*, independent verifier session is
   prompted to **refute the entailment**: "find a reading of this captured output
   under which the claim is still false." If it succeeds, the verdict is
   downgraded to UNSETTLED. This mirrors the adversarial-verify pattern the
   project already uses elsewhere (independent skeptics that default to "not
   proven"). The verifier judges *the entailment*, not the original claim — it is
   cheaper and more focused than re-deriving the claim.
4. **Conservative bounce policy.** Bounce on **REFUTED**. For **UNSETTLED**, the
   default is to **annotate** (file a `falsification-followup` memory + a findings
   note) rather than bounce — *except* when the claim is both load-bearing and
   high-risk (e.g., a guard that silently disables a test), where the policy may
   escalate UNSETTLED to a bounce. This threshold is an explicit operator knob
   (§7), because over-bouncing on true-but-unconfirmable claims is the dominant
   false-positive cost identified in [EXPERIMENT.md §4](EXPERIMENT.md).

The unavoidable honest limit: the falsification agent could *itself* mint an
unfalsified claim about entailment ("yes, this output entails it"). Mechanisms 1
and 2 are mechanical (a pre-stated truth condition is a string match; a config
mismatch is a string compare) and are not subject to the agent's narrative;
mechanisms 3 and 4 reduce, but cannot eliminate, the residue. The design's
posture toward that residue is the same one the experiment took: **report
UNSETTLED rather than fabricate CONFIRMED.** A phase that honestly says "I could
not settle this on the available configuration" is doing its job; a phase that
manufactures confidence is the very failure mode it was built to stop.

---

## 7. Integration map (cited; nothing modified in this job)

Concrete slots, every one read from the working tree. **None are edited here.**

| # | File:symbol | Change the phase would require | Read or [INFERRED] |
|---|---|---|---|
| 1 | `flowtree/agents/.../agent/Phase.java:36` (enum) | Add `FALSIFICATION("falsification", "...")`; add `case "falsification": return FALSIFICATION;` to `fromRuleName()` (184-199) | Read |
| 2 | `CodingAgentJob.doWork():1056-1073` | Insert `if (falsificationEnabled) runFalsificationPhase();` **between** primary (1062) and `runEnforcementRules()` (1065) | Read |
| 3 | new `FalsificationPhase` + `FalsificationPromptBuilder` in `flowtree/runtime/.../jobs/` | New collaborators modelled on `RetrospectivePhase` / `ReviewPromptBuilder`; **not** inline (CodingAgentJob is 1598 lines, over the 1500 soft limit) | Read |
| 4 | `CodingAgentJob.onGitTampering():1183-1211` | The bounce primitive to copy (set field → `executeSingleRun()` → clear in `finally`) | Read |
| 5 | `InstructionPromptBuilder.build():415-491` | Add a 4th restart preamble + `falsificationFindings` field + `setFalsificationFindings()` setter, prepended like 422/446/477 | Read |
| 6 | `CodingAgentJob.buildInstructionPrompt():976-998` | One line: `.setFalsificationFindings(falsificationFindings)` | Read |
| 7 | `resolveEffectivePhaseConfig():1407` + `PhaseConfigBundle.forPhase` | Works for free once the enum value exists; routing via `PHASES.md:47-56` ladder | Read |
| 8 | probe runner | Reuse `PostCompletionCommandRule.runCommand():286-329` (`sh -c`, timeout, tail-capped capture). **Caveat:** "no utility classes" (CLAUDE.md) — the command-run behaviour is currently private to `PostCompletionCommandRule`; lifting it to a shared owner is an open design decision (§7), not a free reuse | Read |
| 9 | activation: `server.py:1807`, `FlowTreeApiEndpoint`, `CodingAgentJobFactory`, `CodingAgentJobEvent`, `CodingAgentJobCodec` | Add `falsificationEnabled` mirroring `retrospectiveEnabled` across the same five files | `server.py:1807` Read; the four Java files located by grep but **[INFERRED]** from the retrospective precedent, not all read |
| 10 | telemetry: `CodingAgentJobEvent` + `PHASES.md:221-254` | Add falsification fields (ran, claims-extracted, refuted, bounced, probesRun, costUsd) like `reviewInfo`/reflection fields | **[INFERRED]** from retrospective/review telemetry precedent |
| 11 | durable doc `flowtree/docs/architecture/PHASES.md:19-29` | Eventually add a `FALSIFICATION` row — a durable-doc change, **explicitly out of scope for this design+experiment job** | Read |

The **restart-to-primary path** touches, concretely: `executeSingleRun()` (the
re-run, 1218), `buildInstructionPrompt()` / `InstructionPromptBuilder` (the
preamble), `currentActivity` (cleared for the redo), a new bounce-cap field, and
the cost tracker (each redo accrues cost via `absorbResult` +
`costTracker.record`, 1277-1278). All read; none modified here.

---

## 8. Open design decisions and risks

Beyond the gaming risk (which §6 addresses directly):

1. **Bounce threshold.** REFUTED-only, or also UNSETTLED-load-bearing-high-risk?
   (§6.4.) The conservative default is REFUTED-only; the looser policy catches
   more but risks looping on unconfirmable-but-true claims.
2. **Bounce cap.** What value, and graceful degradation when hit. Precedent:
   `DEFAULT_MAX_RULE_ENTRIES = 10` (`CodingAgentJob.java:1053`),
   `DEFAULT_MAX_TOTAL_ENFORCEMENT_ATTEMPTS = 25` (1041), and the
   exhaustion-fallback pattern in `EnforcementRunner` (183-205) that writes a
   usable result and retires the rule rather than looping. The falsification phase
   should, on cap-hit, downgrade to a `falsification-followup` memory plus
   `job.harnessStatus().unusual(...)` (the codebase's "something abnormal
   happened" signal, used at `EnforcementRunner.java:189`) rather than block the
   job forever.
3. **Default runner/model for the phase.** Skepticism benefits from a strong
   model, and routing the *redo* differently from the *analysis* is already a
   supported idea — `PHASES.md:104-112` justifies `GIT_TAMPERING_RESTART` as a
   separate phase precisely so operators can route a restart to a different model.
   The same argument says: route the falsification *analysis* to a strong
   skeptical model; route the *redo* to the primary model (it is primary work).
4. **Claim-extraction boundary.** How much to trust the agent's `commit.txt`
   narrative vs. reconstructing claims from the diff. A terse or dishonest
   narrative hides the very claims that need gating; extraction may have to infer
   load-bearing claims from the diff directly when the narrative is thin.
5. **Probe security / sandboxing.** Agent-emitted probes cross the trust boundary
   `PostCompletionCommandRule` assumes away (§5.3). Needs an allow-list or a
   sandbox before probe emission ships. **Do not ship probe emission without it.**
6. **Probe configuration routing.** The biggest deferred cost: a runtime probe is
   only valid on the configuration the decision runs under (Case 1 ⇒ dual-backend
   node). Without routing, config-specific claims can only ever be **UNSETTLED**
   from a non-matching node — which is the *correct* honest answer, but limits the
   catch rate for the Case-1 class until routing exists.
7. **Cost.** Probes are expensive (§5.3); the owner accepted this for now.
   Overhead reduction (caching, dedup of identical claims across bounces,
   skipping probes when a doc check already settled the claim) is deferred.
8. **Interaction with `enforce-changes`.** The bounce re-runs primary, which may
   itself produce "no changes" and feed the existing `EnforceChangesRule`. The
   ordering (falsification before enforcement) means a falsification redo happens
   *before* enforce-changes is consulted; the two loops must be kept from
   compounding (the bounce cap and the total-attempt cap together bound this).
9. **Fixtures.** [EXPERIMENT.md §6](EXPERIMENT.md) flagged that the three cases
   are reconstructed, not replayed. Before implementation, build each as a
   concrete fixture — a diff + a captured narrative + a captured evidence set —
   and assert that `FalsificationPhase` extraction + predicate classification
   reaches the §3-summary verdicts (REFUTED for Case 3, REFUTED-after-probe for
   Case 1, REFUTED-from-source for Case 2). Test location would mirror
   `CodingAgentJobRetrospectiveTest` / `ReviewRuleTest`.

---

## 9. Honest statement of what this design does not yet prove

In the spirit of the workstream — this design must not itself assert how the
harness works without checking, and must be explicit about uncertainty:

- The activation wiring across `FlowTreeApiEndpoint`, `CodingAgentJobFactory`,
  `CodingAgentJobEvent`, and `CodingAgentJobCodec` is **[INFERRED]** from the
  `retrospectiveEnabled` precedent (those files were located by grep but not all
  read end-to-end in this job). Before implementation, read each and confirm the
  five-file path.
- The claim that probe emission can be made safe is **not** established here; §5.3
  and §8.5 flag it as an unresolved security question, and the design says
  explicitly: **do not ship probe emission without an allow-list/sandbox.**
- The proximity bet (§4) is supported by the existing three restart preambles and
  by the workstream's 1600-line evidence, but its effectiveness *for refuted
  behavioural claims specifically* is unmeasured. The fixtures (§8.9) are the way
  to measure it before trusting it.
- Everything in §7 marked "Read" was read on this branch; everything marked
  **[INFERRED]** was not. The distinction is preserved deliberately so a future
  reader does not inherit an unverified claim dressed as a verified one — which is
  the exact laundering this whole workstream exists to prevent.
