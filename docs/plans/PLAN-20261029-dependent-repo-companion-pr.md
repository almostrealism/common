---
status: proposed
created: 2026-10-29
closed:
tracker: [dc9b2ec4-eacd-471c-8277-072324eb3f9b]
---

# Open a companion PR when a job pushes commits to a dependent repo

**Tracker task:** `dc9b2ec4-eacd-471c-8277-072324eb3f9b`: *Open a companion PR when a job pushes commits to a dependent repo*
**Release:** Common 0.76 (project "Common")
**Estimated complexity:** Medium

---

## Motivation

A workstream can declare dependent repos (`WorkstreamConfig.dependentRepos`). A
coding-agent job on that workstream clones each one next to the primary repo,
checks out the workstream's branch there, and commits and pushes whatever the
agent changed in it (`GitCommitHandler.handleDependentRepos`). Nothing then opens
a pull request for the commits in the dependent repo. When `autoCreatePr` is set,
the controller opens a PR for the primary repo only.

That gap caused the 2026-10-29 incident. A job on a workstream for a downstream
application moved a helper into Common as `WaveData.conform(int,int)`. The
commit went to Common branch `feature/sa3-generation`, and no PR was opened for
it. The downstream CI builds against the same-named Common branch when one
exists, so the downstream PR was green. Once it merged, the downstream master
built against Common master and failed with `cannot find symbol: method
conform(int,int)`, breaking master and every branch that merged it. Common PR
#784 repaired it by hand.

A dependent-repo change with no PR is invisible to the reviewer, and it cannot be
merged through the normal flow. The controller should open a *companion* PR for
it, linked both ways with the primary PR.

## Scope

In scope:

1. **Job side: report dependent-repo pushes.** For every dependent repo a job
   actually pushed to, the completion event carries the clone URL, the branch,
   and the pushed commit hash.
2. **Controller side: open or reuse the companion PR.** On a successful
   completion whose job requested `autoCreatePr`, for each reported push:
   - target the dependent repo's default branch;
   - do nothing when that branch is not behind the pushed branch (the branch
     does not differ from default);
   - reuse an open PR whose head is that branch, and never open a duplicate;
   - otherwise open a PR whose title and body come from the pushed commit's
     message (as `createPullRequestFromCommit` already does for the primary),
     with a line naming and linking the primary PR appended to the body;
   - add a reciprocal comment on the primary PR naming the companion PR and
     stating that both must merge. This happens only when the companion PR is
     newly opened, so re-runs do not repeat the comment.
3. **No PR requested: report, do not create.** When the job did not request
   `autoCreatePr` but pushed to a dependent repo, open nothing. Log a warning on
   the controller and add a line to the job's completion notification saying
   which dependent-repo commits are unreviewed.
4. **Tests** against a fake GitHub API covering the three "done when" cases (see
   Success criteria).

Out of scope, as the task states: blocking or gating the primary PR while it
builds against a non-default dependent branch.

## Approach

### 1. Job side: `flowtree/runtime`, package `io.flowtree.jobs`

**Where the push happens.** `GitManagedJob.run()` calls
`repoSetup.prepareDependentRepos()` (clones each URL in `dependentRepos` to
`<parent>/<repoName>`), then `prepareDependentReposBranches()`. After `doWork()`
it calls `commitHandler.handleDependentRepos(repoSetup.getDependentRepoPaths())`.
`GitCommitHandler.handleDependentRepos` commits with the job's commit message and,
when `isPushToOrigin() && !isDryRun()`, pushes through `GitPushReconciler`. Today
it records only the *path* (`dependentRepoCommits`), and the URL is lost after
`prepareDependentRepos`.

Changes:

- **New value type `DependentRepoPush`** (record: `repoUrl`, `branch`,
  `commitHash`) in `io.flowtree.jobs`. It owns its own JSON form, with
  `toJson(ArrayNode)` and a static `listFromJson(String body)` built on Jackson
  like `JobCompletionEvent.toJson`. The controller then parses what the job
  wrote with the same code.
- **`GitRepositorySetup`** keeps the URL with each path. It replaces
  `List<String> dependentRepoPaths` with an ordered path→URL map, and keeps
  `getDependentRepoPaths()` as a view so `InvalidFileDetector` and other
  callers stay unchanged. It adds `getDependentRepoUrl(String path)`.
- **`GitCommitHandler.handleDependentRepos`** records a `DependentRepoPush` after
  the push returns, using `git rev-parse HEAD` in that repo for the hash. It
  records nothing on dry runs, on push-disabled jobs, or when nothing was
  committed. It exposes the list through `getDependentRepoPushes()`.
- **`JobCompletionEvent`** gains `withDependentRepoPushes(List<DependentRepoPush>)`
  and a getter, and serializes them in `toJson()` as `"dependentRepoPushes"`.
