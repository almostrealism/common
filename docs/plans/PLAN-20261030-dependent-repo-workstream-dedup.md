---
status: proposed
created: 2026-10-30
closed:
tracker: [2f28f6e8-9f84-427a-9861-8bcb2f87a4fb]
---

# Don't auto-register a workstream when an existing one already covers the branch as a dependent repo

**Tracker task:** `2f28f6e8-9f84-427a-9861-8bcb2f87a4fb` —
"Don't auto-register a workstream when an existing one already covers the branch
as a dependent repo"
**Release:** Common 0.76

## Motivation

CI registers a workstream for each PR branch
(`tools/ci/register-workstream.sh` → `POST /api/workstreams`, handled by
`WorkstreamRegistrationHandler`). Registration returns the existing workstream
when one matches the same repository and branch; otherwise it creates a new one,
and when the derived Slack channel name collides it appends a numeric suffix
(`w-sa3-generation` → `w-sa3-generation-2`).

That suffixing is wrong when the "collision" is the *same body of work seen from
the other repository*. Concretely, on 2026-10-29:

- A Rings workstream for branch `feature/sa3-generation` (primary repo
  `ringsdesktop`) lists Common among its `dependentRepos`, and its job pushed
  commits to the Common branch `feature/sa3-generation`.
- When Common PR #784 opened for that branch, Common CI registered a *second*
  workstream, `w-sa3-generation-2`, for work the Rings workstream already owns.

The two workstreams then compete: duplicate Slack channels, duplicate
auto-review/auto-resolve runs, and a split record of one piece of work. The fix
is to recognise the dependent-repo relationship at registration time and reuse
the existing workstream instead of creating a duplicate.

## Scope

In scope:

1. **Registration de-duplication.** Before creating a workstream for repository
   `R` and branch `B`, after the existing exact `(branch, repo)` match misses,
   check whether a *non-archived* workstream has default branch `B` and lists
   `R` among its `dependentRepos`, comparing repository identities the way
   registration already does (SSH / HTTPS / `.git` spellings equal). If one
   exists, create nothing and return it as an existing match, adding a field
   that records it was matched as a dependent repo.
2. **Surface the relationship to callers.** Return the new
   `matchedAsDependentRepo` flag on both the `POST /api/workstreams` response
   and the `POST /api/submit` response, so a caller can tell a genuine
   pre-existing match from a dependent-of match and choose how to behave.
3. **Caller handling.** Ensure the auto-review / auto-resolve jobs Common CI
   submits on a companion PR are not silently broken by now resolving to the
   *other* repository's workstream (see Approach → "Check the callers"). The
   exact caller behaviour is the one open decision for the approver (see Open
   Questions); whichever is chosen, the task's bar is that a companion-PR job is
   *shown* to run against the right checkout rather than silently reviewing or
   committing to the wrong repository.

Explicitly **out of scope** (named as dependencies / related work, not widened
into here):

