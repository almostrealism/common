# Agent Prompt Principles

Durable principles that all FlowTree enforcement-rule prompts (and any other
prompt sent to a coding agent on behalf of this project) are expected to
follow. This document is a reference for anyone editing prompt text in
`io.flowtree.jobs` so we do not regress the lessons learned from prior
correction sessions.

Each principle below was added in response to a real failure mode observed
on a branch. The "Why" notes record the case that motivated the rule. Edit
this document when a new prompt-improvement pass establishes a new rule;
keep the motivating case attached so future readers can judge whether a
proposed change is consistent with the existing principle or would
reintroduce the bug.

---

## P1 — Improve the area, do not police the diff

**Applies to:** `DeduplicationRule`. The same shape is worth watching for in
any rule whose correction prompt is framed as "did your branch add a
problem?"

The audit's goal is to leave the codebase with LESS duplication in the area
the branch is touching, not to keep the duplication count flat. Prompts
should NEVER frame the bar as "your branch did not add new duplication" or
"net change is zero." Those framings invite agents to rationalize that any
duplication that pre-dated the branch is out of scope.

The right framing is: while you are in this area, fix the duplication that
is related to your work. "Related" is defined narrowly enough (see P3 below)
that the rule does not turn into a global cleanup mission, but it includes
methods you are creating, moving, or modifying — even when equivalent
copies already exist in files you didn't otherwise touch.

**Why:** A dedup audit on `feature/review-phase` cleared a 4-copy
`truncate(String, int)` helper across `MessageEndpointHandler`,
`SlackListener`, `SlackNotifier`, and `JobStatsStore` using three
rationalizations: (1) "pre-existing copies pre-date the branch," (2) "branch
net change is zero new duplicates," (3) "method body is 3 lines, below the
detector threshold." All three are wrong. The audit was actively MOVING one
of those copies (from `FlowTreeApiEndpoint` into `MessageEndpointHandler`),
which is exactly the moment to consolidate the four.

## P2 — Detector thresholds are a floor, not a ceiling

**Applies to:** `DeduplicationRule`, and any rule whose prompt mentions a
linter or static-check threshold.

Tooling like `duplicate_code` flags duplication above a line-count threshold
because automated detection has to set a bar for unambiguous cases. The
threshold tells the linter when to complain. It does NOT tell the audit
what is worth fixing. A prompt that references a threshold must explicitly
say the threshold is a floor, not a ceiling — otherwise agents will
rationalize that anything below the threshold is fine because "the tool
doesn't even flag it."

## P3 — Scope is "related to this work," concretely defined

**Applies to:** `DeduplicationRule`. The same pattern should be considered
for any future rule that asks the agent to look beyond branch-modified
files.

A correction prompt that asks the agent to examine code beyond the branch's
diff MUST give a concrete boundary, or the agent will either (a) ignore the
ask and stay inside the diff, or (b) try to clean up the entire repository.
For deduplication, the boundary is:

- Methods the branch is creating, moving, modifying, or extracting →
  equivalent copies ANYWHERE are in scope.
- Same package, adjacent package, or same area of functionality as a
  branch-modified file → in scope.
- Class that calls into or is called by branch-modified code → in scope.
- Anything else (different module, no relationship to the work) → out of
  scope.

Future rules that ask for similar cross-cutting work should follow the same
pattern: state the in-scope cases concretely, state the out-of-scope case
with a counter-example, and forbid the audit from wandering further.

## P4 — Give a worked example AND a counter-example

**Applies to:** every enforcement-rule prompt that has a judgment boundary.

Agents over-fit to whichever example is given first. A prompt that shows
only "here is the kind of duplication to fix" produces agents that go on
global cleanup missions. A prompt that shows only "here is the kind of
duplication to ignore" produces agents that skip real fixes. Always include
both: a worked example of the right action, and a counter-example of an
out-of-scope case the agent should leave alone.

## P5 — No reverting work to clear an enforcement violation

**Applies to:** every correction prompt.

Correction prompts MUST NOT instruct the agent (or invite the agent) to use
`git restore`, `git checkout --`, `git reset`, or any other command that
discards working-tree state. The agent's job is to surgically fix the
violation while preserving every other change on the branch. This rule has
existed in the dedup and Maven-protection prompts for a long time; treat it
as load-bearing in every new correction prompt.

**Why:** A previous failure mode was an enforcement loop that reverted the
entire file to remove a single offending method, wiping the rest of the
branch's work along with it.

## P6 — Never create a new Maven module to fix a violation

**Applies to:** every correction prompt that may tempt the agent to "make a
new module to hold the shared helper."

Maven module structure is externally controlled. A correction prompt that
asks the agent to consolidate code into a shared location MUST also state
that creating a new Maven module is not an acceptable solution. Without
this, agents will spawn modules to escape placement decisions, and the
project owner has to manually delete them later.

---

## Editing prompts

When you edit a prompt in `io.flowtree.jobs`:

1. Read this document first. If your change conflicts with a principle
   above, you are probably reintroducing a failure mode. Surface the
   conflict in the PR.
2. Lock the new wording in with prompt-construction tests (see
   `DeduplicationRulePromptTest` for the pattern). The tests should assert
   the presence of the principles and the ABSENCE of any escape-hatch
   wording you removed.
3. If you establish a new principle that other rules should adopt, add it
   here with a "Why" note describing the case that motivated it.
4. If you find an analogous problem in a sibling rule but cannot fix it in
   the current change (scope), file a memory tagged
   `prompt-improvement-followup` so it does not get lost.
