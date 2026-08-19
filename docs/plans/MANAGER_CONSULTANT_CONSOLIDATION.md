# ar-manager / ar-consultant Consolidation

Status: **IN PROGRESS — Phases A, B, C, D implemented. Only the removal of consultant `recall` remains, held until the first green deploy proves the corpus reaches the running image.**
Author: planning session, 2026-08-18

Follow-up to the deferred item recorded in
[AR_MANAGER_HTTP_ONLY_MIGRATION.md](AR_MANAGER_HTTP_ONLY_MIGRATION.md) §9:
"ar-consultant is PARTIALLY duplicative of ar-manager … ar-consultant is KEPT
(not removed), but the state-touching portion must come under the token model
later."

---

## 0. Guiding constraint

`ar-manager` is reachable from **every** repository (remote HTTP MCP server,
registered user-scoped). `ar-consultant` is a **repo-local stdio** entry in this
repo's `.mcp.json` and only exists while working inside `common`. Therefore:
**where the two overlap, the capability must end up in ar-manager.** Anything
left only in ar-consultant is unavailable in the majority of sessions.

---

## 1. Where the overlap actually is

The inventory found exactly **three** overlapping tool pairs, all memory-related.
Everything else is disjoint.

| ar-consultant | ar-manager | Relationship |
|---|---|---|
| `branch_catchup` | `workstream_context` | manager is a **strict superset** |
| `recall` | `memory_recall` | near-parity; manager lacks doc blending |
| `remember` | `memory_store` | manager lacks write-side reformulation |

No manager counterpart exists for `consult`, `search_docs`,
`start_/continue_/end_consultation`, `recall_namespaces`, `consultant_status`,
`list_/export_request_history`. No consultant counterpart exists for any
`github_*`, `tracker_*`, `workstream_*`, `project_*`, `workspace_*`, or
controller tool.

### The substrate is already shared

This is why the work is tractable rather than a rewrite:

- Both servers reach the **same** canonical store — the central ar-memory HTTP
  service — through the same client. `manager/server.py:93` `_get_memory_client()`
  and `consultant/memory_client.py:24` both construct
  `common/memory_http_client.py` `MemoryHTTPClient`.
- Both already import the reformulation **read** helpers from
  `tools/mcp/common/memory_text.py` (`manager/server.py:85`,
  `consultant/server.py:55`), and both expose an identical
  `reformulated: bool = False` parameter.
- Both build an LLM from `tools/mcp/common/inference.py` `create_backend`
  (consultant at import; manager lazily at `manager/server.py:118`).
- The ar-manager container is **already** passed `AR_CONSULTANT_BACKEND` and
  `AR_CONSULTANT_LLAMACPP_URL` (`flowtree/runtime/controller/docker-compose.yml`).

Reading reformulated text is therefore **not** a gap. Only the write path and
doc blending are.

---

## 2. Phase A — retire `branch_catchup`

**Finding: `workstream_context` already works without a `workstream_id`.**

`_resolve_branch_context` (`manager/server.py:2641`) short-circuits at line 2666:

```python
if repo_url and (branch or not require_branch):
    return (repo_url, branch, None)
```

With explicit `repo_url` + `branch` and no workstream:

- **memories** — works (`client.search_by_branch`).
- **commits** — works; `_find_workstream` is skipped and the GitHub org context
  falls back to `_current_github_org.set(owner)`.
- **pull_request** — works, same org fallback.
- **jobs** — omitted, which is correct: with no workstream there are no jobs.

So the "make it work without a workstreamId" prerequisite is **mostly already
satisfied**. Two real defects remain:

### A1. The base-branch fallback is hardcoded

```python
ws = _find_workstream(workstream_id) if workstream_id else None
base = ws.get("baseBranch", "master") if ws else "master"
```

Without a workstream the compare base is always `master`. For any repo whose
default branch is `main`, the Compare API call 404s and `commits` comes back as
`commit_error` — silently degrading the exact stream `branch_catchup` exists to
provide. Fix: resolve the repo's default branch from the GitHub API
(`GET /repos/{owner}/{repo}` → `default_branch`) when no workstream supplies
`baseBranch`, and cache it alongside the existing workspace-map cache
(`WORKSPACE_CACHE_TTL`).

