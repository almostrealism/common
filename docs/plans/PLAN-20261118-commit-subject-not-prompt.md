---
status: in-progress
created: 2026-11-19
closed:
tracker: []
---

# The harness must never use the raw task prompt as a commit subject or PR title

**Branch:** `feature/commit-title-not-prompt`
**Release:** the release under development (the version in the root `pom.xml`), project "Common"
**Estimated complexity:** Small

This plan carries proposal P2 (`commit-msg-prompt-fallback`) from QA retro
RETRO-20261118.

---

## Motivation

When a coding-agent job finishes without writing `commit.txt`, the harness still
has to commit the work the agent produced, so it synthesizes a message. The
synthesized message's subject line is also what an auto-created pull request
takes as its title (`GitHubProxyHandler.pullRequestTitle` via
`AutoPullRequestPublisher.createPullRequestFromCommit`).

The fallback used to build that subject from the **task prompt** — the
instruction text the job was given. That is the wrong source, for a reason that
holds in every case:

- **A prompt says what was asked for, not what was done.** The agent may have
  done part of the task, taken a different approach, or changed unrelated files
  along the way. A subject copied from the prompt describes an intent, not a
  diff, so the commit history and the PR title misrepresent the change.
- **A prompt is not a subject.** Task prompts are paragraphs — often many
  sentences of instructions, constraints, and context. Truncated to a subject
  line they read as a fragment of an order ("Write a planning document for this
  workstream at the target path. Path:..."), which is noise in `git log --oneline`
  and worse as a PR title, where it looks like the agent's own instructions
  leaked into the repository.
- **A prompt can carry content that must not be published.** Prompts routinely
  contain identifiers, internal context, and private-product motivation that the
  public record must never carry (see the root `CLAUDE.md` rules on temporary
  identifiers, private links, and private product names). Routing the prompt
  into a commit subject or a PR title is a standing channel for leaking exactly
  that content.

The fix is to derive a short, descriptive subject from **the change itself** —
the files the job actually modified — and never from the prompt. The prompt is
removed as a fallback source entirely, for both the commit subject and the
auto-created PR title, so there is no path by which it can reappear.

## Scope

In scope:

1. The commit-time fallback the harness applies when `commit.txt` is missing or
   empty (`CommitMessageBuilder.buildFallbackMessage`).
2. The exhaustion fallback `EnforcementRunner` writes when `CommitMessageRule`
   runs out of correction attempts (`CommitMessageBuilder.buildExhaustionFallback`).
3. The auto-created PR title path, confirmed to take its title from the commit
   subject and to carry no independent prompt fallback of its own.

Out of scope:

- The agent-authored path. When the agent writes `commit.txt`, that file is the
  source of truth and is unchanged by this plan (attribution sanitizing via
  `AuthorAttribution` still applies).
- The dependent-repository fallback (`buildDependentFallbackMessage`), which
  already names the repository rather than the prompt and needs no change.
- The wire values of the source tags (`agent`, `prompt_fallback`,
  `commit_rule_recovered`). The `prompt_fallback` tag keeps its name as a stable
  identifier for "the harness generated this because the agent wrote no
  message"; only the *content* it produces changes.

## Approach

All of the work lives in
`flowtree/runtime/src/main/java/io/flowtree/jobs/CommitMessageBuilder.java`.

### 1. Build the subject from the changed files

A new `buildChangeSubject(CodingAgentJob)` reads the files the job changed in its
working tree and summarizes them into a subject:

- Read the uncommitted changes from `git status` via
  `GitOperations.getChangedFiles(workingDir)`, filtered by
  `GitOperations.isExcludedPath` to drop harness scratch paths. `getChangedFiles`
  uses an stdout-only, stderr-discarding invocation, so in a non-git directory it
  returns an empty list cleanly rather than a bogus "fatal: not a git
  repository..." path. (This is why `getChangedFiles` is used here rather than
  `job.extractChangedFilePaths()`, which merges stderr and would surface the
  error text as a fake changed path.)
