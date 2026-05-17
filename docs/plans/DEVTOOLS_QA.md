# DevTools QA: Test-Verification Guardrail and Agent Instruction Improvements

## Category

Code Quality / Developer Tooling

## Motivation

Coding agents have a documented pattern of declaring work complete without running the
tests that exercise their changes. The full project test suite takes hours, so agents
either skip testing entirely or run `mvn clean install -DskipTests` and call that
sufficient. This produces merges that break CI in ways that could have been caught in
minutes by running the one or two test classes directly relevant to the change.

This plan adds a clear, prominent test-verification guardrail to every surface where
agent instructions appear: the root `CLAUDE.md`, module-local `CLAUDE.md` files that
already cover testing, the FlowTree harness prompt (`InstructionPromptBuilder`), and
a planned `.claude` rules addition for the project owner to apply manually.

## Scope

Changes delivered by the coding agent sessions on this branch:

1. **Root `CLAUDE.md`** — new `## CRITICAL: Verify Changes Against Relevant Tests Before
   Declaring Done` section inserted immediately before the existing
   `## Validate Code Quality Before Completing Any Task` section.  Includes the full
   five-step heuristic and the `-DskipTests` prohibition.

2. **`engine/ml/CLAUDE.md`** — brief echo of the rule (ML-specific: do not run the full
   ML suite, target the test class for the changed file, run `pytest` for Python changes).

3. **`flowtree/core/src/main/java/io/flowtree/jobs/InstructionPromptBuilder.java`** — added
   the test-verification rule as an unconditional section in the generated system prompt,
   inserted between the Branch Awareness section and the Budget section so it is visible
   to every coding agent job regardless of which `CLAUDE.md` the agent reads first.

4. **This planning document** (`docs/plans/DEVTOOLS_QA.md`) — also covers the `.claude`
   rules update the project owner must apply manually (see below).

## `.claude` Rules Update — Apply Manually

The project owner should add the following rule to the `.claude` project rules.  These
rules are managed outside the repository and cannot be edited by agents.

### Exact rule text

```
## CRITICAL: Run the relevant tests before declaring work complete

The full test suite takes hours. For every file you modify, find and run the tests that
directly exercise that file before declaring the work done.

**Heuristic:**
1. For `Foo.java` in module `bar`, run `FooTest`, `FooTests`, or `FooIT` from the same
   module's `src/test/java`.  Use the ar-test-runner MCP tool:
   `mcp__ar-test-runner__start_test_run module:"bar" test_classes:["FooTest"]`
2. Also run any tests in the same package that import the file you changed.
3. For Python changes in `tools/`, run the corresponding pytest module:
   `python -m pytest tools/mcp/manager/test_server.py`
4. Do NOT use `-DskipTests` to declare a fix complete; that flag is only for the
   compile-check pass.

If a test fails, fix the production code.  Do not add `@Disabled`, remove assertions,
or weaken tolerances.
```

### Where it should go

Add it as a new rule block in the **Testing** section of the `.claude` rules, directly
after any existing rule about running tests.  If there is no Testing section yet, create
one at the top of the rules file (so it appears before project-specific conventions).

### How it should fire

**Always** — this rule must fire on every session where files are modified, regardless of
file type or module.  It is not scoped to Java-only or test-only sessions; Python changes
in `tools/` are equally subject to the heuristic.

## Success Criteria

- Root `CLAUDE.md` contains the test-verification rule with the five-step heuristic.
- `engine/ml/CLAUDE.md` contains a ML-specific echo.
- Every `InstructionPromptBuilder`-generated prompt includes the section (verified by
  the existing `InstructionPromptBuilderTest` suite — the new section will appear in
  the `build()` output).
- Project owner has applied the `.claude` rules update described above.

## Dependencies

None. This is documentation and harness-instruction work only. No new Maven modules
are created. No pom.xml files are modified.

## Estimated Complexity

Small. All changes are text edits to documentation and the prompt-builder.

---

## Observation: Agent Drops PR Reply After Code Fix

**Documented: 2026-05-13**

Across multiple jobs spanning different workstreams, agents explicitly prompted to call
`github_pr_reply` complete the substantive code work and then end the turn without posting
the reply. The reply step is skipped silently — no error, no indication it was intended.

### Most recent confirmed instance

- **Job:** `0d80c02e-00b5-4ca7-9869-326d385f8856`
- **Workstream:** `b993e3fe-22d6-4288-9841-4e0ba0864d5f` (audio-scene-redesign, on common)
- **PR:** almostrealism/common #175
- **Comment:** `3236774262` (review comment from ashesfall on `PatternLayerManager.java:166`)
- **Commit landed:** `6e2e45ffb3` (code fix complete)
- **Prompt:** explicit instruction near the top — `github_pr_reply(pr_number=175, comment_id=3236774262, ...)`, with "before commit" ordering guidance
- **Outcome:** code fix landed; PR comment never replied to. Verified via `github_pr_review_comments`
  (no `in_reply_to_id` link to the original) and `github_pr_conversation` (no top-level reply either).

### Earlier instances to check

Other PR-review-driven jobs in the same session may show the same pattern.
The rings-au-metadata and rings-midi-capture Copilot review fix jobs (PRs #36 and #39 on
ringsdesktop) are candidates — the user's qualitative impression was that replies landed,
but a programmatic check via `in_reply_to_id` has not been done.

### Working hypothesis

The reply step happens chronologically after the code + commit work. The agent considers
work "substantively done" once the commit lands. Dedup audit and organizational placement
review phases interpose between commit and reply, and the reply gets dropped in transition.
GitHub tools appear to rank lower in the agent's internal action ordering than
code-and-commit, so under budget pressure or session-end timing they are skipped first.

### Why this matters

Review-comment replies are how reviewers know the agent understood the feedback and how the
fix relates to it. Without a reply, the reviewer must infer from the commit alone whether
the principle behind the comment was internalized or whether the symptom was accidentally
addressed. The agent's reasoning is the load-bearing piece; losing it silently degrades
review-loop quality.

### Suggested follow-up (not part of this job)

- **Audit** other recent jobs that asked for `github_pr_reply` and check programmatically
  (via `in_reply_to_id` in review comments) whether the reply actually landed.
- **Reorganize** prompt structure so PR replies happen **before** commit rather than after,
  placing them during the substantive-work phase rather than the wrap-up phase.
- **Add a post-completion check** that verifies the reply landed and re-prompts the agent
  if it did not.
- **Reorder** dedup audit / placement review phases so the reply lands before the
  commit-adjacent phases run.

Tracker task: filed in Common project for follow-up investigation.