### A2. `branch` is mandatory

`require_branch` defaults to `True`, so `workstream_context` cannot be called
with a repo alone. That is acceptable for a branch-narrative tool; no change
proposed. Noted so it is not rediscovered as a bug.

### A3. Lazy workstream auto-creation — recommend against

The alternative floated (auto-create a workstream on first reference) is
**not recommended**, for two reasons:

1. `workstream_context` is a **read** tool. Having a read create persistent
   state violates the shape of the tool and means every incidental "what's on
   this branch?" mints a workstream that must later be archived.
2. It is unnecessary. The no-workstream path above already returns every stream
   except `jobs`, and `jobs` is meaningless for a branch with no workstream.

`workstream_register` remains available for explicit creation when work on a
branch actually becomes a workstream. If auto-creation is still wanted later, it
belongs on the **submit** path (first job submitted against an unregistered
branch), not on a read.

### A4. Removal — DONE

`branch_catchup` is removed from `consultant/server.py`, and the base-branch
defect is fixed in `github_api.default_branch` (a cached GitHub lookup, only
successes cached so a transient outage cannot pin the wrong value). Three call
sites carried the hardcoded fallback, not one: `workstream_context`,
`project_create_branch`, and `github_create_pr` — the last would have opened
pull requests against a nonexistent `master` on any `main`-default repository.

Original removal note:

Delete `branch_catchup` from `consultant/server.py` (~L956–1125) and its
`tracked_tool` registration. Update `tools/mcp/consultant/README.md:207`, which
names it in the retrieval-path list.

**Risk: low.** Self-contained; the replacement is a superset and already
deployed.

---

## 3. Phase B — reformulation in ar-manager

### B1. What moves

`consultant.remember` does three things `manager.memory_store` does not:
retrieve doc context for the note, ask the model to rewrite it, and persist
**both** versions via `memory.store_dual` → `encode_dual_source`.

Move the reformulation step into `tools/mcp/common/` (alongside `memory_text.py`,
which already owns the encode/decode half of this contract) so both servers call
one implementation. `MemoryClient.store_dual` (`consultant/memory_client.py:175`)
moves to the shared `MemoryHTTPClient` — manager already holds one.

Note the doc-context half of reformulation depends on `DocsRetriever`, which
ar-manager does not have until **Phase D**. Phase B should therefore land
reformulation **without** doc context first (prompt the model with the note
alone), and enrich it with doc context once Phase D ships. This keeps B and D
independently shippable.

### B2. Degradation policy — the important difference

`consultant.remember` **hard-refuses** when the model is unreachable:

```python
if synthesis.degraded:
    return {"stored": False, "degraded": True, ...}   # nothing persisted
```

ar-manager must **not** adopt this. The requirement is: reformulate when a model
is available, store the author's original text unreformulated when it is not.

**This is safe, and the reason matters.** The refusal was added because a census
found 36 stored "context dumps." Those dumps were **`PassthroughBackend` output**
— the `[Consultant model not available. Returning raw context.]` banner followed
by raw retrieved context — i.e. *model output written back into the corpus*, not
text an agent authored. `tools/mcp/memory/store.py:41` `is_passthrough_dump` plus
the `store()` guard at line 304 now reject exactly that shape at the single
chokepoint every caller passes through (`test_passthrough_guard.py`).

Storing the **author's** original text on degradation is therefore not the
failure mode the refusal was protecting against, and the store-side guard remains
as backstop. Confirm by test: a `memory_store` call with no LLM reachable must
persist the original and return a flag indicating no reformulation was produced.

Once ar-manager's write path is authoritative, the consultant refusal should be
relaxed to the same behaviour rather than left as a second, stricter policy on
the same store.

### B3. Per-repo configuration

