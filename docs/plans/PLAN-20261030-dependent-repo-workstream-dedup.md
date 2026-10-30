---
status: proposed
created: 2026-10-30
closed:
tracker: [2f28f6e8-9f84-427a-9861-8bcb2f87a4fb]
---

# Don't auto-register a workstream when an existing one already covers the branch as a dependent repo

**Tracker task:** `2f28f6e8-9f84-427a-9861-8bcb2f87a4fb` —
"Don't auto-register a workstream when an existing one already covers the branch
as a dependent repo" (the tracker task records the release it is scheduled in)

## Motivation

CI registers a workstream for each PR branch
(`tools/ci/register-workstream.sh` → `POST /api/workstreams`, handled by
`WorkstreamRegistrationHandler`). Registration returns the existing workstream
when one matches the same repository and branch; otherwise it creates a new one,
and when the derived Slack channel name collides it appends a numeric suffix
(`w-shared-branch` → `w-shared-branch-2`).

That suffixing is wrong when the "collision" is the *same body of work seen from
the other repository*. Concretely, on 2026-10-29:

- A workstream whose primary repo is a downstream repository that builds on this
  one is registered for a shared feature branch; it lists Common among its
  `dependentRepos`, and its job pushed commits to the Common branch of the same
  name.
- When the Common PR (#784) opened for that branch, Common CI registered a
  *second* workstream (the suffixed channel name) for work the downstream
  workstream already owns.

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
   *shown* not to act on the wrong checkout (it is either skipped or run against
   the right one) rather than silently reviewing or committing to the wrong
   repository.

Explicitly **out of scope** (named as dependencies / related work, not widened
into here):

- Opening a companion PR when a job pushes commits to a dependent repo — tracked
  separately as `dc9b2ec4-eacd-471c-8277-072324eb3f9b` and **already merged**
  (PR #788; design [`PLAN-20261029-dependent-repo-companion-pr.md`](PLAN-20261029-dependent-repo-companion-pr.md),
  now `status: completed`). `GitCommitHandler.handleDependentRepos` records each
  dependent-repo push and the controller (`AutoPullRequestPublisher`) opens or
  reuses a companion PR for it. Companion PRs are therefore now routine, and
  every one of them triggers Common CI on a branch the owning workstream already
  covers. That makes this task more pressing, not less; this task only stops the
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

The new lookup belongs in `register(...)` and **not** in
`NotifierRegistry.resolveBranch` (the rung `resolveOrRegister` tries first).
`resolveBranch` with a `repoUrl` is deliberately an exact `(branch, repo)` match,
and `SlackApiWorkstreamTest.testApiSubmitRepoUrlMismatchDoesNotCrossRoute` pins
that a submission naming a repository nobody tracks fails with "No workstream
found for branch" instead of cross-routing. Putting the dependent lookup in
`register(...)` keeps that contract: a submission *without*
`createWorkstreamIfMissing` still fails as it does today, and only the
would-create path learns about the owning workstream.

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
        return Registration.dependentRepoMatch(dependentOf);
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

One existing behaviour of the first lookup affects the ordering:
`SlackNotifier.findWorkstreamByBranchAndRepo` falls back to an *unscoped*
workstream (one with no `repoUrl`) on branch `B` when no workstream names `R`.
Such a workstream would therefore still win over a dependent-of match. That is
the existing contract for configs that predate `repoUrl`, and this plan leaves
it alone (see Open Questions).

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
> at 1586 lines, 14 under the 1600-line Checkstyle `FileLength` limit, and a
> javadoc'd `dependsOnRepo` (about 15–20 lines) will not fit without first
> making room. The implementer must budget for extracting a cohesive group out
> of `Workstream` (the repository-identity helpers, or another cohesive config
> group, following the collaborator-extraction pattern described in
> `flowtree/CLAUDE.md`) so the new member fits. This refactor is part of the
> change, not a follow-up. `FlowTreeApiEndpoint.java` is at 1558 lines (it
> was 1582 before the companion-PR merge) and has room for the submit-response
> field and the dependent-match skip described below (about 10–15 lines), but
> the margin is small. Measure it again when implementing.
> `WorkstreamRegistrationHandler.java` (935 lines) and `NotifierRegistry.java`
> (464 lines) have ample room.

### Returning the relationship

Extend `WorkstreamRegistrationHandler.Registration` with a
`matchedAsDependentRepo` boolean (and accessor) and a factory that sets
`existing = true` (so every existing-match code path keeps working) plus the new
flag. Name the factory something other than the accessor — the sketch above
uses `Registration.dependentRepoMatch(Workstream)`. A static
`matchedAsDependentRepo(Workstream)` beside an instance `matchedAsDependentRepo()`
would be a legal overload, but it reads badly, so avoid it. Then:

- `handleRegister`: when the outcome is a dependent match, emit
  `{"ok":true,"existing":true,"matchedAsDependentRepo":true,"workstreamId":...}`.
  An ordinary existing match keeps emitting exactly what it does today.
- `FlowTreeApiEndpoint#handleSubmit`: `workstreamCreated = !resolution.existing()`
  already becomes `false` for a dependent match (good — no duplicate is
  created). What the submit path does *next* with a dependent match is the
  caller-handling decision in the next section. In every option the response
  carries `"matchedAsDependentRepo": true` so the caller can tell.

### Check the callers

Common CI submits auto-review/auto-resolve via `submit-agent-job.sh` /
`submit-staged-request.sh` with `createWorkstreamIfMissing: true`,
`targetBranch: B`, `repoUrl: git@github.com:almostrealism/common.git`. After
this change that submission resolves to the downstream workstream instead of
creating `w-...-2`. The caller concern is what the job then does:

- A job's **primary** working directory is cloned from the *workstream's*
  `repoUrl` (`GitRepositorySetup.resolveWorkingDirectory` /
  `cloneRepository`), i.e. the downstream repository. The `dependentRepos`
  (Common) are cloned as **siblings** (`prepareDependentRepos` /
  `prepareDependentReposBranches`).
- The auto-review/auto-resolve diff, commit (`GitCommitHandler`), and auto-PR
  all act on the **primary** repo. So a Common PR job routed to the downstream
  workstream would, as the code stands, review the downstream repository's diff
  and land commits/PRs against it — **not** the Common companion checkout. That is the
  "silently break CI-submitted jobs on companion PRs" failure the task warns
  against: the jobs do **not** act on the right repository as-is.

Therefore registration must return the relationship (this plan's
`matchedAsDependentRepo` field), and the companion-PR caller behaviour must be
decided deliberately. See Open Questions for the recommended decision and the
alternative. Whatever is chosen, a test must *show* what happens to a
companion-PR job (skipped, or which checkout it runs against; success criterion
6 below), so the behaviour is demonstrated rather than assumed.

**The guard must be server-side.** `POST /api/submit` resolves the workstream
*and* queues the job in the same call. By the time `submit-agent-job.sh` can
read `matchedAsDependentRepo` from the response, the job has already been sent
to the downstream workstream. A client-side "skip if the response says dependent
match" in the CI scripts therefore cannot stop the wrong-repository job. The
recommended guard (Open Question 1) instead lives in
`FlowTreeApiEndpoint#handleSubmit`. When `resolution` is a dependent match, the
endpoint returns without submitting, in the shape the existing `startedAfter`
guard already uses:

```json
{"ok":true,"skipped":true,"matchedAsDependentRepo":true,
 "workstreamId":"<owning workstream>",
 "reason":"Branch B of <repo> is covered as a dependent repo of workstream <id>"}
```

`submit-agent-job.sh` already treats `"skipped": true` as a clean exit 0 and
writes the `reason` to the step summary. So the recommended option needs **no
CI-script change** for submission. The skip must sit before the
`No FlowTree server configured` check, as the `startedAfter` guard does, so
`SlackApiWorkstreamTest` can observe it. The git-identity check runs earlier in
`handleSubmit` than that guard. A test fixture for the owning workstream must
therefore set `gitUserName` / `gitUserEmail`, or the implementer must place the
dependent-match skip before the identity check. A job that is never queued
needs no identity, so placing it first is arguably the correct order.

Note on `register-workstream.sh`: when registration returns an existing match,
the script may run an update against the returned workstream. For the early
Common `register-workstream` job (analysis.yaml) that update payload is empty
(no `PLAN_FILE` / `TRACKER_CAPABILITIES` / `REQUIRED_LABELS_JSON` /
`DEPENDENT_REPOS`), so it is already a no-op and will not clobber the downstream
workstream. The plan still recommends the script read the new
`matchedAsDependentRepo` field and skip the update entirely for a dependent
match, so Common CI settings can never be written onto the owning (downstream)
workstream even if a future caller starts passing those fields. Unlike the
submit path, a client-side check is sufficient here, because registration
queues no job. **Sequencing constraint:** `tools/ci/` is under the CI-file lock
(`check-ci-file-lock.sh`). That script change can land only on a `ci/...`
branch, separately from the `flowtree/runtime` change. The `register-workstream`
job in `analysis.yaml` also refuses to run a `register-workstream.sh` that
differs from `origin/master` on a non-`ci/` branch. Because the update is
already a no-op today, the script change is optional hardening, and the
runtime change does not depend on it.

## Success criteria

A reviewer can confirm the task is done when:

1. Registering Common branch `B` (repo = Common) when a non-archived downstream
   workstream has default branch `B` and lists Common among its `dependentRepos`
   returns that downstream workstream (`existing: true`, `matchedAsDependentRepo:
   true`, its `workstreamId`) and creates nothing new.
2. A genuine name collision with an *unrelated* workstream (same branch, neither
   the same repo nor a dependent-of relationship) still creates a new workstream
   with a suffixed channel name.
3. An exact `(branch, repo)` match is still returned as an ordinary existing
   match (not a dependent match).
4. Archived workstreams are never matched — an archived downstream workstream
   that would otherwise match as a dependent-of does not suppress creation.
5. The repository-identity comparison holds across spellings: a downstream
   workstream whose `dependentRepos` lists
   `https://github.com/almostrealism/common.git`
   matches a Common registration that names the repo as
   `git@github.com:almostrealism/common.git`.
6. A companion-PR job CI submits is shown not to act on the wrong checkout
   (per the Open-Questions decision). Under the recommended option: a
   `POST /api/submit` with `createWorkstreamIfMissing: true`, `targetBranch: B`
   and `repoUrl` = Common, against a controller holding only the downstream
   workstream that lists Common as a dependent, returns `"skipped": true`,
   `"matchedAsDependentRepo": true` and the downstream `workstreamId`. It creates no
   workstream and queues no job. The same submission for an unrelated branch
   still creates a workstream, as `testApiSubmitCreatesWorkstreamWhenMissing`
   asserts today.

### Tests

- **`NotifierRegistryTest`** (`flowtree/runtime/src/test/.../slack/`) already has
  a Common-workstream fixture (`wsCommon`) and a downstream-workstream fixture
  that share a branch, used by
  `testFindAllByBranchReturnsEveryWorkstreamSharingBranch`. Add coverage for
  `findByBranchAndDependentRepo`: a hit when a workstream lists the repo as a
  dependent (across SSH/HTTPS/`.git` spellings), a miss for an unrelated repo,
  and a miss for an archived workstream.
- **`Workstream` unit coverage** for `dependsOnRepo` (identity equality; empty /
  null `dependentRepos`), alongside the existing `matchesRepo` usage.
- **Endpoint coverage through `register(...)`** (which both the register and
  submit paths use). There is no dedicated `WorkstreamRegistrationHandler` test.
  The existing HTTP-level harness for both entry points is
  `SlackApiWorkstreamTest` (`flowtree/runtime/src/test/.../slack/`), which
  already has `testApiRegisterWorkstreamCollisionHandling` (the `-2` suffix
  case) and `testApiSubmitCreatesWorkstreamWhenMissing` (the submit-create
  path). Extend it with: a dependent-of match on `POST /api/workstreams`
  returns the existing workstream with the flag and creates nothing; an
  unrelated collision still gets a suffixed new workstream (the existing
  collision test must keep passing unchanged); an archived candidate is never
  matched.
- **Caller coverage** demonstrating the companion-PR outcome (success
  criterion 6) through `POST /api/submit` in the same class, consistent with
  the Open-Questions decision. The existing
  `testApiSubmitRepoUrlMismatchDoesNotCrossRoute` must keep passing unchanged.
  It pins that a submission *without* `createWorkstreamIfMissing` never
  cross-routes. Note this method exists in **two** test classes —
  `SlackApiWorkstreamTest` and `SlackRoutingTest` (both in
  `flowtree/runtime/src/test/.../slack/`) — and both copies must keep passing.
- Reuse `GitOperationsTest`'s existing `isSameRepository` coverage rather than
  re-testing identity normalisation.

## Dependencies

- **Related, merged:** `dc9b2ec4-eacd-471c-8277-072324eb3f9b` — "Open a
  companion PR when a job pushes commits to a dependent repo" (PR #788, merged
  to master). It is not a blocker. Companion PRs are what make the
  dependent-of scenario routine, and now that they ship, each one reproduces
  the duplicate-workstream problem until this task lands.
