# CI Investigation Protocol

This document provides a step-by-step guide for diagnosing and fixing CI pipeline failures
in the Almost Realism Common project. It is written for AI agents and developers who need
to investigate CI issues without making them worse.

**Read this document before investigating any CI failure.** The patterns described here are
based on real failures and their root causes.

---

## Before You Start: Mandatory Pre-Investigation Steps

### Step 0: Read the CI reference documents

1. Read `.github/CLAUDE.md` — the authoritative CI pipeline reference (the `guard-ci-file`
   hook injects this automatically when you open `analysis.yaml`)
2. Read `.github/CI_ARCHITECTURE.md` — comprehensive job reference
3. Read this document

Do not skip this step. The CI pipeline has subtle invariants that are easy to violate if
you are not aware of them.

### Step 0b: Run ar-consultant first

Before any investigation:
```
mcp__ar-consultant__consult question:"<your question about the failing CI>" keywords:["ComponentName", "JobName", "artifact"]
```

The consultant may have memory of this exact failure from a previous session. Check before
digging through logs manually.

---

## Failure Pattern Catalog

### Pattern 1: `find: 'all-coverage': No such file or directory`

**Symptom**: The `analysis` job fails with:
```
find: 'all-coverage': No such file or directory
Error: Process completed with exit code 1.
```