Today the only control is `prefers_reformulated()`
(`tools/mcp/common/memory_text.py:50`), which reads the **process-wide**
`AR_MEMORY_REFORMULATED` env var. A single ar-manager process serves every repo,
so an env var cannot express "on for `common`, off for repo X."

Two distinct knobs are needed — they are not the same setting:

| Knob | Applies to | Default |
|---|---|---|
| `reformulateOnStore` | write path (`memory_store`) | proposed off |
| `preferReformulatedOnRead` | read path (`memory_recall`, `workstream_context`) | off (beta) |

**Recommended home: a new top-level `repos:` section in `workstreams.yaml`,**
served by the controller and read by ar-manager the same way `workstream_list`
is read and cached (`workspace_map.py`, `WORKSPACE_CACHE_TTL` 30s). This matches
the existing precedent of `githubOrgs` — a top-level, repo/org-keyed map in the
same file — and makes the setting editable through a tool in the style of
`workspace_update_config`, which already persists back to the YAML.

Cost: it requires a **Java controller change** (config model + the
`/api/workstreams`-adjacent endpoint), which widens the blast radius beyond
Python. The cheaper alternative is a manager-side JSON file mounted at
`/config` alongside `manager-tokens.json`; it avoids Java entirely but creates a
second configuration system with no tool-based editing and no controller
validation.

**Decision needed before implementation** — see §7.

`prefers_reformulated()` keeps working as the process-wide fallback when a repo
has no entry, so nothing regresses.

**Risk: medium**, concentrated in the config-home decision, not the reformulation
logic.

### B4. Status — DONE

- `InferenceBackend.reformulate` (`tools/mcp/common/inference.py`) holds the
  single prompt both servers use. `PassthroughBackend` degrades there, so a
  backend-down banner can never be mistaken for a rewrite.
- `MemoryHTTPClient.store_dual` (`tools/mcp/common/memory_http_client.py`) owns
  the dual encoding; `MemoryClient.store_dual` delegates and adds only the
  Consultant-local git detection.
- `memory_store` takes `reformulate`, defaults from the repository config, and
  stores the author's original with `degraded: true` when no model is reachable.
- `manager/repo_config.py` reads `/config/repo-config.json` behind
  `repo_setting()`; `preferReformulatedOnRead` is wired into both retrieval
  tools. The `/config` mount already exists, so no compose change was needed.
- The Consultant's refusal is relaxed to the same policy — one corpus, one
  write policy.

---

## 4. Phase C — remove the redundant consultant tools

### C1. The blocker: ar-manager cannot see the caller's working directory

`consultant.recall` / `remember` auto-detect repo and branch via
`common/git_context.detect_git_context()` (`consultant/server.py:442`, `:589`).
That works **only because ar-consultant is a local stdio process running in the
user's checkout.**

ar-manager runs in a **container on mac-studio**. It has no access to the
caller's filesystem, so it cannot detect the caller's repo or branch. It resolves
context three ways today, none of which covers an interactive session:

1. explicit `repo_url` / `branch` arguments,
2. `workstream_id` → workstream config,
3. the workstream bound to the in-flight **HMAC temp token** — job-scoped agents
   only (`_get_token_workstream_id`).

An interactive developer authenticating with a **personal** bearer token has
none of these. Removing `consultant.recall`/`remember` without addressing this
turns every zero-argument memory call into an error or, worse, an unscoped
write.

### C1 resolved (owner, 2026-08-18): trust the caller, guard the value

**The server trusts what the caller sends.** An interactive session states its
own `repo_url` and `branch`; ar-manager authorises the call against the token's
scopes and otherwise takes the values at face value. No server-side git
detection, no token-bound repo default, no local shim.

The residual risk is not authorisation, it is **accuracy**: the model supplying
those values is often wrong about them. It reads a stale branch name from the
harness-injected `gitStatus` snapshot — the exact failure CLAUDE.md's
"HARNESS-PROVIDED CONTEXT IS STALE" rule exists to prevent — or from a cached
value near the top of a long context window, and writes a memory onto a branch
that was switched twenty tool calls ago.