- **CI-file lock** for the optional `register-workstream.sh` hardening (see
  "Check the callers"). It needs its own `ci/...` branch. The recommended
  submit-path guard needs no `tools/ci/` change.

## Open questions

1. **Caller behaviour for companion-PR remediation (the one real decision).**
   Once a Common companion-PR submission resolves to the downstream workstream,
   the job's primary checkout is the downstream repository, and
   auto-review/auto-resolve act on the primary repo. Two ways to keep from
   silently breaking it:
   - *(Recommended, in-scope and minimal)* Registration returns the
     relationship (this plan's `matchedAsDependentRepo`), and
     `POST /api/submit` declines to queue a job whose workstream was resolved
     only as a dependent-of match. It returns `"skipped": true` with a reason
     (see "Check the callers" for why this must be server-side and not a CI
     script guard). The owning (downstream) workstream, together with the
     companion-PR mechanism from `dc9b2ec4`, is where the branch's work and
     reviews are coordinated. This avoids running a review against the wrong
     repository and keeps this task's code changes to `flowtree/runtime`.
     **Cost the approver should weigh:** Common CI failures on a companion PR
     (a red test, a code-policy violation) then get *no* automated
     auto-resolve at all. Someone must notice them on the owning workstream,
     or fix them by hand. Today's duplicate workstream is wrong, but it does
     at least attempt the fix against the right checkout.
   - *(Variant)* Apply the skip only to `"automated": true` submissions (what
     CI sends), and let a person's explicit submission through to the owning
     workstream. Note that for a human submission, routing to the owning
     workstream still checks out the *other* repository as primary, so a skip
     with an explanatory reason is probably also the right answer there.
   - *(Alternative, larger)* Teach the job machinery to treat the dependent
     checkout as the subject of the review / commit / auto-PR when the
     submission names a repo that is a dependent of the resolved workstream.
     This touches `GitRepositorySetup`, `GitCommitHandler`,
     `InstructionPromptBuilder`, and the submit path, and should be its own
     task rather than folded in here.

   The approver should pick one; the plan assumes the recommended option unless
   told otherwise.
2. ~~Sequencing against PR #788.~~ Resolved: the companion-PR work has merged,
   so there is nothing to sequence against.
3. **Unscoped workstreams.** `findWorkstreamByBranchAndRepo` returns a
   workstream with no `repoUrl` on branch `B` as a fallback match for any
   repository, ahead of the new dependent lookup. Should a dependent-of match
   outrank an unscoped one? The plan keeps the current precedence (unscoped
   first), because changing it alters routing for configs that predate
   `repoUrl`. If such workstreams no longer exist in practice, the approver may
   prefer the other order.
4. **Several owners.** If more than one non-archived workstream on branch `B`
   lists `R` as a dependent, the plan returns the first one found, mirroring
   `findByBranchAndRepo`. `resolveBranch` treats a comparable ambiguity as an
   error instead. Should a multi-owner dependent match be an ambiguity error
   (falling through to creation, or failing)? This is unlikely in practice,
   but the behaviour should be chosen, not inherited.

## Estimated complexity

**Medium.** The registration logic is small and well-localised, and the repo
identity comparison already exists. The weight is in: (a) threading the new
`matchedAsDependentRepo` field through the `Registration` type and both response
builders; (b) the required small refactor to keep `Workstream.java` (14 lines
under the 1600-line limit) from breaching it, with `FlowTreeApiEndpoint.java`
(42 lines under) to be re-measured; and (c) the caller-handling decision and its
test. Under the recommended option, that is a server-side skip in
`handleSubmit` that the existing CI scripts already honour. The
`register-workstream.sh` hardening is an optional, separate `ci/...` change. No
new Maven module, no hardware requirement.