- Opening a companion PR when a job pushes commits to a dependent repo — tracked
  separately as `dc9b2ec4-eacd-471c-8277-072324eb3f9b` (Common 0.76), in flight
  (PR #788, design `docs/plans/PLAN-20261029-dependent-repo-companion-pr.md`).
  That task is what makes these companion PRs routine; this task only stops the
  duplicate workstream.
- Teaching a job to treat a *dependent* checkout (rather than the workstream's
  primary repo) as the subject of its review / commit / auto-PR. If the approver
  chooses that route for caller handling (Open Questions), it is a substantially
  larger change to `GitRepositorySetup` / `GitCommitHandler` /
  `InstructionPromptBuilder` and should be its own task.

## Approach

### Where the change belongs

The whole registration body is `WorkstreamRegistrationHandler.register(String
body, String defaultBranch)` in
`flowtree/runtime/src/main/java/io/flowtree/api/WorkstreamRegistrationHandler.java`.
It is shared by *both* entry points:

- `handleRegister` (the `POST /api/workstreams` endpoint), and
- `resolveOrRegister` (the `createWorkstreamIfMissing` path of `POST
  /api/submit`), which calls `register(...)` once the branch/id ladder misses.

So a single change in `register(...)` fixes both the early
`register-workstream` CI job *and* the auto-review/auto-resolve submissions,
which is exactly what the task needs.

Today `register(...)` does:

```java
Workstream existing = notifiers.findByBranchAndRepo(defaultBranch, repoUrl);
if (existing != null) {
    return Registration.alreadyRegistered(existing);
}
// ... auto-create Slack channel, build + persist a new Workstream ...
```

The new behaviour inserts a second lookup between those two steps:

```java
Workstream existing = notifiers.findByBranchAndRepo(defaultBranch, repoUrl);
if (existing != null) {
    return Registration.alreadyRegistered(existing);
}
if (repoUrl != null && !repoUrl.isEmpty()) {
    Workstream dependentOf =
        notifiers.findByBranchAndDependentRepo(defaultBranch, repoUrl);
    if (dependentOf != null) {
        return Registration.matchedAsDependentRepo(dependentOf);
    }
}
// ... create a new workstream (suffixed channel) only now ...
```

Ordering matters and is preserved: an exact `(branch, repo)` match still wins, so
a workstream that genuinely tracks `(B, R)` is returned as the ordinary existing
match, never as a dependent match. Only when no workstream *tracks* `(B, R)` do
we look for one that *depends on* `R` at branch `B`. The dependent lookup is
guarded on a non-empty `repoUrl` because there is nothing to compare against a
`dependentRepos` list otherwise (and CI always sends `repoUrl`).

### The two new lookups

- **`NotifierRegistry.findByBranchAndDependentRepo(String branch, String
  repoUrl)`** in
  `flowtree/runtime/src/main/java/io/flowtree/slack/NotifierRegistry.java`,
  mirroring the existing `findByBranchAndRepo` (search across every workspace,
  delegating to a `SlackNotifier` helper). It returns the first *non-archived*
  workstream whose `defaultBranch` equals `branch` and that depends on
  `repoUrl`. Archived exclusion lives here (in the lookup), not in the
  per-workstream predicate — `findByBranchAndRepo` does not filter archived
  today, so the new lookup adds the filter the task requires without changing
  the existing method's contract.
- **`Workstream.dependsOnRepo(String repoUrl)`** in
  `flowtree/runtime/src/main/java/io/flowtree/workstream/Workstream.java`: true
  when `dependentRepos` is non-empty and some entry is the same repository as
  `repoUrl`. It reuses the identity comparison the class already uses for its
  primary repo — `GitOperations.isSameRepository` (see `matchesRepo`) — so the
  `git@` / `https` / `.git` spellings compare equal, as the task requires. This
  predicate reads the workstream's own `dependentRepos` state, so it belongs on
  `Workstream` beside `matchesRepo`.

> **File-length constraint (part of this task's scope).** `Workstream.java` is
> at 1585 lines and `FlowTreeApiEndpoint.java` at 1582 — both within ~15 lines
> of the 1600-line Checkstyle hard limit. (`WorkstreamRegistrationHandler.java`,
> which gains the second lookup and the new `Registration` factory, is well
> under the limit and has room for those members.) Adding members to the two
> near-cap files without first making room will breach the limit and fail CI.
> The implementer must budget for extracting a
> cohesive group out of the file being touched (the repository-identity helpers
> on `Workstream`, or another cohesive config group, following the
> `PhaseConfigResolver` / `WireConfig` extraction pattern described in
> `flowtree/CLAUDE.md`) so the new members fit. This refactor is part of the
> change, not a follow-up.

### Returning the relationship

Extend `WorkstreamRegistrationHandler.Registration` with a
`matchedAsDependentRepo` boolean and a factory
`Registration.matchedAsDependentRepo(Workstream)` that sets `existing = true`
(so every existing-match code path keeps working) plus the new flag. Then:

- `handleRegister`: when the outcome is a dependent match, emit
  `{"ok":true,"existing":true,"matchedAsDependentRepo":true,"workstreamId":...}`.
  An ordinary existing match keeps emitting exactly what it does today.
- `FlowTreeApiEndpoint#handleSubmit`: `workstreamCreated = !resolution.existing()`
  already becomes `false` for a dependent match (good — no duplicate is
  created). Add `"matchedAsDependentRepo": true` to the submit response JSON
  (beside the existing `workstreamCreated` branch) so the submitting script can
  read it.

### Check the callers

Common CI submits auto-review/auto-resolve via `submit-agent-job.sh` /
`submit-staged-request.sh` with `createWorkstreamIfMissing: true`,
`targetBranch: B`, `repoUrl: git@github.com:almostrealism/common.git`. After
this change that submission resolves to the Rings workstream instead of creating
`w-...-2`. The caller concern is what the job then does:

- A job's **primary** working directory is cloned from the *workstream's*
  `repoUrl` (`GitRepositorySetup.resolveWorkingDirectory` /
  `cloneRepository`), i.e. `ringsdesktop`. The `dependentRepos` (Common) are
  cloned as **siblings** (`prepareDependentRepos` /
  `prepareDependentReposBranches`).
- The auto-review/auto-resolve diff, commit (`GitCommitHandler`), and auto-PR
  all act on the **primary** repo. So a Common PR job routed to the Rings
  workstream would, as the code stands, review the Rings diff and land
  commits/PRs against Rings — **not** the Common companion checkout. That is the
  "silently break CI-submitted jobs on companion PRs" failure the task warns
  against: the jobs do **not** act on the right repository as-is.

Therefore registration must return the relationship (this plan's
`matchedAsDependentRepo` field), and the companion-PR caller behaviour must be
decided deliberately. See Open Questions for the recommended decision and the
alternative. Whatever is chosen, a test must *show* where a companion-PR job's
checkout points (success criterion below), so the behaviour is demonstrated
rather than assumed.

Note on `register-workstream.sh`: when registration returns an existing match,
the script may run an update against the returned workstream. For the early
Common `register-workstream` job (analysis.yaml) that update payload is empty
(no `PLAN_FILE` / `TRACKER_CAPABILITIES` / `REQUIRED_LABELS_JSON` /
`DEPENDENT_REPOS`), so it is already a no-op and will not clobber the Rings
workstream. The plan still recommends the script read the new
`matchedAsDependentRepo` field and skip the update entirely for a dependent
match, so Common CI settings can never be written onto the owning (Rings)
workstream even if a future caller starts passing those fields.

## Success criteria

A reviewer can confirm the task is done when:

1. Registering Common branch `B` (repo = Common) when a non-archived Rings
   workstream has default branch `B` and lists Common among its `dependentRepos`
   returns that Rings workstream (`existing: true`, `matchedAsDependentRepo:
   true`, its `workstreamId`) and creates nothing new.
2. A genuine name collision with an *unrelated* workstream (same branch, neither
   the same repo nor a dependent-of relationship) still creates a new workstream
   with a suffixed channel name.
3. An exact `(branch, repo)` match is still returned as an ordinary existing
   match (not a dependent match).
4. Archived workstreams are never matched — an archived Rings workstream that
   would otherwise match as a dependent-of does not suppress creation.
5. The repository-identity comparison holds across spellings: a Rings workstream
   whose `dependentRepos` lists `https://github.com/almostrealism/common.git`
   matches a Common registration that names the repo as
   `git@github.com:almostrealism/common.git`.
6. A companion-PR job CI submits is shown to run against the right checkout
   (per the Open-Questions decision): a test demonstrates that the submission
   resolves to the owning workstream and either (a) the job does not act on the
   wrong repository, or (b) whatever caller guard is chosen takes effect.

### Tests

- **`NotifierRegistryTest`** (`flowtree/runtime/src/test/.../slack/`) already has
  the `wsCommon` / `wsRings` fixtures used by
  `testFindAllByBranchReturnsEveryWorkstreamSharingBranch`. Add coverage for
  `findByBranchAndDependentRepo`: a hit when a workstream lists the repo as a
  dependent (across SSH/HTTPS/`.git` spellings), a miss for an unrelated repo,
  and a miss for an archived workstream.
- **`Workstream` unit coverage** for `dependsOnRepo` (identity equality; empty /
  null `dependentRepos`), alongside the existing `matchesRepo` usage.
- **`WorkstreamRegistrationHandler`'s suite** must cover the three task
  scenarios through `register(...)` (which both the register and submit paths
  use): dependent-of match returns the existing workstream with the flag and
  creates nothing; an unrelated collision still gets a suffixed new workstream;
  an archived candidate is never matched. (If no direct handler test harness
  exists yet, add one in `io.flowtree.api`; otherwise extend the
  closest-existing registration coverage.)
- **Caller coverage** demonstrating the companion-PR checkout outcome
  (success criterion 6), consistent with the Open-Questions decision.
- Reuse `GitOperationsTest`'s existing `isSameRepository` coverage rather than
  re-testing identity normalisation.

## Dependencies

- **Related, in flight:** `dc9b2ec4-eacd-471c-8277-072324eb3f9b` — "Open a
  companion PR when a job pushes commits to a dependent repo" (PR #788). This
  task is not `blocked_by` it, but the two are coupled: companion PRs are what
  make the dependent-of scenario routine, and the caller-handling decision below
  interacts with how commits on a dependent repo are surfaced. The approver
  should decide whether to sequence this task after #788 lands.

## Open questions

1. **Caller behaviour for companion-PR remediation (the one real decision).**
   Once a Common companion-PR submission resolves to the Rings workstream, the
   job's primary checkout is `ringsdesktop`, and auto-review/auto-resolve act on
   the primary repo. Two ways to keep from silently breaking it:
   - *(Recommended, in-scope and minimal)* Registration returns the
     relationship (this plan's `matchedAsDependentRepo`), and Common CI's
     remediation submitters skip submitting a duplicate job when the branch is
     covered only as a *dependent* repo of another workstream — the owning
     (Rings) workstream, together with the companion-PR mechanism from
     `dc9b2ec4`, is where the branch's work and reviews are coordinated. This
     avoids running a review against the wrong repository and keeps this task's
     code changes to registration + a small submitter guard.
   - *(Alternative, larger)* Teach the job machinery to treat the dependent
     checkout as the subject of the review / commit / auto-PR when the
     submission names a repo that is a dependent of the resolved workstream.
     This touches `GitRepositorySetup`, `GitCommitHandler`,
     `InstructionPromptBuilder`, and the submit path, and should be its own
     task rather than folded in here.

   The approver should pick one; the plan assumes the recommended option unless
   told otherwise.
2. **Sequencing against PR #788.** Should this task wait for the companion-PR
   work to merge, or land first (it is self-contained — the dedup is correct
   regardless of whether companion PRs exist yet)?

## Estimated complexity

**Medium.** The registration logic is small and well-localised, and the repo
identity comparison already exists. The weight is in: (a) threading the new
`matchedAsDependentRepo` field through the `Registration` type and both response
builders; (b) the required small refactor to keep `Workstream.java` /
`FlowTreeApiEndpoint.java` (both ~15 lines under the 1600-line limit) from
breaching it; and (c) the caller-handling decision and its test, which
touches CI submission scripts. No new Maven module, no hardware requirement.