**Mitigation: a PreToolUse hook in the repos we care about** that checks the
`repo_url` / `branch` arguments against live `git` before the call leaves the
machine. This is the established idiom here (`block-memory-write.sh`,
`block-branch-track-master.sh`): the hook has filesystem access the server does
not, so it can do what ar-manager structurally cannot.

Calibrate block-vs-warn by consequence:

| Tool | Mismatch is | Action |
|---|---|---|
| `memory_store` | a **write** onto the wrong branch — silently corrupts the corpus | **block**, report live values |
| `memory_recall`, `workstream_context` | a read that returns the wrong branch's context | **warn**, report live values, allow |

Reads must not be blocked: asking about another repo or branch is legitimate
(`scope="all"` exists for exactly that). Writes have no such legitimate case
from an interactive session.

The hook allows the call unchanged when the argument is absent (the server's own
resolution then applies) and when the working directory is not a git repo. Repo
URLs compare on normalised `owner/repo`, so the `git@`/`https://`/`.git`-suffix
spellings are equivalent.

### C2. What can be removed, and when

| Tool | Status |
|---|---|
| `branch_catchup` | **removed** (Phase A) |
| `remember` | **removed** (Phase C) |
| `recall_namespaces` | **removed** (Phase C) |
| `recall` | **kept** — still gated on Phase D (doc blending) |
| `consult`, `search_docs`, consultation sessions, request history | kept — no overlap |

ar-consultant is down to nine tools, none of which writes a memory except the
opt-in consultation summary in `end_consultation`.

`recall` is the one deliberate hold-back. It blends `DocsRetriever` context into
its summary, and ar-manager has no documentation corpus until §5 lands — removing
it now would delete a capability with no replacement rather than move one. Rule 3
in `CLAUDE.md` already points at `memory_recall` as the default, so the ordering
costs nothing.

`recall_namespaces` had no manager counterpart, and the 2026-06-06 audit
identified it as the correct way to enumerate namespaces — so removing consultant
tools around it would have stranded it. **Resolved:** ar-manager now exposes
`memory_namespaces`, hitting the same `/api/memory/namespaces` endpoint through
`MemoryHTTPClient.namespace_stats`.

It takes ar-manager's `scope` selector (`repo` / `branch` / `all`) rather than
the Consultant's `all_repos` boolean, because consistency with ar-manager's own
memory tools matters more here than mirroring the tool being replaced. That
selector is now resolved by one shared `_resolve_scope_context`, extracted from
`memory_recall` so `scope` cannot come to mean different things in different
tools.

### C3. Migration surface — DONE

Removing consultant memory tools requires coordinated updates. Every one of
these names `mcp__ar-consultant__remember` or `__recall` today:

- `CLAUDE.md` — Rules 2, 3, and 12 (lines 208, 224, 348)
- `.claude/hooks/block-memory-write.sh` — steers built-in memory writes to these
  exact tool names (lines 11, 81–82, 93)
- `.claude/hooks/lib/memory_reminder_check.py` — the store/read tool-name tables
  (lines 88, 90, 136, 149) **and** `test_memory_reminder_check.py`
- `.claude/agents/policy-compliance.md` (36–37)
- `.claude/commands/review-policy.md` (13, 22)
- `docs/claude-directory-setup.md` (63, 66, 202–203)
- `docs/plans/MEMORY_REMINDER_HOOK.md` (156, 160, 282)
- `docs/internals/module-dependency-architecture.md` (988),
  `docs/internals/ci-investigation-protocol.md` (377)
- `tools/mcp/consultant/README.md`

The hook changes were behavioural, not cosmetic: `block-memory-write.sh` steered
agents **toward** tools that no longer exist, and `memory_reminder_check.py`
counted `mcp__ar-consultant__remember` as a store — so an agent calling the dead
tool would have silently stopped being reminded to store anything. Its test now
asserts that retired name does **not** reset the counter.