- **`GitManagedJob.fireJobCompleted`** attaches the pushes. `GitManagedJob` is at
  1594 of 1600 lines, so this is not done by adding lines there. The block that
  copies staged/skipped files, commit hash, pushed flag, and PR URL from
  `commitHandler` onto the event moves into
  `GitCommitHandler.describe(JobCompletionEvent, String targetBranch, boolean
  pushToOrigin)`. That method fills the event and includes the new pushes, so
  `fireJobCompleted` shrinks. The behavior belongs on the handler, because it
  reads only the handler's state.

### 2. GitHub API: `io.flowtree.github.GitHubProxyHandler`

Every API URL is hard-coded to `https://api.github.com`, so no test can point the handler at a fake. Changes:

- Add an API root field. The existing constructor defaults it to
  `https://api.github.com`, and a second constructor accepts it so tests can use a
  local `com.sun.net.httpserver.HttpServer`. Every URL builder (including
  `commitApiUrl`) uses it. The proxy endpoint's host check compares
  against that root's host.
- New operations, each returning `null`/`false` on failure in the same style as
  the existing ones:
  - `fetchDefaultBranch(ownerRepo, token)`: `GET /repos/{o}/{r}`, `default_branch`.
  - `isAhead(ownerRepo, base, head, token)`: `GET /repos/{o}/{r}/compare/{base}...{head}`,
    `ahead_by > 0`. This is the "branch differs from default" test.
  - `findOpenPullRequest(ownerRepo, branch)`: built on the existing
    `listPullRequestsForBranch`, returning the first `PullRequest.isOpen()`. This
    gives "reuse, never duplicate".
  - `commentOnPullRequest(ownerRepo, number, body, token)`:
    `POST /repos/{o}/{r}/issues/{n}/comments`.
- `createPullRequestFromCommit` gets an overload that takes a body suffix (the
  primary-PR link line) appended to the derived body. `pullRequestTitle` and
  `pullRequestBody` stay as they are.

### 3. Controller side: `io.flowtree.api`

`FlowTreeApiEndpoint` is at 1582 lines. The task needs more logic in the
auto-PR block of `handleStatusEvent`, so that block and its
state move out of the endpoint:

- **New collaborator `AutoPullRequestPublisher`** (package `io.flowtree.api`,
  next to `AutoPrContext`). It owns the `autoCreatePrJobs` map now held by the
  endpoint (`register(taskId, AutoPrContext)` from `handleSubmit`, and `discard`
  for non-success completions) and a `publish(JobCompletionEvent)` method. That
  method does what the endpoint's auto-PR block does now: it opens the primary PR, records its
  URL on the event, and then handles every `event.getDependentRepoPushes()` entry:
  1. resolve the token for the dependent repo's own org
     (`extractOrgFromRepoUrl(push.repoUrl())`), since the dependent repo can be
     in another org;
  2. `fetchDefaultBranch`, falling back to `master` when it cannot be read;
  3. `isAhead(default, push.branch())`, and stop if it is not ahead;
  4. `findOpenPullRequest(push.branch())`: if one exists, record its URL and
     stop (reuse);
  5. `createPullRequestFromCommit(..., push.commitHash(), ..., suffix)`, where
     the suffix links the primary PR URL when one exists. Use the primary
     context's `draft` flag, so a draft primary gets a draft companion;
  6. when the companion is newly opened and a primary PR exists, call
     `commentOnPullRequest` on the primary with the companion's URL and the
     line "both must merge".

  When the job had no `AutoPrContext` (`autoCreatePr` not requested) but the
  event carries pushes, `publish` opens nothing and logs a `warn(...)` that
  names each repo, branch and commit as unreviewed.
- The primary PR to link is whatever `event.getPullRequestUrl()` ends up as. It
  is either the PR the job detected (`GitCommitHandler.detectPullRequestUrl`) or
  the one just auto-created.
- `JobCompletionEvent` gains `withCompanionPullRequestUrls(List<String>)` so the
  result is visible downstream.
- `handleStatusEvent` parses `dependentRepoPushes` from the body with
  `DependentRepoPush.listFromJson` and calls `publisher.publish(event)`. The
  endpoint gets shorter.

### 4. Notification

`SlackNotifier` builds the completion message (`":link: PR:"`).
Add one line per companion PR, and when pushes exist without companion PRs, add
a line listing the unreviewed dependent-repo commits (repo, branch, short hash).
That is the "message" half of "log or message".

### Placement notes

- `DependentRepoPush` lives in `io.flowtree.jobs` with `JobCompletionEvent`,
  which carries it. The job produces it and the controller consumes it, and the
  runtime module holds both.
- The new GitHub operations go on `GitHubProxyHandler`, which already owns every
  REST call to GitHub. No new client class.
- `AutoPullRequestPublisher` is a concept the endpoint already had: the pending
  auto-PR registry and its completion behavior. It is extracted, not invented.

