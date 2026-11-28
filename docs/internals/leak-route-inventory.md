# Private-link leak route inventory

This is the red-team route map for the "Never Publish a Private Link" rule
(root `CLAUDE.md`). It traces, in this repository's code and configuration,
**every path by which text produced by an agent or by automation leaves the
system for a place others can read**, and records for each route its code
location, the credential it uses, and whether the leak-guard task's enumerated
routes (the task titled *"Block private Claude links from reaching GitHub;
audit and remove past leaks"*) already cover it.

It is the companion of the adversarial corpus in
[`tools/leak-corpus/`](../../tools/leak-corpus/README.md), which generates every
obfuscated form a payload can take; this document is about *where* a payload can
be injected, that corpus is about *how* it can be disguised.

The guard task enumerates eleven routes. They are referenced here as **G1**
(ar-manager GitHub write tools), **G2** (controller GitHub proxy), **G3**
(FlowTree harness egress — commit.txt, auto-PR title/body, staged diff and new
files, branch names), **G4** (credential isolation), **G5** (CI workflows that
post text), **G6** (public CI logs and artifacts), **G7** (private stores
quoted into public places — tracker, memory, messages), **G8** (published
images and packages), **G9** (Slack posts), **G10** (GitHub-side enforcement),
**G11** (harness attribution settings).

## The one detector today

A single matcher exists: `.claude/hooks/lib/private_url_check.py`, run by the
`PreToolUse` hook `.claude/hooks/block-private-url.sh` (and the opencode
adapter `.opencode/plugins/block-private-url.ts`). It flags a `claude.ai` page,
a bare `session_<id>`, or a URL whose host is a lab machine.

Two facts about it shape every row below:

1. **It is hook-only and in-session.** No server-side or CI egress path
   imports `private_url_check` or applies an equivalent filter. Verified: no
   file under `flowtree/` or `tools/mcp/` references the detector. So every G2,
   G3 (server side), G5, G6, G7, G8, G9 route is *uncovered in code today* —
   they are the guard work still to be built, not routes that are already
   filtered.
2. **It performs no normalisation.** `CLAUDE_AI_LINK` requires the literal
   host; `SESSION_ID` is `\bsession_[A-Za-z0-9]{16,}\b` and is **not**
   case-insensitive. Every URL-encoded, HTML-entity, NFKC/fullwidth,
   confusable/homoglyph, zero-width, line-split and case-folded form in the
   corpus slips past it. This is inside the guard task's stated scope
   ("Obfuscated forms, normalised before matching"), so it is a correctness gap
   the guard must close, demonstrated by the corpus — not a new route. See
   "Detector-matcher gaps" at the end.

## Routes

### GitHub writes via ar-manager MCP tools (G1)

All ar-manager GitHub writes are issued by the tool implementations under
`tools/mcp/manager/` and forwarded by the controller proxy (next section).

| Tool | File (symbol) | GitHub endpoint | Agent text published | Guard route |
| --- | --- | --- | --- | --- |
| `github_create_pr` | `github_tools.py` (`github_create_pr`) | `POST …/pulls` | `title`, `body` | G1 |
| `github_update_pr` | `github_tools.py` (`github_update_pr`) | `PATCH …/pulls/{n}` | `title`, `body` | G1 |
| `github_pr_reply` | `github_tools.py` (`github_pr_reply`) | `POST …/pulls/{n}/comments/{id}/replies` | `body` | G1 |
| `github_request_copilot_review` | `github_tools.py` | `POST …/requested_reviewers` | bot login only (no prose) | G1 (n/a) |
| `github_set_pr_state` | `github_lifecycle_tools.py` (`github_set_pr_state`) | `PATCH …/pulls/{n}` | `state` only (no prose) | G1 (n/a) |
| `github_start_test_run` | `github_lifecycle_tools.py` (`github_start_test_run`) | `POST …/deployments` | **branch name as `ref`** | G1 — **gap, see below** |
| `github_rerun_failed_jobs` | `github_lifecycle_tools.py` | `POST …/actions/runs/{id}/rerun-failed-jobs` | none | G1 (n/a) |

Credential: none in ar-manager itself. The tools call the controller proxy,
which holds the per-org PAT.

The in-session detector's `GITHUB_WRITE_TOOL` regex is
`github_(create_pr|pr_reply|.*comment.*|.*update.*|.*edit.*)`. It matches the
first three rows and misses the rest — acceptable for the three that carry no
prose, **but a concrete gap for `github_start_test_run`** (see
"Gaps the guard task does not cover").

### The controller GitHub proxy (G2)

`flowtree/runtime/src/main/java/io/flowtree/github/GitHubProxyHandler.java`
(~1537 lines) is the single egress for every GitHub write — `GET/POST/PUT/PATCH`
to any endpoint. `commentOnPullRequest(...)` posts
`POST …/issues/{n}/comments`; the create/update PR paths post pull bodies.
Bodies are JSON-escaped (`JsonFieldExtractor.escapeJson`) but **not scanned for
private links**. Credential: a per-org PAT resolved by `resolveGithubToken(org)`
from `githubOrgTokens` (loaded from `workstreams.yaml`). This is the "a tool
that is missed is still caught" backstop the guard task wants (G2); today it
performs no filtering.

### FlowTree harness egress (G3)

| Route | File (symbol) | Destination | Credential | Filtering today |
| --- | --- | --- | --- | --- |
| Commit message from `commit.txt` / fallback | `flowtree/runtime/.../jobs/CommitMessageBuilder.java` | git history (public on push) | git identity env | none for private links |
| Authorship-trailer stripping | `flowtree/runtime/.../jobs/AuthorAttribution.java` (`sanitize`) | — | — | strips *attribution* only, **not** private links |
| Commit + push | `flowtree/runtime/.../jobs/GitCommitHandler.java` (`commit`, `push`) → `GitPushReconciler` | `origin` (GitHub) | SSH key via `GIT_SSH_COMMAND` | none |
| File staging (diff, new files, `.flowtree/*.md`) | `GitCommitHandler.stageFiles` → `FileStager`, `FlowtreeArtifacts.isExcludedFromCommit` | git history | — | `.flowtree/` artifacts excluded by path; file *contents* not scanned for links |
| Auto-PR title/body | `flowtree/runtime/.../api/AutoPullRequestPublisher.java` (`openPrimaryPullRequest`) | GitHub PR | `resolveGithubToken(org)` | none — `prCtx.description` from job submission |
| Companion PR + cross-link comment | `AutoPullRequestPublisher.publishCompanion` → `GitHubProxyHandler.commentOnPullRequest` | GitHub PR + comment | per-org PAT | none |
| Branch name | job target branch, pushed as the PR head ref | GitHub refs + PR head | SSH key | **none — see gap below** |

`AuthorAttribution` is the only existing content filter on this path and it
targets authorship lines, not private links. A session link in `commit.txt`
body, in the auto-PR `description`, in a staged file's contents, or in the
branch name reaches GitHub today. G3 names all of these as work to do.

### Credentials that can write to GitHub (G4)

| Holder | What | Location |
| --- | --- | --- |
| Controller | per-org GitHub PAT | `githubOrgTokens` from `workstreams.yaml`, used by `GitHubProxyHandler.resolveGithubToken` |
| Node host / agent | SSH key for `git push` | `GIT_SSH_COMMAND` set by `GitOperations` |
| Agent session env | `GH_TOKEN` / `GITHUB_TOKEN`, proxy-injected git credentials | agent process environment (this job's own env has them) |
| CI | `GITHUB_TOKEN`, any PAT | `.github/workflows/*.yaml` job env |

G4's requirement — "only the filtering egress path may hold a write credential"
— is **not met today**: an agent process with `GH_TOKEN`/`GITHUB_TOKEN` and
injected git credentials, and a node host with an SSH key, can write to GitHub
directly, bypassing every in-code filter. This is the credential-isolation work
of the guard task and the part it flags for explicit owner action (revocation).

### CI workflows that post text, and public logs/artifacts (G5, G6)

- `.github/workflows/` and `tools/ci/` were searched for comment-posting steps
  (`gh pr comment`, `gh issue comment`, `…/comments`, `createComment`). The
  only hit is documentation text in `tools/ci/prompts/general-review.txt`
  ("Do not post comments … make the changes yourself") — no workflow step posts
  agent-authored comment text found. PR status labels
  (`.github/workflows/pr-status-labels.yaml`, `tools/ci/pr-status-labels.py`)
  apply fixed labels, no agent prose. (G5: no active in-repo route found today;
  the review/comment posting that does happen flows through G1/G2.)
- CI uploads artifacts (`actions/upload-artifact` in `analysis.yaml`) and prints
  logs; on a public repo these are public. The guard task (G6) requires
  transcripts/agent logs/job output be scanned before print/upload. No such
  scan exists today. **This directory is under the CI-file lock — edits go on a
  `ci/` branch; file a task, do not edit here.**

### Private stores quoted into public places (G7)

| Store | Write path (file / symbol) | Filtering today |
| --- | --- | --- |
| Tracker tasks | `tools/mcp/manager/tracker_tools.py` (`tracker_create_task`, `tracker_update_task`) → `tracker_client._tracker_post/_put` | length caps only; no link filter |
| Memory | `tools/mcp/manager/memory_tools.py` (`memory_store`) → ar-memory service | none |
| Messages | `tools/mcp/manager/messaging_tools.py` (`send_message`) → controller mailbox + Slack | none |

Agents routinely quote tracker descriptions, memories and messages into PR
bodies and commits. G7 wants matching content refused or redacted *on write* to
these stores so it cannot be laundered through them. Nothing filters them today.

### Published images and packages (G8)

- `tools/mcp/manager/Dockerfile` bakes the consult corpus (docs + source). The
  consult corpus includes notes from prior sessions; if a memory containing a
  private link is baked in, the published image carries it. `test_dockerfile_packaging.py`
  asserts packaging contents. G8 explicitly names "ar-manager with its consult
  corpus" — verify at build time whether session data / memories reach the image.
- `flowtree/runtime/.../controller/Dockerfile` and the agent image bake compiled
  JARs and config; no transcripts/memories observed. (G8.)

### Slack and alerts (G9)

| Route | File (symbol) | Destination | Credential |
| --- | --- | --- | --- |
| Job/PR event → Slack | `flowtree/runtime/.../slack/SlackNotifier.java`; `flowtree/runtime/.../api/GitHubWebhookHandler.java` → `NotifierRegistry` | Slack channel | Slack token |
| `send_message` → Slack channel | `tools/mcp/manager/messaging_tools.py` (`send_message`) | Slack channel + mailbox | controller bearer |
| `send_alert` → owner | `tools/mcp/manager/messaging_tools.py` (`send_alert`) | owner SMS/DM (not a public surface) | controller bearer |

G9 wants the same detector applied to Slack posts from the controller and
agents. `send_alert` targets the owner privately, so it is lower risk, but the
channel posts (`send_message`, `SlackNotifier`) are the surface G9 names.

### GitHub-side enforcement and harness attribution (G10, G11)

- G10 (secret-scanning push protection / rulesets / a redact-and-alert
  `pull_request`/`issue_comment`/`push` backstop) is GitHub configuration plus a
  workflow under the CI-file lock. Out of scope for this branch — the guard task
  already flags it as a `ci/` follow-up.
- G11 (managed Claude Code attribution settings on nodes; removing any
  attribution/session instruction from harness prompts/templates/docs). Anything
  under `.claude/` must be filed as a task, not edited here.

## Gaps the guard task does not cover

These are routes or matcher weaknesses that the eleven enumerated routes do not
clearly address. Each is filed as a tracker task (see the branch summary for
ids) with the corpus case that reproduces it.

1. **`git push` branch names / refspecs are not scanned in-session.** The
   detector's `GIT_PUBLISHING` set is `{commit, tag, notes, merge}` and
   `PUBLISHING_MENTION` never fires for `git push`. An agent can
   `git push origin HEAD:refs/heads/<session-id>` and publish a private id into
   GitHub refs and the PR head ref with no in-session block. G3 names "branch
   names" for the *server-side harness*, but the in-session Bash hook — the only
   thing that exists today — has no branch-name coverage at all. Corpus:
   `placement_branch`, `placement_refspec` (surface `branch_name`).
   Location: `.claude/hooks/lib/private_url_check.py` (`GIT_PUBLISHING`,
   `PUBLISHING_MENTION`, `_publishing_commands`).

2. **`github_start_test_run` publishes a branch name as a GitHub deployment
   `ref`, unscanned.** The tool's own comment
   (`github_lifecycle_tools.py`) asserts a test run "carries only a branch name
   … so there is no prose or URL for the private-link guard", and
   `_test_run_branch_error` validates only ref *shape* (rejecting fork heads,
   pull refs, fully-qualified refs) — never private-link *content*. The
   detector's `GITHUB_WRITE_TOOL` regex does not match `github_start_test_run`.
   So a branch named for a session id is published as a public deployment ref
   with no block at either layer. G2 lists "deployments' payload/description"
   but not the `ref`; G1 does not match the tool. Corpus: `placement_refspec`,
   `placement_branch`. Location: `tools/mcp/manager/github_lifecycle_tools.py`
   (`github_start_test_run`, `_test_run_branch_error`);
   `.claude/hooks/lib/private_url_check.py` (`GITHUB_WRITE_TOOL`).

## Detector-matcher gaps (in scope for the guard, demonstrated by the corpus)

These are not new routes; they are the normalisation the guard task already
requires, which `private_url_check.py` does not yet do. The corpus asserts each
through an `expectedFailure` so it ratchets shut when implemented:

- URL-encoding (single and double), HTML entities (decimal, hex, named),
  NFKC/fullwidth, confusable/homoglyph letters, zero-width insertion,
  line-splitting (plain and hyphen-wrapped), and combinations of these.
- The session word matched case-sensitively (an uppercased `SESSION_…` slips
  through).
- A bare host with no scheme and no path (e.g. inside `noreply@<host>`), which
  neither `CLAUDE_AI_LINK` (needs a trailing `/`) nor `URL_HOST` (needs a
  scheme) catches.
- A host munged with a non-dot separator (`claude-ai`) in a branch name.
- A raw session-credential value (a token shape), which the detector does not
  look for at all.