Two stale references surfaced that predate this work: `claude-code-job.md`
described the agent prompt as using `mcp__ar-consultant__remember` and
`branch_catchup`, while `InstructionPromptBuilder` has always emitted
`memory_store` and `workstream_context`. Corrected to match the code. No Java
change was needed anywhere in Phase C — the prompt builder never named a
consultant tool.

**Risk: medium-high** — not technically difficult, but it touches the enforcement
machinery that keeps agents storing memories at all. A half-applied migration
leaves agents steered at a dead tool.

---

## 5. Phase D — docs capability in ar-manager

### D1. The corpus is far smaller than assumed

`DocsRetriever` (`consultant/docs_retriever.py`) has **no precomputed index and
no embedding step**. It globs markdown/HTML off the filesystem per query. It also
**already** supports relocation:

```python
# Resolve project root: AR_DOCS_DIR env var takes priority (required
# when running as a pushed tool outside the common repo) ...
_env_docs = os.environ.get("AR_DOCS_DIR", "").strip()
```

The corpus it reads is `docs/`, module `README.md` files, root `CLAUDE.md`, and
the explicit `STANDALONE_MD_ROOTS` / `STANDALONE_HTML_ROOTS` lists — measured at
**~3.5 MB** (120 markdown + 35 HTML under `docs/`, plus 46 module READMEs).

**Consequence: ar-manager does not need "the entire common repo" in its image.**
It needs `docs_retriever.py` plus ~3.5 MB of documentation. That is a normal
image layer, not a structural problem.

### D2. The actual packaging obstacle

`tools/mcp/manager/Dockerfile` builds with context `tools/mcp`
(`docker-compose.yml`: `context: ../../../tools/mcp`). It cannot `COPY` from the
repo root, so it cannot reach `docs/` or the module READMEs. The change is:

1. Move the build context to the repo root.
2. Add a `.dockerignore` — without one, the whole repo (including `target/`,
   `.git`, and sample assets) is uploaded as build context on every build.
3. `COPY` the doc roots and `consultant/docs_retriever.py`; set `AR_DOCS_DIR`.
4. Extend `test_dockerfile_packaging.py`, which already exists to catch exactly
   this class of "the image is missing a module server.py imports" failure.

`docs_retriever.py` should move to `tools/mcp/common/` as part of this, since it
will then have two consumers.

### D3. Why this forces the deployment question

A doc corpus baked into an image is only as fresh as the last deploy. Today the
stack is rebuilt by running `flowtree/runtime/rebuild.sh` **by hand** on
mac-studio. Under manual deploys, ar-manager's answers would drift from the
repo's documentation with no signal — which is worse than not having the feature,
because the answers stay confident while going stale.

So the deploy pipeline is a genuine prerequisite for D, not scope creep. It is
also the highest-risk phase in this plan.

### D4. Proposed CI changes

Current state (`.github/workflows/analysis.yaml`): `python-tests` runs the
manager/common/tools suites on `ubuntu-latest`, gated on `python_changed`. There
is **no image build and no deployment step anywhere in the pipeline.**

Proposed additions:

- **`docker-build`** — builds the `ar-memory`, `ar-tracker`, `ar-manager`, and
  `flowtree-controller` images. `needs: [python-tests, test-flowtree]`. Runs on
  PRs as a **build-only** check so packaging breaks surface before merge. Uploads
  no coverage, so per Rule 10 it does **not** enter `analysis.needs`.
- **`deploy`** — `master` only, restarts the containers on mac-studio.

**The `environment:` trap must be respected here.** `.github/CLAUDE.md` documents
why `auto-resolve` was split into a separate `workflow_run` workflow: a job
carrying `environment:` in a `pull_request` run attaches a GitHub Deployment
status to the PR head, and an abandoned deployment then shows as a spurious "had
a problem deploying" failure on the PR. A `deploy` job is the textbook case.
**Follow the established pattern**: keep `deploy` out of `analysis.yaml`'s PR
path — either gate it strictly on `github.ref == 'refs/heads/master'` with
`push`, or put it in its own `workflow_run`-triggered workflow next to
`auto-resolve-submit.yaml`. Do not add `environment:` to a job that can run under
`pull_request`.