## Success criteria

New tests in `flowtree/runtime/src/test/java`. All extend `TestSuiteBase`, all
have `@Test(timeout = ...)`, and a fake GitHub API runs on a local
`com.sun.net.httpserver.HttpServer`. The fake keeps an in-memory list of PRs and
comments and serves `GET /repos/{o}/{r}`, `GET .../compare/...`,
`GET .../pulls?head=...`, `GET .../commits/{ref}`, `POST .../pulls`, and
`POST .../issues/{n}/comments`.

- **`AutoPullRequestPublisherTest`** (`io.flowtree.api`):
  - `companionPrOpenedAndLinkedBothWays`: an event with a primary branch and one
    `DependentRepoPush` with `autoCreatePr` produces exactly one new PR in the
    dependent repo, based on its default branch. The PR's title and body come
    from the fake commit message, and its body contains the primary PR URL. The
    primary PR receives one comment containing the companion URL.
  - `noDependentPushCreatesNothing`: an event with no pushes makes no request to
    the dependent repo. The fake records zero calls under that repo.
  - `existingCompanionPrIsReused`: with an open PR already on the dependent
    branch, publishing again opens no new PR, adds no second comment, and reports
    the existing PR's URL.
  - `branchEqualToDefaultCreatesNothing`: `ahead_by: 0` opens nothing.
  - `withoutAutoCreatePrNothingIsOpened`: pushes with no `AutoPrContext` make
    no `POST` requests and leave the unreviewed commits recorded.
- **`GitHubProxyHandlerTest`**: covers the new URL construction against a custom
  API root, and `findOpenPullRequest` skipping closed PRs.
- **`JobCompletionEvent` round-trip**: `toJson()` → `DependentRepoPush.listFromJson`
  preserves URL, branch, and hash. Add it to the existing event/serialization
  tests in `io.flowtree.jobs`.
- **`GitCommitHandler`**: an existing git-backed test fixture
  (`GitManagedJobGitOperationsTest` style, with local bare repos as origins)
  shows that a job changing a file in a dependent repo reports one
  `DependentRepoPush` with the pushed HEAD hash, and that a job leaving the
  dependent repo untouched reports none.
- `mvn clean install -DskipTests` passes, the new and existing tests above pass,
  and `ar-build-validator` (checkstyle, code_policy, test_timeouts,
  duplicate_code) is clean. `FlowTreeApiEndpoint` and `GitManagedJob` both end
  shorter than they start.

## Dependencies

None. The task has no `blocked_by`, and everything it builds on is on master:
`AutoPrContext`, `createPullRequestFromCommit`, `listPullRequestsForBranch`, and
dependent-repo commit/push. The work runs on any node, so no Metal or macOS
label is needed.

## Open questions

1. **`autoCreatePr` versus an existing primary PR.** The task says the companion
   PR follows the primary job's `autoCreatePr`. In the incident the primary
   (downstream) PR already existed, and the job may well have been submitted
   without `autoCreatePr`, as ordinary workstream jobs usually are. In that case
   this plan opens nothing and only reports the commits as unreviewed, so the
   same incident could happen again with a louder notification. **Recommendation:**
   also open the companion PR when the job did not request `autoCreatePr` but its
   event carries a primary PR URL (the job detected an open PR on its branch),
   since a reviewer is already looking at the primary. This is a one-line
   condition in `AutoPullRequestPublisher.publish`. The approver should decide.
2. **Primary PR creation fails** (no token, API error) while `autoCreatePr` was
   requested. The plan still opens the companion PR, without the primary link,
   and the notification says the link is missing. The alternative is to skip it.
3. **Restart durability.** `autoCreatePrJobs` is in memory, so a controller
   restart between submit and completion drops both the primary and the
   companion PR. This exists today and is not widened here. It could become a
   follow-up task if the approver wants it.
4. **Re-run with new commits on an already-open companion PR.** The plan reuses
   the PR and does not edit its title or body. Later commits show up in the PR
   naturally. It does not add a fresh comment on each run.
   <!-- TODO(review): if the companion PR is opened before any primary PR exists, a later run that reuses it never adds the primary link or the reciprocal comment; decide whether reuse should backfill missing links. -->

## Estimated complexity

**Medium.** Roughly two new production types (`DependentRepoPush`,
`AutoPullRequestPublisher`), four new `GitHubProxyHandler` operations plus an
injectable API root, small changes to `GitRepositorySetup`, `GitCommitHandler`,
`JobCompletionEvent`, `SlackNotifier`, and two length-reducing extractions
(`FlowTreeApiEndpoint`, `GitManagedJob`). Most of the test effort is the fake
GitHub server. It fits in one change. If it has to be split, the natural seam is
(a) the job reporting pushes, together with the event serialization, then (b) the
controller opening companion PRs.