**Root cause chain**:
1. Only non-layer-flagged directories changed (flowtree/, flowtreeapi/, graphpersist/, tools/)
2. No named-layer flag was set (`base_changed`, `compute_changed`, etc. all false)
3. Both `test` and `test-media` were skipped (gated on layer flags)
4. No `coverage-group-*` or `coverage-media` artifacts were uploaded
5. The `analysis` job ran (it doesn't gate on layer flags, only on its `needs`)
6. The merge step ran `find all-coverage/` before `mkdir -p all-coverage`

**Diagnosis**:
- Check the `changes` job output: which flags were set?
- Check which directories actually changed (the branch diff)
- Confirm that `test` and `test-media` show as "skipped" in the workflow run

**Fix** (already applied as of 2026-04):
The `mkdir -p all-coverage` guard exists in the `analysis` job's merge step. Verify it's still there:
```bash
grep -n "mkdir -p all-coverage" .github/workflows/analysis.yaml
```
If it's missing, add it back immediately before any `find all-coverage` command.

**What NOT to do**: Do not add a `flowtree_changed` flag to "fix" this. The correct fix
is the `mkdir -p` guard, which already exists. The layer flag approach would cause flowtree
changes to trigger the full test matrix unnecessarily.

---

### Pattern 2: Coverage Missing from Qodana Report

**Symptom**: Qodana analysis runs but reports 0% or unexpectedly low coverage for certain modules.

**Root cause**: A test job uploads `coverage-*` artifacts but is not in `analysis.needs`.
The analysis job ran before the test job finished, downloading incomplete artifacts.

**Diagnosis**:
1. List all jobs in the workflow that upload `coverage-*` artifacts:
   ```bash
   grep -B20 'name: coverage-' .github/workflows/analysis.yaml | grep -E '(^  [a-z]|name: coverage)'
   ```
2. Check the `analysis` job's `needs` list:
   ```bash
   grep -A10 '^  analysis:' .github/workflows/analysis.yaml | grep 'needs'
   ```
3. Find the missing jobs (in step 1 output but not in step 2 output)

**Fix**: Add the missing job to `analysis.needs`. The `validate-ci-edit` hook validates
this automatically before any edit to `analysis.yaml`.

**Invariant**: `analysis.needs` must always include: `[build, test, test-media]`. Any new
job added in the future that uploads `coverage-*` must also be added here.

---

### Pattern 3: Flowtree Tests Not Running

**Symptom**: A flowtree-only branch shows no test failures, but you suspect tests weren't run.

**Root cause**: If flowtree tests were accidentally removed from the `build` job, they would
only run in layer-gated jobs — which are skipped on flowtree-only branches.

**Diagnosis**:
```bash
grep -n "pl flowtree" .github/workflows/analysis.yaml
```
This should show `mvn test -pl flowtree` inside the `build` job.

If it's missing, re-add it. Flowtree tests MUST be in `build`, not in a separately-gated job.

**Verification**: Check the `build` job in the workflow run. The flowtree test step should
show "X tests run, Y passed" (not "skipped").

---

### Pattern 4: Layer Flag Mismatch (`set_all_flags_true` vs detection loop)

**Symptom**: On master branch, all tests run (expected). On a feature branch with a specific
layer changed, some tests run that shouldn't, OR tests that should run are skipped.

**Root cause**: The `set_all_flags_true` block and the detection loop in the `changes` job
list different sets of layer names. This causes inconsistent behavior between branch types.

**Diagnosis**:
```bash
# Extract flags from set_all_flags_true block
grep -A50 'set_all_flags_true' .github/workflows/analysis.yaml | grep '_changed=true' | sort

# Extract flags from detection loop
grep '_changed=true' .github/workflows/analysis.yaml | grep -v 'set_all_flags_true' | sort
```
Both outputs must be identical. Any mismatch is a bug.

**Fix**: Make both sections list exactly the same flags. The set_all_flags_true block is a
shortcut that sets all flags simultaneously; the detection loop sets individual flags based
on which directory changed.

---

### Pattern 5: New Module Tests Not Running

**Symptom**: A new module was added. Its code compiles but tests are never executed in CI.

**Diagnosis**:
1. Determine which layer the module belongs to (read its pom.xml dependencies)
2. Find the corresponding layer flag (e.g., Layer 4 engine → `engine_changed`)
3. Verify the flag is set in the `changes` detection loop for the module's directory
4. Verify the `test` or `test-media` job includes the module in its test matrix

If the module is in a NEW top-level directory (not base/, compute/, domain/, engine/, extern/, studio/):
- Determine if it's a new named layer or a standalone module
- If named layer: add a new `*_changed` flag to `changes` (both sections!)
- If standalone: add tests to `build`, not to a new gated job

**Critical check**: After adding any new flag, verify it is consumed by at least one job.
Dead flags (set but never read) are a maintenance hazard.

---

### Pattern 6: Analysis Job Times Out or Fails at Qodana Step

**Symptom**: Coverage merge succeeds but Qodana analysis fails or takes excessively long.

**Diagnosis**:
1. Check the size of merged coverage data: very large `.exec` files slow Qodana significantly
2. Verify the JaCoCo CLI merge command is correct (merging .exec files, not report files)
3. Check Qodana configuration in `qodana.yaml` or `.github/workflows/analysis.yaml`

**Note**: This pattern is less common. The more likely Qodana failure causes are:
- Missing coverage artifacts (Pattern 2)
- The `all-coverage/` directory not existing (Pattern 1)

---

### Pattern 7: Test Job Runs on Flowtree-Only Branch

**Symptom**: `test` or `test-media` shows as "running" (not "skipped") when only `flowtree/`
files changed.

**Root cause**: The `changes` job incorrectly set a layer flag. This can happen if:
- The detection loop was modified incorrectly
- A flowtree-family file was accidentally placed in a layer directory (e.g., `base/`)
- The diff detection tool malfunctioned

**Diagnosis**:
```bash
# Check what actually changed in the branch
git diff --name-only origin/master..HEAD

# Check the changes job output in the workflow run
# Look at the step outputs for each flag
```

If only flowtree-family files changed but a layer flag was set, there's a bug in the
detection loop. Review the detection loop filter patterns for false positives.

---

## Investigation Workflow

When a CI failure occurs, follow this exact sequence:

### Phase 1: Gather Facts (5 minutes)

1. **Read the error message literally.** Do not interpret it yet. Just read it.
2. **Identify which job failed.** (`build`, `test`, `test-media`, `analysis`, `code-policy-check`, etc.)
3. **Find the specific step that failed.** Read the step name and its output.
4. **Check the `changes` job output.** Which flags were set? (`code_changed`, `base_changed`, etc.)
5. **Check which jobs ran, which were skipped.** This tells you which flags were true.

### Phase 2: Form a Hypothesis (2 minutes)

Based on Phase 1:
- Is this a known pattern from the catalog above? If yes, jump to the fix.
- If not, state your hypothesis explicitly: "I believe X failed because Y."
  Do NOT say "it might be" — be specific.

### Phase 3: Verify the Hypothesis (10 minutes)

For every hypothesis:
1. Find evidence that confirms it (not just fails-to-contradict it)
2. Find evidence that could disprove it
3. If disproved, go back to Phase 2

**For dependency-related hypotheses** (most common CI failures):
```bash
# Check what module X depends on
grep -o '<artifactId>ar-[^<]*</artifactId>' MODULE/pom.xml

# Check what depends on module X
grep -rl 'ar-MODULE-NAME' $(find . -name pom.xml)
```

**Never state a dependency fact without running these commands first.**

### Phase 4: Apply the Fix

1. Make the minimal change that fixes the root cause
2. Do not "fix" unrelated issues at the same time
3. Verify the fix with the post-fix checklist below

### Phase 5: Verify the Fix

Before committing any CI change:
```bash
# 1. Verify set_all_flags_true and detection loop match
grep -A50 'set_all_flags_true' .github/workflows/analysis.yaml | grep '_changed=true' | sort
grep '_changed=true' .github/workflows/analysis.yaml | grep -v 'set_all_flags_true' | sort

# 2. Verify analysis.needs includes all coverage-generating jobs
grep -A20 '^  analysis:' .github/workflows/analysis.yaml | grep -E '(needs|build|test)'

# 3. Verify mkdir -p all-coverage is present
grep -n "mkdir -p all-coverage" .github/workflows/analysis.yaml

# 4. Verify mvn test -pl flowtree is in build job
grep -n "pl flowtree" .github/workflows/analysis.yaml
```

All four checks must pass.

---

## Dependency Investigation Protocol

When investigating any issue that involves module dependencies, follow this exact protocol.

### Step 1: Identify the modules involved

List every module that is relevant to the issue. Be specific — use artifact IDs (e.g.,
`ar-utils`, not "utils").

### Step 2: For each module, check both directions

For module `ar-X`:
```bash
# Direction 1: What does ar-X depend on?
grep -o '<artifactId>ar-[^<]*</artifactId>' path/to/X/pom.xml

# Direction 2: What depends on ar-X?
grep -rl 'ar-X' $(find . -name pom.xml) | grep -v 'path/to/X/pom.xml'
```

### Step 3: Build the subgraph

Draw or write out the relevant portion of the dependency graph for the modules involved.
This does not need to be exhaustive, but it must be accurate. Every edge in your subgraph
must be backed by a pom.xml grep result from Step 2.

### Step 4: Answer the specific question

Only after completing Steps 1-3, answer the original question. Your answer must reference
specific pom.xml evidence from Step 2.

**Example of correct reasoning**:
> "I need to know if ar-flowtree depends on ar-utils-http.
> I ran: `grep 'ar-utils-http' flowtree/pom.xml`
> Output: `<artifactId>ar-utils-http</artifactId>`
> Conclusion: yes, ar-flowtree depends on ar-utils-http."

**Example of incorrect reasoning** (do not do this):
> "flowtree is in the standalone layer, which is above the engine layer, so it probably
> depends on engine modules like utils-http."

The second example is speculation. Only the first is acceptable.

---

## Common Mistakes and How to Avoid Them

### Mistake: "Nothing depends on X" after checking only one pom.xml

Always use `grep -rl` across ALL pom.xml files. A module may have consumers you haven't
thought to check.

### Mistake: Confusing dependency direction

"A depends on B" means A's pom.xml has B in `<dependencies>`. It does NOT mean B depends
on A. If you write "A → B" in your notes, re-read it as "A needs B, not B needs A."

### Mistake: Treating a consumer as a provider

If `flowtree` uses `flowtreeapi`, then:
- `flowtree` is the **consumer**
- `flowtreeapi` is the **provider** (dependency)
- `flowtreeapi` does NOT know about `flowtree`

### Mistake: Assuming the consultant is wrong

The ar-consultant MCP has architectural knowledge from many prior sessions. If the
consultant says something contradicts your current belief, **trust the consultant**. Read
the evidence it cites. Your current belief may be wrong.

### Mistake: Fixing the symptom instead of the root cause

The `find: 'all-coverage': No such file or directory` error was "fixed" incorrectly
multiple times before the true root cause (no `mkdir -p` guard) was found. Always trace
the error back to its actual cause using the Phase 1-3 investigation workflow.

### Mistake: Making changes without reading the CI reference first

The CI pipeline has subtle invariants. Reading `.github/CLAUDE.md` before touching
`analysis.yaml` takes 2 minutes. Getting the pipeline wrong and debugging the failure
takes hours.

---

## Post-Mortem Template

When a significant CI failure occurs, document it using this template:

```markdown
## CI Failure Post-Mortem — [Date]

### Symptom
[What error message appeared? Which job? Which step?]

### Root Cause
[The actual reason, stated precisely. Must be verifiable from logs/code.]

### Incorrect Hypotheses Considered
[List hypotheses that were disproved and what disproved them. This prevents others
from going down the same wrong path.]

### Fix Applied
[What change was made? File, line number, before/after.]

### Verification
[How was the fix verified? Did the workflow run succeed?]

### How to Prevent
[Was documentation updated? Was a check added? Was a hook updated?]
```

Store the post-mortem as a memory using `mcp__ar-consultant__remember` with namespace `bugs`
and tags `["CI", "pipeline", "post-mortem"]`.

---

## Quick Reference: CI Job Trigger Matrix

| What changed | build | test | test-media | code-policy | test-timeout |
|-------------|-------|------|------------|-------------|--------------|
| base/ | ✓ | ✓ | ✓ | ✓ | ✓ |
| compute/ | ✓ | ✓ | ✓ | ✓ | ✓ |
| domain/ | ✓ | ✓ | ✓ | ✓ | ✓ |
| engine/ | ✓ | ✓ | ✓ | ✓ | ✓ |
| extern/ | ✓ | — | ✓ | ✓ | ✓ |
| studio/ | ✓ | — | ✓ | ✓ | ✓ |
| flowtree/ | ✓ | — | — | ✓ | ✓ |
| flowtreeapi/ | ✓ | — | — | ✓ | ✓ |
| graphpersist/ | ✓ | — | — | ✓ | ✓ |
| tools/ | ✓ | — | — | ✓ | ✓ |

✓ = runs, — = skipped

The `analysis` job always runs after `build`, `test`, and `test-media` complete (any may be skipped).

---

## Related Documentation

- `.github/CLAUDE.md` — Authoritative CI module graph and layer-gating rules
- `.github/CI_ARCHITECTURE.md` — Comprehensive CI job reference with troubleshooting
- `docs/internals/module-dependency-architecture.md` — Full dependency graph with verification commands
- `docs/internals/standalone-modules.md` — Standalone module reference (flowtree, tools, etc.)
- `.claude/hooks/validate-ci-edit.sh` — Automated pre-edit validation for analysis.yaml
- `.claude/hooks/guard-ci-file.sh` — Auto-injects CI context when analysis.yaml is opened
- `.claude/hooks/guard-pom-read.sh` — Auto-injects dependency check when pom.xml is opened