### D6. Status — IMPLEMENTED

**Packaging (D2).** `docs_retriever.py` and its tests moved to
`tools/mcp/common/` (which also puts them under CI for the first time — the
consultant directory is not in `python-tests`). The move is import-transparent:
the Consultant already had `tools/mcp/common` on `sys.path`, and
`DocsRetriever`'s repo-root computation is the same distance from the root in
either directory.

The Dockerfile now builds from the repo root, and a **pruning builder stage**
copies the directories that can hold documentation, deletes everything that is
not `*.md`/`*.html`, and the runtime stage takes only the result. Copying the
module trees directly would have carried the whole Java source tree for the sake
of a few READMEs. A root `.dockerignore` was added (none existed): it takes the
context from **771 MB to ~47 MB**, with the bulk being nested `*/results/`
directories of test output.

**Doc blending (the capability that gates `recall`).** `memory_recall` now
retrieves documentation context, feeds it to the summarizer alongside the
memories, and returns `doc_references`. The prompt asks the model to flag where
documentation contradicts a memory — a memory can be stale, and that is exactly
what grounding is for. Corpus and model are independently optional: losing
either costs part of the summary, never the memories.

**Deployment (D3).** A new `.github/workflows/deploy.yaml`, plus a `docker-build`
job in the main pipeline. Details are documented in `.github/CLAUDE.md`, which
is the authoritative CI reference.

**Restart policy (decision 5) — resolved as drain-and-fail.** The workflow
closes job intake, waits for in-flight jobs via `tools/ci/drain-agent-jobs.sh`,
and fails rather than forcing when the wait expires; `skip_drain: true` on a
manual run overrides deliberately. Chosen because a deploy that silently kills
active agent work is worse than a deploy that does not happen, and because the
override keeps the forcing option one click away. Intake is reopened in an
`always()` step so a failed deploy cannot leave the controller quiesced.

### D7. What is NOT verified

**No Docker daemon was available in the implementation environment** (`docker`
is not on `PATH`), so the image has never actually been built. The Dockerfile,
`.dockerignore`, and the corpus assertions are verified only statically —
`test_dockerfile_packaging.py` parses the Dockerfile and checks that every
imported module is packaged, that `AR_DOCS_DIR` names a `docs` directory, that
the corpus is copied, that every top-level directory holding markdown is staged,
and that the prune is present. The first `docker-build` run is what will confirm
the image is real.

The deploy workflow has likewise never run. `tools/ci/drain-agent-jobs.sh` was
tested against a stub controller (waits and succeeds when jobs finish; fails on
timeout; treats an unreachable controller and malformed payloads as zero), but
the workflow around it has not executed.

**This is why consultant `recall` is still present.** Removing it before a green
`docker-build` and a first successful deploy would mean that if the corpus does
not in fact reach the running image, documentation-grounded retrieval is gone
from both servers at once. `_get_docs()` degrades silently by design, so that
failure would be quiet. Remove `recall` once the pipeline has proven the corpus
is live — the deploy workflow's "Verify the documentation corpus is live" step
is the signal to wait for.

### D5. Deployment risks and constraints

These are the reasons to treat D as its own effort with its own review:

1. **Runner resolved (owner, 2026-08-18): native macOS runner, Docker made
   available to it.** mac-studio can host both Docker and native macOS runners;
   for build/deploy the **native** runner is the required path, with Docker
   installed and reachable by the runner user. This matches
   `tools/ci/macos/README.md`, which describes the `[self-hosted, macos, ar-ci]`
   runner as a native shell loop — the change is granting that runner Docker
   access, not containerising it. Give the deploy job a **distinct label**
   (following the `ar-ci-cl` precedent) so it does not sit behind the general
   macOS test queue, and so a long test run cannot delay a deploy.
2. **ar-manager restarts break in-flight agent jobs.** Every running coding-agent
   job holds an MCP connection to ar-manager. A deploy that restarts the
   container mid-job drops it. Needs either a drain step (refuse deploy while
   jobs are running) or an accepted-interruption policy.