- `summarizeChangedFiles(List<String>)` reduces the paths to base names (no
  directory, no extension), deduplicated in order, and forms one of:
  - `Update <A>` — one file,
  - `Update <A> and <B>` — two files,
  - `Update <A> and N other files` — more than two,

  keeping the subject within git's conventional 72-column limit.
- When no changed file can be determined — a clean tree, or a `git status` that
  could not be run — the subject names the job neutrally (`Job <taskId> commit`)
  and **never** the prompt.

### 2. Rewrite the two fallbacks to use it

- `buildFallbackMessage` uses the change-derived subject and a body that records
  only that the harness generated the message, plus the session id and exit code
  for forensics. The body no longer echoes the prompt.
- `buildExhaustionFallback` returns the same change-derived subject (a single
  subject line), replacing the prompt-derived text that previously produced
  PR titles reading like the agent's own instructions.

### 3. Confirm the PR-title path carries no prompt fallback

The auto-created PR title takes the commit subject line
(`GitHubProxyHandler.pullRequestTitle` via
`AutoPullRequestPublisher.createPullRequestFromCommit`). Verification found that
its non-commit fallback was *not* prompt-free: the submit endpoint registered
`CodingAgentJobFactory.getDisplaySummary()`, which returns a prompt of 80
characters or fewer verbatim when no description is set. So the PR title, and
the body of a subject-only commit, could still be the prompt. The submit
endpoint now registers `AutoPrContext.forJob(factory, ...)`, whose fallback is
the explicit description or `Job <taskId>`, never the prompt.

The commit-time subject is read from the staged index
(`FileStager.GitOperations.stagedPaths()`), not from the whole working tree, so
a file that the staging guardrails skipped is not named in a commit it is
absent from. When the index is empty, as it is when `EnforcementRunner` writes
the exhaustion fallback, the subject is read from `git status`.

### Placement notes

Deriving a subject from a job's changed files is a fact about the job's commit
message, so it stays on `CommitMessageBuilder`, which each `CodingAgentJob`
already owns as the single collaborator for "an agent that wrote no message
still gets its work committed." No new helper or utility class is introduced;
`buildChangeSubject`, `summarizeChangedFiles`, and the base-name helpers are
methods on the builder that owns the concept.

## Success criteria

- `CommitMessageBuilderTest` is rewritten so that, for a job that wrote no
  `commit.txt`:
  - a working tree with one changed file produces `Update <name>`,
  - two changed files produce `Update <A> and <B>`,
  - more than two produce `Update <A> and N other files`, within 72 columns,
  - a clean or non-git tree produces `Job <taskId> commit`,
  - **no** generated subject or body contains any substring of the task prompt.
- The exhaustion fallback produces the same change-derived subject, not the
  prompt.
- The source tag remains `prompt_fallback` on the no-`commit.txt` path (wire
  compatibility), while the content is change-derived.
- `mvn clean install -DskipTests` passes and `ar-build-validator` (checkstyle,
  code_policy, test_timeouts, duplicate_code) is clean.

## Relationship to the implementation on the branch

The implementation described here is already on this branch and matches the plan:
`buildFallbackMessage` and `buildExhaustionFallback` build the subject through
`buildChangeSubject` → `summarizeChangedFiles`, the prompt is removed from both
the subject and the fallback body, the `prompt_fallback` wire tag is unchanged,
and the PR-title fallback was changed from the display summary (which could be the
prompt) to a prompt-free `AutoPrContext.forJob` description. `CommitMessageBuilderTest`
was rewritten accordingly. This document records the intent and the contract so
the branch and any follow-up review share one reference.

## Dependencies

None. Everything this builds on is already present in `flowtree/runtime` and
`flowtree/base`. The change runs on any node; no hardware label is required.

## Estimated complexity

**Small.** One new subject-building method and one summarizer on
`CommitMessageBuilder`, two fallback rewrites that call them, a confirmation that
the PR-title path needs no change, and a rewritten `CommitMessageBuilderTest`.