3. **Self-deployment hazard.** An auto-resolve agent job merging to master
   triggers a deploy that restarts the very server that job is talking to.
4. **The controller image needs the Maven build.** `rebuild.sh` runs `mvn` before
   building the controller image; a CI deploy must either reuse `build` job
   artifacts or repeat the build on the deploy runner.
5. **`rebuild.sh` defaults to `--no-cache`,** with `--cache` as an opt-in to work
   around transient apt/GPG errors. A CI deploy rebuilding four images from
   scratch every master merge is slow; caching strategy needs deciding.
6. **Host-mounted secrets are an advantage, not a risk.** `/Users/Shared/flowtree/secrets`,
   `manager-tokens.json`, and `workstreams.yaml` are host mounts, so the deploy
   job never handles credentials. Preserve this — do not move secrets into the
   image or into CI.
7. **`.dockerignore` correctness is security-relevant** once the build context is
   the repo root: an over-broad context risks baking local artifacts into a
   published image.

**Risk: high.** This phase should not be bundled with A–C.

---

## 6. Sequencing

```
A  retire branch_catchup ──────────────────► independent, ship first
B  reformulation in ar-manager ────────────► independent of A
        │
        ├── B needs the §7 config-home decision
        │
C  remove consultant memory tools ─────────► unblocked: B, C1 hook, and
                                             memory_namespaces are all in
        │
D  docs in ar-manager ─────────────────────► needs D2 packaging
        └── D3 deployment ─────────────────► native macOS runner + Docker
                                             enables removing consultant.recall
```

A, B, the C1 hook, and `memory_namespaces` are done, so C's removals are
unblocked — what remains for C is the removal itself plus the migration surface
in §4.3, which is documentation and hook work rather than server work. D remains
a separate effort: its packaging half (D2) is independent, its deployment half
(D3) still needs the restart policy settled.

---

## 7. Decision log

**Resolved (owner, 2026-08-18):**

2. **Interactive git context (§4.1)** — **trust the caller.** The session states
   its own `repo_url`/`branch`; the token authorises. Accuracy is protected by a
   PreToolUse hook that checks the arguments against live `git` — blocking
   writes on mismatch, warning on reads.
4. **Deploy runner (§5.5.1)** — **native macOS runner on mac-studio, with Docker
   made available to it**, under a distinct label.

**Assumed, reversibly (see §3.3):**

1. **Per-repo config home** — implemented as a **manager-side JSON file** at
   `/config/repo-config.json`, read through a single accessor so the backing
   store can move to a `repos:` section in `workstreams.yaml` later without
   touching any caller. Chosen because it keeps Phase B independently shippable:
   the YAML option couples this change to a Java controller change and a
   controller deploy. Revisit once §5's deploy pipeline exists — at that point
   the YAML option's cost drops considerably.

**Still open:**

3. **Lazy workstream auto-creation (§2.3)** — this plan recommends **against**
   it and shows it is unnecessary for the `workstream_context` use case. Not
   blocking anything; confirm at leisure, or state the case for it.
5. **Restart policy (§5.5.2)** — drain in-flight agent jobs before deploying, or
   accept interruption? Blocks D3 only.

---

## 8. What this plan deliberately does not do

- Does not remove `consult`, `search_docs`, the consultation-session tools, or
  the request-history tools. They have no ar-manager counterpart and no overlap.
- Does not address the **auth gap** that ar-consultant reaches memory with no
  caller-scoped token (AR_MANAGER_HTTP_ONLY_MIGRATION §9 item 1). Phase C closes
  it as a side effect for the tools it removes; the remaining consultant tools
  keep the untokened path until consultant is retired entirely.
- Does not split `tools/mcp/manager/server.py`. It is ~6.7k lines against a 1500
  soft limit and the prior plan says to split it while it is already being
  edited. Phases B and C edit it substantially — worth reconsidering then, but it
  is not scoped here.
